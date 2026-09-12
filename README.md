# WMS 分布式仓储平台

本目录是项目的详细设计与实施文档包，版本 **v0.4 / 2026-09-10**。当前已获授权进入S0工程与兼容验证，实际实现和测试状态以交付状态为准；尚无生产容量验证。

已确认：第一版独立入库、出库、库存服务（三进程/三库/独立发布）；自营仓；支持批次、序列号和效期；库存不为负；支持跨仓分配；面向超大规模演进；定时调度采用 XXL-JOB，分库分表采用 ShardingSphere-JDBC；跨仓预占采用Seata TCC，其余链路本地事务与可靠消息。京东订单规模是用户提出的规模方向，不是已取得的京东数据或本系统承载证明。

## 阅读顺序

| 文档 | 内容与使用者 |
| --- | --- |
| [总体架构](docs/design/01-architecture.md) | 服务边界、数据权威、技术栈、部署和依赖方向 |
| [领域详细设计](docs/design/02-domain.md) | 库存口径、业务规则、状态机、跨仓和序列号协议 |
| [数据库与分片](docs/design/03-data-sharding.md) | 字段级模型、约束、索引、事务、路由及迁移 |
| [API 与事件契约](docs/design/04-contracts.md) | 接口清单、请求结果、权限、幂等、错误和消息 |
| [OpenAPI 3.1](wms-contract/src/main/resources/openapi/wms-v1.yaml) | S1 已落实的 HTTP 契约；未实现切片不得把文档存在当成业务已交付 |
| [任务与对账](docs/design/05-jobs-reconciliation.md) | XXL-JOB、检查点、现有对账项目差距和接入方案 |
| [容量与运维](docs/design/06-capacity-operations.md) | 参数化容量、压测、部署、监控、灰度、恢复 |
| [决策与来源](docs/design/07-decisions-evidence.md) | 已确认/提议/待确认、版本验证清单、源码证据和官方来源 |
| [三服务拆分与恢复协议](docs/design/08-service-boundaries-protocols.md) | 首版独立边界、T1/T2/T3、执行授权、取消与补偿 |
| [Seata TCC事务设计](docs/design/09-seata-tcc.md) | 跨仓Try/Confirm/Cancel、TC权威、Fence与分片、恢复门禁 |
| [幂等协议与故障验收](docs/design/10-idempotency-protocols.md) | TCC分支归属、XID原子绑定、业务身份、安全重授权与故障断言 |
| [边缘网关与弹性演进](docs/design/11-edge-resilience.md) | 后续南北向入口/超时重试熔断如何叠在既有协议上；本轮未批准实施 |
| [具体实施计划](docs/delivery/wms-v1/DELIVERY_PLAN.md) | 唯一实施计划，阶段、任务、文件、依赖和 AC 验收矩阵 |
| [Cursor 交接](docs/delivery/wms-v1/CURSOR_HANDOFF.md) | 页面、交互、接口、数据准备与前端验收 |
| [设计评审](docs/delivery/wms-v1/REVIEW_REPORT.md) | 对关键竞态的评审及修正 |
| [交付状态](docs/delivery/wms-v1/DELIVERY_STATUS.md) | 唯一当前状态、文档检查证据、实施未开始说明 |
| [恢复入口](CODEX_PROGRESS.md) | 后续会话继续执行的上下文 |

## 本次边界

- 当前推进本项目实施和隔离本地验证；不修改其他项目、不部署生产。
- 当前目录已是Git仓库，origin为GitHub，实施使用任务分支；远程main已存在；验证通过后按持续授权正常发布，CI不含生产部署。
- Java 后端、数据库、接口与前端均可由当前实施者完成；不再按工具拆分。`wms-console/` 仍未实现。S1 已有 OpenAPI、幂等种子和 OIDC 资源服务器（issuer 来自环境）；页面仍禁止写死业务 Mock。
- 新建项目名称暂用 `wms-platform`。总体可行性为 conditional-go：核心方案可实施，版本组合、物理容量、外部数量对账适配和现场设备验收有明确前置门禁。
- 建议默认值不会被标记为用户已批准。缺少规模数字不阻塞文档完成，但阻塞生产容量签署。

## 实施入口

S0实际命令见[本地运行手册](docs/implementation/S0_RUNBOOK.md)，候选版本与尚未完成的验证见[版本记录](docs/implementation/VERSION_LOCK.md)。隔离本地中间件见 `deploy/compose.local.yml`；容器内编译启动应用见根目录 `compose.yaml` 与 `deploy/up.sh`（复制 `.env.example` 为 `.env` 后启动）。不修改共享 dev-infra，也不把编排起来当作业务验收。inventory 在显式 JDBC 时迁移并提供主数据只读 HTTP；OIDC issuer 为空则业务接口拒绝。本地 Casdoor 开通脚本在 auth-platform `deploy/wms-platform-provision.py`。`wms-console/` 已创建；未配置 OIDC 时停在登录/配置态。
