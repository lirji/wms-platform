# 基础设施与连接清单

核对基线 `main c5c96e3`（2026-09-13）。本表来自[根 Compose](../../compose.yaml)、[中间件 Compose](../../deploy/compose.local.yml)、[环境模板](../../.env.example)和[初始化目录](../../deploy/init)。本次只核对仓库声明，未连接数据库、登录控制台或读取本机真实 `.env`；不代表现场实例或生产版本已验证。

## 环境与地址

默认项目名 `wms-local`，宿主绑定 `127.0.0.1`。以下宿主端口均可由模板对应的 `WMS_*_HOST_PORT` 覆盖。容器间使用服务名，不能把容器自己的 `127.0.0.1` 当作宿主数据库。此隔离栈不加入共享 dev-infra 网络；故障验证使用独立 Testcontainers。

| 连接 ID | 用途/库 | 宿主地址 | 容器内地址 | 应用账号 / 凭据引用 |
| --- | --- | --- | --- | --- |
| `local/mysql-apps/inbound` | `wms_inbound` | `127.0.0.1:18306` | `mysql-apps:3306` | `wms_inbound` / `WMS_INBOUND_DB_PASSWORD` |
| `local/mysql-apps/outbound` | `wms_outbound` | 同上 | 同上 | `wms_outbound` / `WMS_OUTBOUND_DB_PASSWORD` |
| `local/mysql-apps/fulfillment` | `wms_fulfillment` | 同上 | 同上 | `wms_fulfillment` / `WMS_FULFILLMENT_DB_PASSWORD` |
| `local/mysql-apps/registry` | `wms_registry` | 同上 | 同上 | `wms_registry` / `WMS_SERIAL_DB_PASSWORD` |
| `local/mysql-cell-a/inventory` | `wms_inventory` | `127.0.0.1:18307` | `mysql-cell-a:3306` | `wms_inventory_a` / `WMS_INVENTORY_A_DB_PASSWORD` |
| `local/mysql-cell-b/inventory` | `wms_inventory` | `127.0.0.1:18308` | `mysql-cell-b:3306` | `wms_inventory_b` / `WMS_INVENTORY_B_DB_PASSWORD` |
| `local/kafka/events` | 命令、结果、投影事件 | `localhost:18992` | `kafka:9092` | 当前隔离配置无 SASL；不等于生产 ACL 已完成 |
| `local/redis/query` | 展示缓存，非库存权威 | `127.0.0.1:18379` | `redis:6379` | `WMS_REDIS_PASSWORD` |
| `local/seata/tc` | TCC RPC / 控制台 | `127.0.0.1:18091` / `17091` | `seata-server:8091` / `7091` | DB：`wms_seata` / `WMS_SEATA_DB_PASSWORD`；库 `seata` 位于 mysql-apps |
| `local/xxl/admin` | 调度管理 | `http://127.0.0.1:18080/xxl-job-admin` | `xxl-job-admin:8080` | DB：`wms_xxl` / `WMS_XXL_DB_PASSWORD`；库 `xxl_job` 位于 mysql-apps；执行器令牌 `WMS_XXL_ACCESS_TOKEN` |
| `external/oidc/resource-server` | 外部身份提供方 | 由 `WMS_OIDC_ISSUER` 声明 | JWKS 须从应用容器可达 | `WMS_OIDC_CLIENT_ID`、`WMS_OIDC_JWK_SET_URI`；实际账号未核验 |

Kafka 宿主端口映射到容器 `19092` 外部监听器，内部应用仍用 `9092`。`WMS_KAFKA_ADVERTISED_HOST` 必须能被相应客户端解析。Seata advertised 地址也须与客户端位置匹配；根 Compose 的应用文件配置指向 `seata-server:8091`，本机 JAR 使用宿主入口。

本地应用账号在各自 schema 获得初始化所需权限，不跨写其他服务业务表；这不是生产最小权限签署。MySQL 管理凭据分别引用 `WMS_MYSQL_APPS_ROOT_PASSWORD`、`WMS_MYSQL_CELL_A_ROOT_PASSWORD`、`WMS_MYSQL_CELL_B_ROOT_PASSWORD`，不供业务应用使用。XXL Web 管理员凭据与执行器 access token 是两种身份；此处不记录引导口令，实际开通及轮换未核验。

## 应用入口

| 服务 | 宿主入口 | 容器内部 | 数据连接 / 控制台代理 |
| --- | --- | --- | --- |
| console | `http://127.0.0.1:18180` | `console:80` | Nginx 转发下面四个业务 API 前缀 |
| inbound | `http://127.0.0.1:18181` | `inbound:18181` | `local/mysql-apps/inbound`；`/inbound-api` |
| outbound | `http://127.0.0.1:18182` | `outbound:18182` | `local/mysql-apps/outbound`；`/outbound-api` |
| inventory | `http://127.0.0.1:18183` | `inventory:18183` | `local/mysql-cell-a/inventory`；`/inventory-api` |
| serial-registry | `http://127.0.0.1:18184` | `serial-registry:18184` | `local/mysql-apps/registry`；无控制台公开代理 |
| fulfillment | `http://127.0.0.1:18185` | `fulfillment:18185` | `local/mysql-apps/fulfillment`；`/fulfillment-api` |

本机 Vite 默认 `127.0.0.1:4181`（`WMS_UI_PORT`），代理去掉前缀再请求相应后端。根 Compose 只启动一个连接 Cell A 的 inventory；启动 Cell B 数据库不等于已有第二个库存服务。

## 配置与凭据责任

| 配置组 | 仓库默认 | 启用前需要的资料 |
| --- | --- | --- |
| OIDC | issuer / client ID 为空 | issuer 与令牌一致、应用可达 JWKS、客户端及回调配置；缺 issuer/client ID 时 readiness 不通过 |
| 序列号登记 | `WMS_SERIAL_ALLOWED_SUBJECTS` 为空 | 明确受信服务主体；JDBC 模式下空名单会拒绝启动，还需要匹配 scope/企业/仓权限 |
| 运行消息 | 四服务 `WMS_*_MESSAGING_ENABLED=false` | Topic 前缀、Cell 路由、生产消费权限及积压预算；详见[消息运行](../implementation/MESSAGING_RUNTIME.md)与[多 Cell 路由](../implementation/INVENTORY_CELL_MESSAGING.md) |
| 序列号客户端 | `WMS_SERIAL_CLIENT_ENABLED=false` | 登记地址、令牌目录及受控只读挂载；本机 HTTP 仅显式允许 |
| TC 审计 / 原生 RM | `WMS_TC_AUDIT_ENABLED=false`、`WMS_TCC_RM_ENABLED=false` | 原 TC 集群/分组、只读审计源、资源地址与网络隔离；见[RM 门禁](../implementation/RUNTIME_TCC_RM.md) |
| 自动履约执行 | `WMS_FULFILLMENT_EXECUTION_ENABLED=false` | 消息和审计链路、固定 Cell 映射、企业范围、服务令牌目录及只读挂载；见[执行配置](../implementation/FULFILLMENT_EXECUTION.md) |
| 数据库时间 | UTC 模板 | 新库时间策略；旧库来源证明与固定偏移审计，见[时间规范](../implementation/DATABASE_TIME.md) |

仓库 `.gitignore` 排除 `.env` 与 `.local/`。本轮未收集真实账号密码，也未生成空的“私有密码手册”。实际凭据来源、负责人、轮换时间、生产地址/版本均待环境负责人核验；后续在受控凭据系统登记并用上述连接 ID 关联。

## 初始化与恢复边界

MySQL 初始化脚本只在空数据卷首次启动执行；已有卷不会因为更新脚本自动增加账号或 schema。业务表由各应用的 Flyway 追加迁移维护，见[数据索引](../implementation/DATA_AND_CONTRACTS.md)。先核对当前迁移和数据库时间策略；不能通过删除已有数据卷代替迁移。

`deploy/down.sh` 默认保留本项目数据卷。删除卷、共享环境故障注入、生产迁移和恢复演练均不属于本次文档任务。当前没有生产备份恢复时间、可恢复数据点或容量签署。MinIO、Elasticsearch、RabbitMQ、Nacos、Prometheus/Grafana 不在本仓库 Compose 的已部署清单内。
