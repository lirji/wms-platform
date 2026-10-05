# WMS Platform Containerization Report

> 本地 / 开发 Compose 拓扑。**不是生产最终部署方案。** 不包含 Kubernetes / Helm。

- Skill: `project-containerization`
- Protocol: `containerization-report/v1`
- Execution time: 2026-09-16
- Target: `/Users/liruijun/personal/LLM/wms-platform`
- Mode: generate + execute + validate
- Deployment target: local
- Final status: **READY_WITH_WARNINGS**

## Project

- Type: FRONTEND_BACKEND（Maven 多模块 + `wms-console`）
- Languages: Java 21, JavaScript (Vite/React), Python（脚本，非容器服务）
- Frameworks: Spring Boot 4.1.1；console Node 22 + Nginx 1.27
- Build: Maven reactor + npm；共享 `deploy/app.Dockerfile`

## Detected Services

| Service | Kind | Runnable | Port (container / host) |
|---|---|---|---|
| inbound | application | yes | 18181 / 18181 |
| outbound | application | yes | 18182 / 18182 |
| inventory | application | yes | 18183 / 18183 |
| serial-registry | application | yes | 18184 / 18184 |
| fulfillment | application | yes | 18185 / 18185 |
| console | frontend | yes | 80 / 18180 |

Library / non-process modules (no container): `wms-contract`, `wms-runtime`, `wms-security`, `wms-test-support`, `wms-integration`, `wms-execution-driver`, parent `wms-platform`.

## Infrastructure

| Service | Image | Class | Host port |
|---|---|---|---|
| mysql-apps | mysql:8.4.11 | COMPOSE_MANAGED | 18306 |
| mysql-cell-a | mysql:8.4.11 | COMPOSE_MANAGED | 18307 |
| mysql-cell-b | mysql:8.4.11 | COMPOSE_MANAGED | 18308 |
| redis | redis:7-alpine | COMPOSE_MANAGED | 18379 |
| kafka | apache/kafka:3.8.0 | COMPOSE_MANAGED（应用默认不消费） | 18992 |
| kafka-init | apache/kafka:3.8.0 | one-shot | — |
| seata-server | apache/seata-server:2.6.0 | COMPOSE_MANAGED | **28091**（原 18091 与 sibling 冲突后改 `.env`） |
| xxl-job-admin | xuxueli/xxl-job-admin:3.4.2 | COMPOSE_MANAGED | **28080**（原 18080 冲突后改 `.env`） |

## External Dependencies

| Name | Endpoint | Required | Local replacement |
|---|---|---|---|
| OIDC / Casdoor (auth-platform) | `localhost:8000` | 业务接口要令牌；空 issuer = deny-all | **无**（本 Compose 不包含 IdP） |
| sibling `dev-infra` / `langchain4j-platform` | 已占用 18080/18091 等 | 本栈不加入其网络 | 不要接入 |

## Deployment Topology

```text
inbound        → mysql-apps/wms_inbound , OIDC(EXTERNAL)
outbound       → mysql-apps/wms_outbound , OIDC(EXTERNAL) , kafka OPTIONAL
inventory      → mysql-cell-a/wms_inventory , redis , seata-server , xxl-job-admin , kafka OPTIONAL
serial-registry→ mysql-apps/wms_registry , OIDC(EXTERNAL)
fulfillment    → mysql-apps/wms_fulfillment , OIDC(EXTERNAL) , kafka OPTIONAL
console        → inbound/outbound/inventory/fulfillment healthy
```

- Network: `wms-local` (`10.89.40.0/24`)
- Shared MySQL: `mysql-apps` 多库；inventory **不得**并入同一实例
- Flyway 拥有业务 schema；`deploy/init/**` 只负责账号/空库/Seata/XXL 系统表

## Generated Artifacts

| Path | Operation | Notes |
|---|---|---|
| `docs/deployment/containerization-report.md` | CREATE then PATCH | 本报告 |
| `compose.yaml` | KEEP | 未覆盖 |
| `deploy/compose.local.yml` | KEEP | 未覆盖 |
| `deploy/app.Dockerfile` | KEEP | 共享多 JAR multi-stage |
| `wms-console/Dockerfile` | KEEP | Node build + Nginx |
| `.dockerignore` / `.env.example` / `.gitignore` | KEEP | |

未为每个模块 CREATE Dockerfile。未再造 `compose.yml`。

## Modified Artifacts

| Path | Change | Reason |
|---|---|---|
| `.env`（gitignored） | `WMS_SEATA_HOST_PORT` 18091→28091；`WMS_XXL_ADMIN_HOST_PORT` 18080→28080 | PORT_CONFLICT vs langchain4j-platform |
| `.env`（gitignored） | `WMS_TC_AUDIT_ENABLED=false`；`WMS_FULFILLMENT_EXECUTION_ENABLED=false`；`WMS_SERIAL_TRANSFER_ENABLED=false` | 与 Compose 默认一致；缺少 `tc_audit` 账号且 execution 依赖 TC 审计 bean |
| mysql-apps 已有 volume | `CREATE/ALTER USER wms_registry` | EXISTING_VOLUME：initdb 不会在旧卷重跑 `30-serial-registry.sh` |

未修改 `application.yml`、未修改业务代码、未 `docker volume rm`。

## ChangeSet

| path | operation | reason | evidence | risk | rollback |
|---|---|---|---|---|---|
| compose.yaml | KEEP | 已有完整拓扑 | Dry Run DeploymentTopology | LOW | 无改动 |
| deploy/compose.local.yml | KEEP | 已有中间件 | 同上 | LOW | 无改动 |
| deploy/app.Dockerfile | KEEP | 共享构建 | 文件 + compose build | LOW | 无改动 |
| wms-console/Dockerfile | KEEP | 已有 | 文件 | LOW | 无改动 |
| docs/deployment/containerization-report.md | CREATE/PATCH | Skill 产物 | protocol | LOW | 删除该文件 |
| .env | PATCH | 仅本地宿主端口与可选开关 | PORT_CONFLICT / CONFIG_BINDING | MEDIUM | 恢复原端口与开关（勿提交） |

## Docker Images

| Image | Arch | Source |
|---|---|---|
| wms-platform-java:local | linux/arm64 | `deploy/app.Dockerfile` target `runtime` |
| wms-local-console:latest | linux/arm64 | `wms-console/Dockerfile` |
| mysql:8.4.11 | linux/arm64 | compose |
| redis:7-alpine | linux/arm64 | compose |
| apache/kafka:3.8.0 | linux/arm64 | compose |
| apache/seata-server:2.6.0 | linux/arm64 | compose |
| xuxueli/xxl-job-admin:3.4.2 | **linux/amd64** | 现有 `platform: linux/amd64` |

`PLATFORM_COMPATIBILITY_RISK`: XXL 在 Apple Silicon 上走 amd64 模拟；本轮已 HEALTHY，未再加 `platform`。

## Ports / Volumes / Networks

- 应用宿主端口 18180–18185 绑定 `127.0.0.1`
- 中间件宿主：18306–18308、18379、18992、**28091**、17091、**28080**
- **EXISTING_VOLUME**: `wms-local_mysql-apps-data` 等，未删除
- Network: `wms-local`

## Environment Variables

运行时覆盖，不改 `application.yml`：

- JDBC：`WMS_*_JDBC_URL` → Docker DNS `mysql-apps` / `mysql-cell-a`
- Redis：`WMS_RUNTIME_CACHE_REDISHOST=redis`
- Kafka：`WMS_MESSAGING_BOOTSTRAPSERVERS=kafka:9092`（应用默认 `WMS_*_MESSAGING_ENABLED=false`）
- Bind：Compose `WMS_BIND_ADDRESS=0.0.0.0`（容器内）；宿主映射用 `.env` `127.0.0.1`
- OIDC：EXTERNAL，**未**改写成 compose 服务名

## Secret Handling

- Dockerfile / compose / 本报告 **不含**真实口令
- `.env.example` 仍为 `change-me-*` 占位
- `.env` 仅本地；`.gitignore` 已忽略
- `deploy/.env.casdoor.json` 仍标 **SECRET_EXPOSURE**（本轮未读取或复制其值）
- 日志中的 password 字段输出为 `[REDACTED]`

## Validation L1 — Compose Config

```text
Command: docker compose --env-file .env config --quiet
Exit Code: 0
Result: PASS
Errors: none
Warnings: none
```

## Validation L2 — Build

```text
Command: docker compose --env-file .env build
Result: PASS
Duration: ~587s
Images: wms-platform-java:local ; wms-local-console
Error Category: n/a
```

未使用 `--no-cache`。

## Validation L3 — Runtime Startup

先 infra，后 application，再 console。

### Runtime Status Matrix

| Service | Status |
|---|---|
| mysql-apps | HEALTHY |
| mysql-cell-a | HEALTHY |
| mysql-cell-b | HEALTHY |
| redis | HEALTHY |
| kafka | HEALTHY |
| kafka-init | EXITED (0) |
| seata-server | HEALTHY |
| xxl-job-admin | HEALTHY |
| inbound | HEALTHY |
| outbound | HEALTHY |
| inventory | HEALTHY |
| serial-registry | HEALTHY |
| fulfillment | HEALTHY |
| console | HEALTHY |

`depends_on` 不是完整业务 readiness；应用以 `/actuator/health/readiness`（含 `wmsReadiness`）为准。

`STARTUP_READINESS_LIMITATION`: 健康 UP ≠ 50 项 AC / 登录后业务验收。

## Validation L4 — Smoke Test

| Service | Test Type | Target | Expected | Actual | Result |
|---|---|---|---|---|---|
| inbound | HTTP readiness | `http://127.0.0.1:18181/actuator/health/readiness` | 200 `{"status":"UP"}` | 200 UP | PASS |
| outbound | HTTP readiness | `:18182/.../readiness` | 200 UP | 200 UP | PASS |
| inventory | HTTP readiness | `:18183/.../readiness` | 200 UP | 200 UP | PASS |
| serial-registry | HTTP readiness | `:18184/.../readiness` | 200 UP | 200 UP | PASS |
| fulfillment | HTTP readiness | `:18185/.../readiness` | 200 UP | 200 UP | PASS |
| console | HTTP root | `http://127.0.0.1:18180/` | 200 HTML | 200 `<!doctype html>` | PASS |
| xxl-job-admin | HTTP | `http://127.0.0.1:28080/` | 可响应 | 302 | PASS |
| seata console | HTTP | `:17091` | 可选 | empty reply | WARN |
| OIDC login | browser | localhost:8000 | 未做登录 | 跳过 | SKIPPED |

Functional dependency: 五应用 readiness UP 证明 JDBC（及 inventory 的 Redis/Seata depends_on）可达。未做破坏性业务写。

## Repair History

| Round | Failure | Root Cause | Files / actions | Revalidate |
|---|---|---|---|---|
| 1 | seata bind 18091 failed | PORT_CONFLICT：`langchain4j-platform-vision-service` 占用 18091；gateway 占用 18080 | PATCH `.env` 宿主端口 28091 / 28080 | seata + xxl HEALTHY |
| 2 | serial-registry Access denied `wms_registry` | EXISTING_VOLUME：旧卷不重跑 `30-serial-registry.sh` | 在 mysql-apps **容器内** CREATE/ALTER USER（未删卷） | serial-registry HEALTHY |
| 3 | fulfillment Access denied `tc_audit` → 关闭审计后缺 `TcEvidenceScope` | 本地 `.env` 打开 TC 审计/自动履约，但 init **没有** `tc_audit`；execution 配置依赖审计 bean | PATCH `.env` 将 TC 审计与 fulfillment/serial-transfer execution 恢复为 Compose 默认 `false` | fulfillment HEALTHY |

Repair rounds used: **3 / 3**。未 `volume rm` / prune。未改业务 Java。

## Risks

| Code | Level | Note |
|---|---|---|
| SECRET_EXPOSURE | HIGH | `deploy/.env.casdoor.json` 仍在树内 |
| EXTERNAL_DEPENDENCY | HIGH | OIDC 未做登录 smoke |
| PORT_CONFLICT | MEDIUM | 已用 `.env` 错开 18080/18091；其他机器仍可能撞车 |
| EXISTING_VOLUME | MEDIUM | 旧卷账号与当前 `.env` 可能再漂移 |
| PLATFORM_COMPATIBILITY_RISK | MEDIUM | xxl-job-admin amd64 on ARM |
| RESOURCE_PRESSURE_WARNING | MEDIUM | 3×MySQL + Kafka + Seata + XXL + 5×Java |
| SERVICE_DISCOVERY | LOW | `WMS_SEATA_ADVERTISED_IP=127.0.0.1`；本轮 inventory 已 healthy |

## Warnings

- Kafka / 消息开关默认 false，未验证 Outbox 消费。
- mysql-cell-b 已启动但 inventory 只接 cell-a。
- Seata HTTP console `:17091` 无有效 HTTP 响应；8091 healthcheck 已过。
- 本机 `.env` 开关被收敛到 Compose 默认；若要开 TC 审计/自动履约，需先有 `tc_audit` 账号与配套配置，不能只改布尔值。

## Unknowns

- 宿主机 `java -jar` 与 Compose JDBC 双轨仍以环境变量为准。
- OIDC 登录后控制台业务链路未在本轮验证。

## Manual Actions

1. 浏览器打开 `http://127.0.0.1:18180/`，用外部 IdP 登录（本报告不存放口令）。
2. 需要 TC 审计/自动履约时：先补齐 `tc_audit` 与网络边界，再打开对应开关。
3. 不要用 `deploy/down.sh --volumes` 当作修库手段。

## Rollback Plan

（本阶段不自动执行）

- CREATE 本报告 → 删除 `docs/deployment/containerization-report.md`
- KEEP Docker 文件 → 无需回滚
- PATCH `.env` 端口/开关 → 手工恢复原值（文件被 gitignore）
- mysql `wms_registry` 用户 → 保留（旧卷所需）；不要删 volume

## Audit

```text
Skill:           project-containerization
Driver:          execute_local_commands + write_design_docs；未 modify_runtime_files（Docker 文件 KEEP）
Router:          PROJECT_CONTAINERIZATION（上一阶段 Dry Run READY_FOR_GENERATION）
Execution Plan:  KEEP existing compose/Dockerfile → L1 config → L2 build → infra up → apps up → smoke
ChangeSet:       见上
Commands:        docker compose config --quiet (0);
                 docker compose build (~587s, 0);
                 docker compose up -d <infra then apps then console>
                 禁止: volume rm / system prune / down -v
Command Results: L1 PASS; L2 PASS; L3 HEALTHY; L4 apps+console PASS
Artifacts:       docs/deployment/containerization-report.md
Repair Attempts: 3
Final Validation: READY_WITH_WARNINGS
Final Status:    READY_WITH_WARNINGS
```

## Final Status

**READY_WITH_WARNINGS**

核心应用与控制台已构建、启动并通过真实 readiness/入口 smoke。警告：OIDC 未登录验证、可选 Kafka/TC 审计未开启、XXL amd64、资源压力、密钥文件仍在仓库树内。
