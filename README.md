# Nobel Adam Twin API

> 把个人项目文档变成一个可以被面试官追问的「数字分身」。

- 线上体验：[home.nobeladam.cn](https://home.nobeladam.cn)
- 前端仓库：[gitee.com/hidingdust/nobel-adam](https://gitee.com/hidingdust/nobel-adam)
- API 健康检查：[aichat.nobeladam.cn/api/health](https://aichat.nobeladam.cn/api/health)

---

## 这是什么

这不是一个通用聊天机器人，而是我的个人主页上那个「另一个我」的后端。

它把项目文档、技术笔记和回答边界放进一套 RAG 流程里，让面试官或访客可以直接追问：

- 你做过哪些项目？
- 晚霞猎人的评分算法怎么设计的？
- MediReport 的 MQ 怎么保证不丢消息？
- 这个功能是你写的吗？哪部分不是你做的？
- 你做过电商吗？没做过就说没做过。

回答默认第一人称、先给结论、3～5 句；不报公司名、客户名、用户量和开发时间；没整理成文档的内容不现编。

当前知识库收录了 **6 个项目 + 技术写作**，共 **70 篇文档、513 个知识块**。

---

## 线上状态

| 项 | 状态 |
|---|---|
| 前端 | 已上线 `home.nobeladam.cn` |
| 后端 | 已上线 `aichat.nobeladam.cn` |
| 对话 | SSE 流式输出 |
| 检索 | 1024 维向量 + 可选 rerank |
| 知识库 | 513 chunks，启动时加载进内存 |
| 数据库 | 无 |
| 运行内存 | 约 177MB / 448MB 上限 |

---

## 核心特性

### 1. 面向「被追问」设计

- 第一人称人格提示词：语气、长度、贡献边界、拒答规则
- 项目名识别：提到某个项目时，只检索该项目的资料，避免把 A 项目讲成 B 项目
- 跨项目检索时，同一项目最多取 3 块，防止一个项目占满上下文
- 没命中资料时按边界拒答，不硬编

### 2. 可解释的检索链路

```text
问题
  → embedding（1024 维）
  → 余弦相似度召回
  → 项目过滤 / 每项目限流
  → 可选 rerank
  → 拼 system prompt + 最近对话
  → SSE 流式生成
```

- 检索结果会带 `tags` 和 `sources`，前端展示「晚霞猎人 / 评分算法」这类标签
- `sources` 保留命中的项目、文档标题和二级标题，方便排查
- rerank 默认只在向量结果不够区分时触发，节省免费额度

### 3. 工程化保护

- **SSE 流式**：首字更快，回答不用等整段生成完
- **requestId 幂等**：同一个请求重复提交只生成一次；已完成请求直接回放
- **限流**：单 IP 10 次/分钟，另有每日/全局预算
- **前端配合**：发送中禁用、10 次/分钟本地窗口、429 冷却倒计时
- **CORS 白名单**：只允许主页域名
- **参数校验**：问题长度、历史轮数、requestId 格式都有上限
- **不保存对话**：服务端无数据库，历史由前端带最近几轮

### 4. 模型可替换

对话、embedding、rerank 全部通过环境变量配置，不写死在 Java 代码里：

```bash
TWIN_CHAT_MODEL=deepseek-v4.1-flash
TWIN_EMBEDDING_MODEL=qwen3.7-text-embedding-flash
TWIN_EMBEDDING_DIMENSIONS=1024
TWIN_RERANK_MODEL=qwen3.7-text-rerank
```

以后充值或换模型时：

- 只换对话模型：改 `.env`，重启容器
- 换 embedding 模型：重新生成索引，再重启
- 换服务商：只要兼容 OpenAI 协议，改 `TWIN_AI_BASE_URL` 和 Key 即可
- 不需要 rerank：`TWIN_RERANK_ENABLED=false`

> 换 embedding 模型必须重建索引。后端会校验 `manifest.json` 里的模型和维度，不一致会拒绝就绪，避免新旧向量空间混用。

---

## 架构

```text
浏览器 home.nobeladam.cn
  │
  │  POST /api/chat  (SSE)
  ▼
Caddy（HTTPS / 反向代理 / 关闭 SSE 缓冲）
  │
  ▼
Spring Boot 3.5 / Java 21
  ├─ RateLimitService      单 IP 滑动窗口 + 每日预算
  ├─ IdempotencyService    requestId 缓存与回放
  ├─ RetrievalService      embedding → 余弦召回 → rerank
  ├─ PromptService         persona + index + 检索片段
  ├─ AiClient              百炼 OpenAI 兼容接口 / 原生 rerank
  └─ KnowledgeIndex        启动加载 chunks + 向量 + prompt
  │
  ▼
阿里云百炼
  ├─ deepseek-v4.1-flash             对话
  ├─ qwen3.7-text-embedding-flash    1024 维向量
  └─ qwen3.7-text-rerank             可选重排
```

没有数据库、没有向量库、没有常驻 Worker。513 个 chunk 的向量约 2MB，启动加载后直接在内存里算余弦相似度，查询开销可以忽略。

---

## 技术栈

- Java 21
- Spring Boot 3.5
- Java 21 `HttpClient`（调用 OpenAI 兼容接口）
- Caffeine（限流窗口 / 幂等缓存）
- Jackson
- Python 3（离线增量索引脚本）
- Docker + Caddy
- 无 Spring AI / 无 LangChain4j：减少依赖和内存，模型切换只改配置

---

## API

### `POST /api/chat`

请求：

```json
{
  "requestId": "7f4c2c1e-8a5d-4d0e-9b7a-2f6c1d3e5a90",
  "message": "晚霞猎人的评分算法怎么设计的？",
  "history": [
    { "role": "user", "content": "你做过哪些项目？" },
    { "role": "assistant", "content": "知识库里整理了六个……" }
  ]
}
```

响应：`text/event-stream`

```text
event:meta
data:{"tags":["晚霞猎人","评分算法"],"sources":[...],"reranked":true}

event:delta
data:{"text":"核心是四因子加权打分，"}

event:delta
data:{"text":"权重 45/25/20/10……"}

event:done
data:{"tags":["晚霞猎人","评分算法"],"sources":[...]}
```

错误：

| HTTP | 含义 |
|---|---|
| `400` | 参数不合法（问题过长、历史过多、requestId 格式错误） |
| `409` | 同一个 requestId 还在生成 |
| `429` | 触发限流，响应带 `retryAfter` 和 `scope` |
| `503` | 知识库、prompt 或 Key 未就绪 |
| `502` | 模型上游异常 |

### `GET /api/health`

```json
{
  "status": "ok",
  "index": {
    "ready": true,
    "chunks": 513,
    "dimensions": 1024,
    "embeddingModel": "qwen3.7-text-embedding-flash"
  },
  "ai": {
    "configured": true,
    "chatModel": "deepseek-v4.1-flash",
    "rerankEnabled": true
  },
  "limits": {
    "perMinute": 10,
    "perDayPerIp": 100,
    "globalPerDay": 500
  }
}
```

`GET /api/health/ready` 在未就绪时返回 `503`，用于部署探活。

---

## 本地启动

### 1. 准备知识库

`knowledge/` 是私有数据，不进公开仓库：

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

### 2. 生成索引

首次全量：

```bash
python scripts/build_index.py \
  --model qwen3.7-text-embedding-flash \
  --dimensions 1024
```

之后增量：

```bash
python scripts/build_index.py
```

脚本会按 `text_for_embedding` 的 SHA-256 复用旧向量，只重算新增/修改的块。换模型时加 `--full`。

### 3. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env，填 ALI_API_KEY
```

### 4. 启动

```bash
mvn spring-boot:run
```

验证：

```bash
curl http://127.0.0.1:8080/api/health
```

---

## 配置项

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `ALI_API_KEY` | 空 | 百炼 API Key，只在服务端使用 |
| `TWIN_AI_BASE_URL` | 百炼 OpenAI 兼容地址 | 换服务商时改这里 |
| `TWIN_CHAT_MODEL` | `deepseek-v4.1-flash` | 对话模型 |
| `TWIN_EMBEDDING_MODEL` | `qwen3.7-text-embedding-flash` | 向量模型 |
| `TWIN_EMBEDDING_DIMENSIONS` | `1024` | 必须与索引一致 |
| `TWIN_RERANK_ENABLED` | `true` | 是否启用 rerank |
| `TWIN_RERANK_MODEL` | `qwen3.7-text-rerank` | 重排模型 |
| `TWIN_CORS_ALLOWED_ORIGINS` | `https://home.nobeladam.cn` | 前端白名单，逗号分隔 |
| `TWIN_RATE_LIMIT_PER_MINUTE` | `10` | 单 IP 每分钟 |
| `TWIN_RATE_LIMIT_PER_DAY_PER_IP` | `100` | 单 IP 每天 |
| `TWIN_RATE_LIMIT_GLOBAL_PER_DAY` | `500` | 全局每天 |
| `TWIN_KNOWLEDGE_DIR` | `./knowledge` | 知识库目录 |
| `TWIN_TOP_K` | `6` | 最终上下文块数 |
| `TWIN_CANDIDATE_K` | `12` | 向量召回候选数 |
| `TWIN_SIMILARITY_THRESHOLD` | `0.35` | 向量相似度阈值 |

完整配置见 `src/main/resources/application.yml`。

---

## 索引更新

```text
新增/修改文档
  → 重新切块，更新 chunks.jsonl
  → 更新 _index.md / _persona.md
  → python scripts/build_index.py
  → 上传 embeddings.jsonl + manifest.json + prompt 文件
  → 重启容器
```

增量脚本会：

1. 对每个 chunk 的 `text_for_embedding` 算哈希
2. 和旧 manifest / embeddings 对比
3. 只调用新增或变更块的 embedding
4. 复用未变块的向量
5. 删除已经不存在于 chunks 的旧向量
6. 原子写入新的 `embeddings.jsonl` 和 `manifest.json`

换 embedding 模型时必须 `--full` 全量重建，因为向量空间已经变了。

---

## 部署

线上使用：

- 服务器：腾讯云香港轻量 2C2G
- Docker 容器 `twin-api`
- 复用同机 Caddy，新增 `aichat.nobeladam.cn` 站点
- 容器限制 `448m`，JVM `-Xmx224m`
- 实测常驻内存约 177MB
- SSE 关闭 Caddy 缓冲（`flush_interval -1`）

完整步骤、命令、回滚和排障见：

**[DEPLOYMENT.md](DEPLOYMENT.md)**

---

## 目录结构

```text
src/main/java/cn/nobeladam/twin/
├─ ai/            百炼 OpenAI 兼容客户端 + rerank
├─ api/           SSE、健康检查、异常处理、DTO
├─ config/        配置绑定、CORS、HttpClient / 线程池
├─ error/         业务异常
├─ idempotency/   requestId 幂等缓存
├─ knowledge/     索引加载、项目识别
├─ prompt/        persona + 检索上下文拼装
├─ ratelimit/     单 IP / 每日 / 全局滑动窗口
└─ retrieval/     向量召回、项目过滤、rerank

knowledge/        私有知识库（gitignore）
scripts/          build_index.py 增量索引
deploy/           Caddy 站点片段
```

---

## 设计取舍

**为什么不用 Spring AI / LangChain4j？**

这个服务只需要 chat、embedding、rerank 三个 HTTP 调用。直接走 OpenAI 兼容协议，依赖更少、启动更轻，换模型/服务商只改配置。以后如果要换回 Spring AI，只需要替换 `AiClient`，其余检索、限流、SSE 逻辑不变。

**为什么不用向量数据库？**

513 个 chunk、1024 维，向量总共约 2MB。启动加载进内存，余弦相似度是微秒级；引入 pgvector / Milvus 只会多一个故障点。数据量到几万块再考虑。

**为什么不在服务端保存对话？**

个人主页不需要用户体系；历史由前端带最近几轮，服务端无状态、无数据库、无隐私留存。代价是无法跨设备续聊，但面试场景够用。

**为什么限流做在两层？**

前端限流是体验，后端限流才是安全边界。公开接口一定会被扫，单 IP 10 次/分钟 + 每日/全局预算能挡住大部分滥用。

---

## 已知边界

- 单实例内存限流；多实例部署时需要换 Redis
- 免费额度有时效，长期高流量需要充值或换模型
- 没有服务端会话持久化
- rerank 会增加一次外部调用；默认只在歧义时触发
- 回答质量依赖知识库文档的完整度和检索命中率

---

## 相关链接

- 个人主页：[home.nobeladam.cn](https://home.nobeladam.cn)
- 前端仓库：[gitee.com/hidingdust/nobel-adam](https://gitee.com/hidingdust/nobel-adam)
- 后端仓库：Gitee [nobel-adam-twin-api](https://gitee.com/hidingdust/nobel-adam-twin-api) · GitHub [nobel-adam-twin-api](https://github.com/hidingdust/nobel-adam-twin-api)
- 部署手册：[DEPLOYMENT.md](DEPLOYMENT.md)
