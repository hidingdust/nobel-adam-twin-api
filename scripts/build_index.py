#!/usr/bin/env python3
"""Build embeddings for the twin knowledge base.

默认只重算新增/修改的 chunk；换模型或 --full 时全量重建。
不依赖第三方库，直接调用百炼的 OpenAI 兼容 embeddings 接口。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
DEFAULT_MODEL = "qwen3.7-text-embedding-flash"
DEFAULT_DIMENSIONS = 1024


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build incremental embeddings for chunks.jsonl")
    parser.add_argument("--chunks", default="knowledge/index/chunks.jsonl")
    parser.add_argument("--out", default="knowledge/index/embeddings.jsonl")
    parser.add_argument("--manifest", default="knowledge/index/manifest.json")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--dimensions", type=int, default=DEFAULT_DIMENSIONS)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--api-key-env", default="ALI_API_KEY")
    parser.add_argument("--batch-size", type=int, default=10)
    parser.add_argument("--sleep", type=float, default=0.2)
    parser.add_argument("--full", action="store_true", help="忽略旧向量，全量重建")
    parser.add_argument("--dry-run", action="store_true", help="只统计，不调用接口")
    parser.add_argument("--limit", type=int, default=0, help="只处理前 N 个待 embedding 的块，调试用")
    return parser.parse_args()


def load_chunks(path: Path) -> list[dict]:
    if not path.is_file():
        raise SystemExit(f"chunks 文件不存在：{path}")
    chunks = []
    seen = set()
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            node = json.loads(line)
            chunk_id = node.get("chunk_id", "")
            if not chunk_id:
                raise SystemExit(f"第 {line_number} 行缺少 chunk_id")
            if chunk_id in seen:
                raise SystemExit(f"chunk_id 重复：{chunk_id}")
            seen.add(chunk_id)
            text = node.get("text_for_embedding") or node.get("text") or ""
            if not text.strip():
                raise SystemExit(f"chunk 没有可 embedding 的文本：{chunk_id}")
            chunks.append({"id": chunk_id, "text": text, "hash": sha256(text)})
    if not chunks:
        raise SystemExit("chunks.jsonl 为空")
    return chunks


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_existing(path: Path, manifest_path: Path, model: str, dimensions: int, force_full: bool) -> dict[str, dict]:
    if force_full or not path.is_file() or not manifest_path.is_file():
        return {}
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception:
        return {}
    if manifest.get("embedding_model") != model or manifest.get("dimensions") != dimensions:
        print("旧索引的模型/维度和当前配置不一致，自动全量重建")
        return {}

    existing = {}
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                node = json.loads(line)
            except Exception:
                continue
            chunk_id = node.get("chunk_id", "")
            vector = node.get("embedding")
            if chunk_id and isinstance(vector, list) and len(vector) == dimensions:
                existing[chunk_id] = node
    return existing


def call_embeddings(base_url: str, api_key: str, model: str, dimensions: int, texts: list[str]) -> tuple[list[list[float]], int, int]:
    payload = {
        "model": model,
        "input": texts,
        "dimensions": dimensions,
        "encoding_format": "float",
    }
    request = urllib.request.Request(
        base_url.rstrip("/") + "/embeddings",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )

    last_error = None
    for attempt in range(1, 6):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                body = json.loads(response.read().decode("utf-8"))
            data = body.get("data", [])
            if not isinstance(data, list) or len(data) != len(texts):
                raise RuntimeError(f"embedding 返回数量不一致：期望 {len(texts)}，实际 {len(data)}")
            data = sorted(data, key=lambda item: item.get("index", 0))
            vectors = []
            for item in data:
                vector = item.get("embedding", [])
                if not isinstance(vector, list) or len(vector) != dimensions:
                    raise RuntimeError("embedding 维度不一致")
                vectors.append(vector)
            usage = body.get("usage", {}) or {}
            return vectors, int(usage.get("total_tokens", 0)), int(usage.get("prompt_tokens", 0))
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            last_error = f"HTTP {error.code}: {body[:500]}"
            if error.code == 403:
                raise SystemExit(
                    "调用 embedding 失败（403）。请检查免费额度或关闭“仅使用免费额度”模式。\n" + last_error
                )
            if error.code in (429, 500, 502, 503, 504) and attempt < 5:
                time.sleep(min(8, 2 ** attempt))
                continue
            raise SystemExit(last_error)
        except Exception as error:
            last_error = f"{type(error).__name__}: {error}"
            if attempt < 5:
                time.sleep(min(8, 2 ** attempt))
                continue
            raise SystemExit(last_error)

    raise SystemExit(last_error or "embedding 调用失败")


def write_atomic(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(content, encoding="utf-8")
    os.replace(temp, path)


def main() -> int:
    args = parse_args()
    chunks_path = Path(args.chunks)
    out_path = Path(args.out)
    manifest_path = Path(args.manifest)

    chunks = load_chunks(chunks_path)
    existing = load_existing(out_path, manifest_path, args.model, args.dimensions, args.full)

    todo = []
    reused = 0
    for chunk in chunks:
        old = existing.get(chunk["id"])
        if old and old.get("hash") == chunk["hash"]:
            reused += 1
        else:
            todo.append(chunk)

    removed = len(set(existing) - {chunk["id"] for chunk in chunks})
    if args.limit > 0:
        todo = todo[: args.limit]

    print(f"chunks={len(chunks)} reused={reused} to_embed={len(todo)} removed={removed} model={args.model}")
    if args.dry_run:
        return 0
    if not todo:
        print("没有需要重新 embedding 的块")
    if not todo and out_path.is_file():
        # 仍刷新 manifest 的时间和源文件哈希
        pass

    api_key = os.environ.get(args.api_key_env)
    if not api_key:
        raise SystemExit(f"环境变量 {args.api_key_env} 为空")

    new_vectors: dict[str, dict] = {}
    total_tokens = 0
    prompt_tokens = 0
    started = time.time()

    for offset in range(0, len(todo), args.batch_size):
        batch = todo[offset : offset + args.batch_size]
        texts = [item["text"] for item in batch]
        vectors, tokens, prompt = call_embeddings(args.base_url, api_key, args.model, args.dimensions, texts)
        total_tokens += tokens
        prompt_tokens += prompt
        for item, vector in zip(batch, vectors):
            new_vectors[item["id"]] = {
                "chunk_id": item["id"],
                "hash": item["hash"],
                "embedding": vector,
            }
        done = min(offset + len(batch), len(todo))
        print(f"  {done}/{len(todo)} embedded（tokens={total_tokens}）")
        if args.sleep > 0 and done < len(todo):
            time.sleep(args.sleep)

    merged = {}
    for chunk in chunks:
        chunk_id = chunk["id"]
        if chunk_id in new_vectors:
            merged[chunk_id] = new_vectors[chunk_id]
        elif chunk_id in existing:
            merged[chunk_id] = existing[chunk_id]
        else:
            raise SystemExit(f"缺少向量：{chunk_id}")

    lines = [
        json.dumps(merged[chunk["id"]], ensure_ascii=False, separators=(",", ":"))
        for chunk in chunks
    ]
    write_atomic(out_path, "\n".join(lines) + "\n")

    source_hash = sha256(chunks_path.read_text(encoding="utf-8"))
    manifest = {
        "embedding_model": args.model,
        "dimensions": args.dimensions,
        "chunks": len(chunks),
        "source": chunks_path.name,
        "source_sha256": source_hash,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "tokens_used_this_run": total_tokens,
        "prompt_tokens_this_run": prompt_tokens,
        "elapsed_seconds": round(time.time() - started, 2),
    }
    write_atomic(manifest_path, json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")

    print(f"完成：{out_path}（chunks={len(chunks)}，本次 tokens={total_tokens}，耗时 {manifest['elapsed_seconds']}s）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
