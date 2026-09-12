# 本地容器运行

在容器内编译并启动本仓库已选定的进程。中间件版本见 `docs/implementation/VERSION_LOCK.md`，不复制其他产品的编排。

这不是生产部署，也不是 50 项 AC 或设备/容量验收。不要 `compose down` 或加入 sibling `dev-infra`。

## 前置

- Docker 与 Compose
- 复制口令模板：`cp .env.example .env`，把 `change-me` 换成仅本机使用的值
- 本机未占用默认端口：应用 18180–18185，库 18306–18308，Kafka 18992，Redis 18379，Seata 18091/17091，XXL 18080

## 命令

```bash
./deploy/up.sh
./deploy/down.sh
./deploy/down.sh --volumes   # 仅此时删除本编排数据卷
```

只起中间件、本机跑 JAR：仍用 `deploy/compose.local.yml`（见 `docs/implementation/S0_RUNBOOK.md`）。

## 变量（`.env.example`）

| 变量 | 用途 |
| --- | --- |
| `WMS_*_PASSWORD` / `WMS_REDIS_PASSWORD` / `WMS_XXL_ACCESS_TOKEN` | 隔离栈口令，禁止提交 `.env` |
| `WMS_BIND_ADDRESS` | 宿主绑定，默认 `127.0.0.1` |
| `WMS_*_HOST_PORT` | 宿主端口 |
| `WMS_OIDC_*` | 可选。空则业务接口拒绝，控制台停在配置态 |
| `WMS_APP_JAVA_OPTS` / `WMS_APP_MEMORY_LIMIT` | 应用堆与容器内存 |
| `WMS_JDK_IMAGE` / `WMS_JRE_IMAGE` / `WMS_NODE_IMAGE` / `WMS_NGINX_IMAGE` | 可选。覆盖编译/运行基础镜像，默认 Temurin 21.0.8_9 与 Node 22 / nginx 1.27 |

应用容器内 JDBC 使用 compose 服务名，不读宿主 `127.0.0.1` 端口。inventory 本编排只接 Cell A；Cell B 库仍启动，供种子或第二实例。

## 入口

- 控制台：`http://127.0.0.1:18180/`
- 健康：各服务 `/actuator/health`（控制台为 `/`）
- 种子仍走 `./scripts/seed-local.sh --profile isolated-wms`，JDBC 指向宿主库存 `18307`/`18308` 与应用库 `18306`，拒绝 `43306`/`dev-infra`

健康 UP 只代表进程与探针。OIDC、Kafka 投递、TCC Try、XXL 触发、真实设备另证。
