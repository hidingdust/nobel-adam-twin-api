# 部署手册（手把手版）

这份文档按“我没有部署过”的视角写。照顺序做即可。

## 0. 最终结构

```text
浏览器 home.nobeladam.cn
  └─ POST https://aichat.nobeladam.cn/api/chat (SSE)
        │
        ▼
sunset-hunter 服务器 43.135.4.67
  ├─ Caddy 容器 sunset-hunter-caddy-1（已有，占 80/443）
  │    └─ aichat.nobeladam.cn → twin-api:8080
  └─ Docker 网络 sunset-hunter_default
       └─ twin-api 容器（本项目，2G 机器上限制 448m）
```

- 不新开 Caddy
- 不占用 `api.nobeladam.cn`（那是 sunset-hunter）
- 知识库挂载在容器里，不烤进镜像
- 前端仍然在 `home.nobeladam.cn`，`aichat` 只是 API 域名

## 1. 你需要在控制台做的事

### 1.1 DNS

在 DNSPod / 腾讯云云解析里给 `nobeladam.cn` 加：

| 字段 | 值 |
|---|---|
| 主机记录 | `aichat` |
| 类型 | `A` |
| 记录值 | `43.135.4.67` |
| TTL | 600 |

验证（本机 PowerShell）：

```powershell
Resolve-DnsName aichat.nobeladam.cn -Type A
```

应该返回 `43.135.4.67`。

### 1.2 模型额度

当前使用：

- 对话：`deepseek-v4.1-flash`
- Embedding：`qwen3.7-text-embedding-flash`
- Rerank：`qwen3.7-text-rerank`

如果百炼控制台提示免费额度用尽，需要关闭“仅使用免费额度”并充值，或改用有额度的模型。

## 2. 登录服务器

sunset-hunter 服务器本机可以直连，不需要跳板：

```powershell
ssh -i C:\Users\Administrator\.ssh\pulse_deploy ubuntu@43.135.4.67
```

看到 `ubuntu@VM-0-4-ubuntu` 就成功了。

> 说明：最初想把 AI 服务放 PULSE 那台，但实测 PULSE 服务器访问不了 `dashscope.aliyuncs.com`（连接超时）；
> sunset-hunter 这台可以正常访问百炼，所以最终把 AI 服务放在这台，复用它的 Caddy。
> 仍然不碰 `api.nobeladam.cn` 的现有接口，只是同一台服务器上多一个独立容器。

## 3. 服务器上创建目录

```bash
sudo mkdir -p /opt/nobel-adam-twin
sudo chown -R ubuntu:ubuntu /opt/nobel-adam-twin
mkdir -p /opt/nobel-adam-twin/knowledge/system-prompt
mkdir -p /opt/nobel-adam-twin/knowledge/index
```

## 4. 本机准备部署包

在项目根目录 `D:\OpenCodeProjects\nobel-adam-twin-api` 执行：

```powershell
mvn clean package -DskipTests

# 复制成固定名字，Dockerfile 里用 app.jar
Copy-Item target\nobel-adam-twin-api-0.1.0.jar app.jar -Force

# 生成 .env（不要提交到 Git）
Copy-Item .env.example .env -Force
# 然后编辑 .env，填 ALI_API_KEY
```

`.env` 至少包含：

```text
ALI_API_KEY=sk-...
TWIN_CHAT_MODEL=deepseek-v4.1-flash
TWIN_EMBEDDING_MODEL=qwen3.7-text-embedding-flash
TWIN_EMBEDDING_DIMENSIONS=1024
TWIN_CORS_ALLOWED_ORIGINS=https://home.nobeladam.cn
```

## 5. 上传文件

在项目根目录执行（第二个 PowerShell 窗口不要关）：

```powershell
scp -P 22022 -i C:\Users\Administrator\.ssh\pulse_deploy app.jar ubuntu@43.135.4.67:/opt/nobel-adam-twin/
scp -P 22022 -i C:\Users\Administrator\.ssh\pulse_deploy Dockerfile ubuntu@43.135.4.67:/opt/nobel-adam-twin/
scp -P 22022 -i C:\Users\Administrator\.ssh\pulse_deploy docker-compose.yml ubuntu@43.135.4.67:/opt/nobel-adam-twin/
scp -P 22022 -i C:\Users\Administrator\.ssh\pulse_deploy .dockerignore ubuntu@43.135.4.67:/opt/nobel-adam-twin/
scp -P 22022 -i C:\Users\Administrator\.ssh\pulse_deploy .env ubuntu@43.135.4.67:/opt/nobel-adam-twin/
```

上传知识库（约 11MB）：

```powershell
scp -P 22022 -i C:\Users\Administrator\.ssh\pulse_deploy -r knowledge\system-prompt ubuntu@43.135.4.67:/opt/nobel-adam-twin/knowledge/
scp -P 22022 -i C:\Users\Administrator\.ssh\pulse_deploy -r knowledge\index ubuntu@43.135.4.67:/opt/nobel-adam-twin/knowledge/
```

服务器上收权限：

```bash
chmod 600 /opt/nobel-adam-twin/.env
chmod -R u+rwX,go-rwx /opt/nobel-adam-twin/knowledge
```

## 6. 启动容器

```bash
cd /opt/nobel-adam-twin
sudo docker compose up -d --build
```

看状态：

```bash
sudo docker ps --filter name=twin-api
sudo docker logs -f twin-api
```

日志出现类似：

```text
知识索引加载完成：chunks=513, dimensions=1024, embeddingModel=qwen3.7-text-embedding-flash, rerank=true
Tomcat started on port 8080
```

本地健康检查：

```bash
curl http://127.0.0.1:18080/api/health
curl http://127.0.0.1:18080/api/health/ready
```

`/api/health` 里应有：

```json
{
  "status": "ok",
  "index": { "ready": true, "chunks": 513 },
  "ai": { "configured": true }
}
```

## 7. 加 Caddy 站点

先备份：

```bash
sudo cp /opt/sunset-hunter/Caddyfile /opt/sunset-hunter/Caddyfile.bak-$(date +%Y%m%d-%H%M%S)
```

把下面内容追加到 `/opt/sunset-hunter/Caddyfile`：

```caddyfile
aichat.nobeladam.cn {
	encode gzip
	reverse_proxy twin-api:8080 {
		flush_interval -1
	}
}
```

校验并重载：

```bash
sudo docker exec sunset-hunter-caddy-1 caddy validate --config /etc/caddy/Caddyfile
sudo docker exec sunset-hunter-caddy-1 caddy reload --config /etc/caddy/Caddyfile
```

看 Caddy 日志：

```bash
sudo docker logs --tail 80 sunset-hunter-caddy-1
```

首次访问 `https://aichat.nobeladam.cn` 时 Caddy 会自动申请证书。DNS 没生效前证书会失败，等 DNS 生效后重试。

## 8. 验证线上接口

```bash
curl https://aichat.nobeladam.cn/api/health
```

SSE 测试：

```bash
curl -N -X POST https://aichat.nobeladam.cn/api/chat \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"requestId":"prod-test-001","message":"晚霞猎人的评分算法怎么设计的？","history":[]}'
```

应该先看到 `event:meta`，然后连续出现 `event:delta`，最后 `event:done`。

## 9. 前端接入

前端请求地址：

```text
https://aichat.nobeladam.cn/api/chat
```

请求体：

```json
{
  "requestId": "crypto.randomUUID()",
  "message": "用户问题",
  "history": [
    { "role": "user", "content": "上一轮问题" },
    { "role": "assistant", "content": "上一轮回答" }
  ]
}
```

CORS 只允许 `.env` 里的：

```text
TWIN_CORS_ALLOWED_ORIGINS=https://home.nobeladam.cn
```

如果以后加 `https://www.nobeladam.cn`，用逗号分隔。

## 10. 以后加项目 / 更新知识库

1. 修改 `D:\WorkBuddyWorkSpace\ai-twin-rag` 里的源文档
2. 重新生成 `chunks.jsonl`
3. 更新 `_index.md` 的项目清单
4. 本机跑增量向量：

```powershell
python scripts/build_index.py --model qwen3.7-text-embedding-flash --dimensions 1024
```

5. 上传新的 `chunks.jsonl`、`embeddings.jsonl`、`manifest.json`、prompt 文件
6. 重启：

```bash
sudo docker compose -f /opt/nobel-adam-twin/docker-compose.yml restart
```

增量脚本会按内容哈希复用旧向量，只算新增/修改的块。

## 11. 以后换模型

只换对话模型：

```bash
# 编辑 /opt/nobel-adam-twin/.env
TWIN_CHAT_MODEL=qwen-plus
```

然后：

```bash
cd /opt/nobel-adam-twin
sudo docker compose up -d
```

换 embedding 模型：

1. 修改 `.env` 的 `TWIN_EMBEDDING_MODEL` / `TWIN_EMBEDDING_DIMENSIONS`
2. 本机全量重建索引：

```powershell
python scripts/build_index.py --full --model 新模型 --dimensions 新维度
```

3. 上传新的 `embeddings.jsonl` 和 `manifest.json`
4. 重启容器

后端启动时会校验 `manifest.embedding_model` 和 `dimensions`，不一致会拒绝就绪，避免新旧向量空间混用。

## 12. 常用运维命令

```bash
# 资源占用
sudo docker stats --no-stream

# 健康
curl http://127.0.0.1:18080/api/health/ready

# 日志
sudo docker logs --tail 200 twin-api

# 重启
cd /opt/nobel-adam-twin && sudo docker compose restart

# 停止
cd /opt/nobel-adam-twin && sudo docker compose down

# 重新构建
cd /opt/nobel-adam-twin && sudo docker compose up -d --build
```

## 13. 回滚

上传新 jar 前先备份：

```bash
cp /opt/nobel-adam-twin/app.jar /opt/nobel-adam-twin/app.jar.bak
```

出问题时：

```bash
cp /opt/nobel-adam-twin/app.jar.bak /opt/nobel-adam-twin/app.jar
cd /opt/nobel-adam-twin && sudo docker compose up -d --build
```

Caddy 回滚：

```bash
sudo cp /opt/sunset-hunter/Caddyfile.bak-... /opt/sunset-hunter/Caddyfile
sudo docker exec sunset-hunter-caddy-1 caddy reload --config /etc/caddy/Caddyfile
```

## 14. 常见问题

### 502 Bad Gateway

- `twin-api` 容器没起来：`sudo docker ps -a | grep twin-api`
- Caddy 和 twin-api 不在同一个网络：`sudo docker inspect twin-api --format '{{json .NetworkSettings.Networks}}'`，应该有 `sunset-hunter_default`
- 看日志：`sudo docker logs --tail 100 twin-api`

### 503 / status=degraded

- 知识库文件不完整
- `manifest.json` 的模型/维度和 `.env` 不一致
- `ALI_API_KEY` 没进容器：`sudo docker exec twin-api env | grep ALI_API_KEY`（不要截图给别人）
- 看 `/api/health` 的 `index.detail`

### 429

正常限流，返回里有 `retryAfter`。前端应显示友好提示并倒计时。

### SSE 一直不输出 / 最后一次性输出

- Caddy 的 `flush_interval -1` 没加或没 reload
- 中间还有 CDN/代理缓冲；`aichat` 走 DNS only，不要开代理

### 模型 403 AllocationQuota.FreeTierOnly

免费额度用尽，或账号处于“仅使用免费额度”模式。去百炼控制台充值/关闭该模式，或换模型。

## 15. 安全约定

- `.env` 永不提交 Git
- `knowledge/` 永不提交公开仓库
- 不把 Key 发到聊天/群里
- 服务器上 `.env` 权限 600
- 公开仓库只放代码、Dockerfile、部署文档
