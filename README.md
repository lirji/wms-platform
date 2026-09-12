# WMS 分布式仓储平台

本项目已包含 5 个独立后端进程和 React 作业控制台，覆盖入库、出库、库存、序列号登记与跨仓履约。当前仍在后端整改和验收阶段，尚未完成生产容量、真实设备与全部 50 项 AC 验收。

本轮文档按已发布 `main c5c96e3` 核对（2026-09-13）。从[文档总入口](docs/README.md)查看当前架构、运行方式、连接配置、数据与接口；已交付和剩余工作以[交付状态](docs/delivery/wms-v1/DELIVERY_STATUS.md)为准。原始设计中的独立查询服务、独立集成服务和部分基础设施仍是演进目标。

已确认：自营仓；入库、出库、库存独立进程与数据所有权；支持批次、序列号和效期；库存不为负；跨仓预占采用 Seata TCC，其余链路使用本地事务与可靠消息。序列号唯一范围为企业 + SKU + SN，质检按收货批次处理。规模方向不等于已取得的容量证明。

## 阅读顺序

| 文档 | 内容与使用者 |
| --- | --- |
| [总体架构](docs/design/01-architecture.md) | 服务边界、数据权威、技术栈、部署和依赖方向 |
| [领域详细设计](docs/design/02-domain.md) | 库存口径、业务规则、状态机、跨仓和序列号协议 |
| [数据库与分片](docs/design/03-data-sharding.md) | 字段级模型、约束、索引、事务、路由及迁移 |
| [API 与事件契约](docs/design/04-contracts.md) | 接口清单、请求结果、权限、幂等、错误和消息 |
| [OpenAPI 3.1](wms-contract/src/main/resources/openapi/wms-v1.yaml) | 当前 HTTP 契约；接口存在不等于全部业务闭环已验收 |
| [任务与对账](docs/design/05-jobs-reconciliation.md) | XXL-JOB、检查点、现有对账项目差距和接入方案 |
| [容量与运维](docs/design/06-capacity-operations.md) | 参数化容量、压测、部署、监控、灰度、恢复 |
| [决策与来源](docs/design/07-decisions-evidence.md) | 已确认/提议/待确认、版本验证清单、源码证据和官方来源 |
| [三服务拆分与恢复协议](docs/design/08-service-boundaries-protocols.md) | 首版独立边界、T1/T2/T3、执行授权、取消与补偿 |
| [Seata TCC事务设计](docs/design/09-seata-tcc.md) | 跨仓Try/Confirm/Cancel、TC权威、Fence与分片、恢复门禁 |
| [幂等协议与故障验收](docs/design/10-idempotency-protocols.md) | TCC分支归属、XID原子绑定、业务身份、安全重授权与故障断言 |
| [边缘网关与弹性演进](docs/design/11-edge-resilience.md) | 后续南北向入口/超时重试熔断如何叠在既有协议上；本轮未批准实施 |
| [控制台前端框定](docs/design/console-frontend/BRIEF.md) | 作业角色、非目标与视口假设 |
| [控制台前端架构](docs/design/console-frontend/FRONTEND_ARCHITECTURE.md) | 路由树、单应用、栈约束、状态矩阵 |
| [控制台前端切片](docs/design/console-frontend/IMPLEMENTATION_SLICES.md) | F0–F4 落地顺序 |
| [具体实施计划](docs/delivery/wms-v1/DELIVERY_PLAN.md) | 唯一实施计划，阶段、任务、文件、依赖和 AC 验收矩阵 |
| [Cursor 交接](docs/delivery/wms-v1/CURSOR_HANDOFF.md) | 页面、交互、接口、数据准备与前端验收 |
| [设计评审](docs/delivery/wms-v1/REVIEW_REPORT.md) | 对关键竞态的评审及修正 |
| [交付状态](docs/delivery/wms-v1/DELIVERY_STATUS.md) | 当前发布基线、能力边界、剩余整改和验收证据 |
| [恢复入口](CODEX_PROGRESS.md) | 后续会话继续执行的上下文 |

## 本次边界

- 当前推进本项目实施和隔离本地验证；不修改其他项目、不部署生产。
- 当前目录已是Git仓库，origin为GitHub，实施使用任务分支；远程main已存在；验证通过后按持续授权正常发布，CI不含生产部署。
- Java 后端、数据库、接口与前端均可由当前实施者完成；不再按工具拆分。`wms-console/` 已按作业模块落地；OIDC issuer 来自环境，页面禁止写死业务 Mock。
- 新建项目名称暂用 `wms-platform`。总体可行性为 conditional-go：核心方案可实施，版本组合、物理容量、外部数量对账适配和现场设备验收有明确前置门禁。
- 建议默认值不会被标记为用户已批准。缺少规模数字不阻塞文档完成，但阻塞生产容量签署。

## 实施入口

从[本地运行与验证](docs/implementation/S0_RUNBOOK.md)选择容器或本机开发路径，容器步骤见[部署目录说明](deploy/README.md)。配置前先看[基础设施与连接清单](docs/operations/INFRASTRUCTURE.md)：`.env.example` 是模板，完整启动还需要 OIDC 与序列号受信主体配置；默认消息、原生 RM 和自动履约执行关闭。inventory 默认只连接 Cell A。

本仓库 CI 不含生产部署。本机隔离验证不操作共享 dev-infra。文档整理不代表重新启动过环境或重新完成业务验收；本次复用已发布代码的既有 CI 证据。
