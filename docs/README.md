# 项目文档入口

本次核对基线：已发布 `main c5c96e3`，日期 2026-09-13。仓库已有 10 个 Maven 模块、5 个后端应用和 1 个前端应用。完整交付仍在进行；未合入 main 的本地开发不计入本文的已实现能力。

## 按任务阅读

| 你要做什么 | 主文档 | 阅读结果 |
| --- | --- | --- |
| 了解项目与服务关系 | [总体架构](design/01-architecture.md) | 当前进程、模块职责、数据权威与演进目标 |
| 启动与调试 | [本地运行与验证](implementation/S0_RUNBOOK.md)、[容器启动](../deploy/README.md)、[前端开发](../wms-console/README.md) | 配置前提、命令、端口与常见失败 |
| 配置数据库和中间件 | [基础设施与连接清单](operations/INFRASTRUCTURE.md) | 宿主/容器地址、数据库、账号角色、凭据变量与核验状态 |
| 核对版本和依赖风险 | [版本记录](implementation/VERSION_LOCK.md) | POM、锁文件、镜像标签及已有 SBOM/OSV 证据 |
| 修改数据库或对接接口 | [数据与接口索引](implementation/DATA_AND_CONTRACTS.md) | 数据归属、迁移入口、OpenAPI、权限及事件协议 |
| 看业务约束与设计理由 | [领域设计](design/02-domain.md)、[数据库与分片](design/03-data-sharding.md)、[决策记录](design/07-decisions-evidence.md) | 不变量、原始取舍、待确认问题 |
| 看已完成及剩余工作 | [交付状态](delivery/wms-v1/DELIVERY_STATUS.md)、[实施计划](delivery/wms-v1/DELIVERY_PLAN.md) | 当前唯一状态与 50 项 AC 计划 |
| 恢复开发任务 | [CODEX_PROGRESS](../CODEX_PROGRESS.md) | 当前任务、工作树、未发布改动和下一步 |

## 运行协议专题

| 主题 | 当前实现文档 |
| --- | --- |
| 普通入出库消息与恢复 | [消息运行链路](implementation/MESSAGING_RUNTIME.md)、[出库消息](implementation/OUTBOUND_MESSAGING.md)、[多 Cell 路由](implementation/INVENTORY_CELL_MESSAGING.md) |
| 跨仓履约 | [原生 RM](implementation/RUNTIME_TCC_RM.md)、[TC 证据恢复](implementation/FULFILLMENT_TC_RECOVERY.md)、[自动分配执行](implementation/FULFILLMENT_EXECUTION.md) |
| 确认与执行授权 | [库存确认](implementation/FULFILLMENT_CONFIRMATION_MESSAGING.md)、[出库授权](implementation/FULFILLMENT_AUTHORIZATION_MESSAGING.md) |
| 序列号入库 | [登记服务](implementation/SERIAL_REGISTRY_RUNTIME.md)、[收货批次](implementation/SERIAL_RECEIPT_BATCH.md)、[身份质检](implementation/SERIAL_RECEIPT_QUALITY.md)、[分次上架](implementation/SERIAL_PUTAWAY.md)、[源释放](implementation/SERIAL_SOURCE_RELEASE.md) |
| 序列号盘点 | [完整身份观察](implementation/COUNT_SERIAL_OBSERVATION.md)、[逐身份恢复](implementation/COUNT_SERIAL_RECOVERY.md) |
| 时间与数据移动 | [数据库时间](implementation/DATABASE_TIME.md)、[仓迁移限制](implementation/WAREHOUSE_MIGRATION_LIMITS.md) |

## 文档权威与维护

- 配置值以 POM、`package-lock.json`、Compose 和 `.env.example` 为准；表结构以所属模块的追加迁移为准，公开接口以 OpenAPI 与权限映射为准。
- `docs/design/` 保存设计目标与取舍；标记为目标或候选的内容不能视为已运行。专题文档保存实现与阶段证据；其中历史日志不覆盖最新交付状态。
- `DELIVERY_STATUS.md` 维护当前能力与阻塞，`DELIVERY_PLAN.md` 保留批准的验收范围，根进度文件只负责恢复任务。历史报告保留原测量日期，不用旧阶段结论推断当前状态。
- 改架构时同步总体架构；改依赖时同步版本与安全证据；改数据库/API 时同步数据与接口索引及相关专题；改环境时同步连接清单和运行手册；完成切片时更新交付状态与恢复入口。
- 公开文档只记录账号角色和凭据引用，不记录真实密码、令牌或带凭据连接串。现场登录、部署版本和生产目标未核验时明确标记，不由模板推断。

- [已提交分配取消补偿](implementation/COMMITTED_CANCELLATION.md)：COMP实施状态、原CANCEL、未知实物及兼容边界。
