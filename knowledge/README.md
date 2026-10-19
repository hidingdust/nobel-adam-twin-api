# knowledge（私有数据，不提交）

这个目录放数字分身的私有资料，仓库里只保留本说明。

本地/服务器目录结构：

```text
knowledge/
├─ system-prompt/
│  ├─ _persona.md
│  └─ _index.md
└─ index/
   ├─ chunks.jsonl
   ├─ embeddings.jsonl
   └─ manifest.json
```

来源：

- `system-prompt/` 来自 `D:\WorkBuddyWorkSpace\ai-twin-rag\rag-ingest\system-prompt\`
- `chunks.jsonl` 来自 `D:\WorkBuddyWorkSpace\ai-twin-rag\rag-build\chunks.jsonl`
- `embeddings.jsonl` / `manifest.json` 由 `scripts/build_index.py` 生成

公开仓库不包含这些文件；部署时通过 `scp` 上传到服务器 `/opt/nobel-adam-twin/knowledge`。

更换 embedding 模型后必须重新生成索引，否则后端会因 manifest 中的模型/维度不一致而拒绝启动。
