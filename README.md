# Nobel Adam Twin API

主页「另一个我」数字分身的后端。

- Spring Boot 3.5 / Java 21
- 百炼 OpenAI 兼容接口（chat + embedding）
- 1024 维向量检索 + 可选 rerank
- SSE 流式回答
- 单 IP 10 次/分钟、每日预算、requestId 幂等
- Docker + 现有 Caddy 反向代理

## 模型

当前默认使用免费额度模型：

| 用途 | 模型 |
|---|---|
| 对话 | `deepseek-v4.1-flash` |
| Embedding | `qwen3.7-text-embedding-flash`（1024 维） |
| Rerank | `qwen3.7-text-rerank` |

全部通过环境变量配置。以后充值或换模型时，不需要改 Java 代码：

```bash
TWIN_CHAT_MODEL=qwen-plus
TWIN_EMBEDDING_MODEL=qwen3.7-text-embedding
TWIN_EMBEDDING_DIMENSIONS=1024
```

**换 embedding 模型必须重建索引**，否则 `/api/health` 会提示索引模型/维度不一致，`/api/chat` 返回 503。

## 本地运行

```powershell
# 1. 准备知识库（私有数据，不提交）
# knowledge/system-prompt/_persona.md
# knowledge/system-prompt/_index.md
# knowledge/index/chunks.jsonl

# 2. 生成向量（首次全量，之后增量）
python scripts/build_index.py --model qwen3.7-text-embedding-flash --dimensions 1024

# 3. 配置环境变量
Copy-Item .env.example .env
# 编辑 .env，填 ALI_API_KEY

# 4. 启动
mvn spring-boot:run
```

健康检查：

```bash
curl http://127.0.0.1:8080/api/health
curl http://127.0.0.1:8080/api/health/ready
```

对话：

```bash
curl -N -X POST http://127.0.0.1:8080/api/chat \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"requestId":"demo-001","message":"晚霞猎人的评分算法怎么设计的？","history":[]}'
```

## API

`POST /api/chat`，返回 SSE：

```text
event: meta
data: {"tags":["晚霞猎人","评分算法"],"sources":[...]}

event: delta
data: {"text":"霞光指数是..."}

event: done
data: {"tags":[...],"sources":[...]}
```

错误：

- `400` 参数不合法
- `409` 同一个 requestId 还在生成
- `429` 触发限流，带 `retryAfter`
- `503` 知识库/Key 未就绪

## 目录

```text
src/main/java/cn/nobeladam/twin/
  ai/           百炼 HTTP 客户端
  api/          SSE、健康检查、错误处理
  config/       配置与 CORS
  idempotency/  requestId 缓存
  knowledge/    索引加载、项目识别
  prompt/       系统提示词与检索上下文
  ratelimit/    滑动窗口限流
  retrieval/    向量检索 + rerank

knowledge/      私有数据（gitignore）
scripts/        增量索引脚本
deploy/         部署用 Caddy 片段
```

## 部署

见 [DEPLOYMENT.md](DEPLOYMENT.md)。
