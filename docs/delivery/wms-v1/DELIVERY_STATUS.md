# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S4-04（真实 file-mode TC 恢复 + 双仓 Fence 路由）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s4-04。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：`SeataTccRecoveryIT`、`TccFenceShardingIT`、TCC 操作键哈希、inventory `tc-it` profile、测试与文档、快进 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11 + `apache/seata-server:2.6.0`。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、把本切片当 AC-12 履约屏障或 XXL 集群通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | 本地 default verify 与定向 TC IT 已通过；发布后核对远程 verify |
| EG-02 TC组合/唯一TM | running | 同 JVM TM/RM + file-mode TC；不是 fulfillment TM 生产组合 |
| EG-03 业务决定 | running | OQ-03 仍待；本切片未发明单位/效期默认 |
| EG-04 完整闭环 | pending | S5 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S4-04 待快进 |

## 本轮已实现（S4-04）

- `SeataTccRecoveryIT`：真实 TC Confirm/Cancel/空回滚；8s 超时由 TC 驱动 Cancel；二阶段把 `TCCResource` 换成新实例后 Confirm 重试；错误 XID 得 `OWNER_MISMATCH`。
- Confirm 不重抢库存；`execution_authorization_id` 保持空；无 ALLOCATED/出库许可表。不是 fulfillment 屏障。
- `TccFenceShardingIT`：`AbstractRoutingDataSource` 按持久化 `warehouseId` 选物理库，再经单 Cell ShardingSphere；Fence 与预占同事务；无上下文不得取连接。
- TCC 阶段 `operation_id` 改为 SHA-256，避免真实 XID 超出 `outbox_event.operation_id` VARCHAR(64)。
- 默认 failsafe 排除上述两项；`-Ptc-it` 的 `s4-tc-recovery` 才启动 Seata。

## 未完成

- 本轮快进 remote main。S4-05…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
