# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S6-02（序列号 TRANSFER_PREPARED/SEALED/IN_TRANSIT/RECEIVING/ACTIVE 与 epoch）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：`feat/wms-s6-02`。S5-06 已在 remote main `97e35fa`。S6-01 `01eeeaa`、S6-01a `29f8181` 已推任务分支，等 S5-06 main CI 后再按序快进 main。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：登记 `V002__serial_transfer.sql`、库存 `V012__local_serial_transfer.sql`、转移协议/仓内 SEALED 与文档。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、把本切片当 AC-16/17 生产通过、源仓数量扣减与跨库存守恒（S6-04）。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | 等 S5-06 verify 结束后按 S6-01 → S6-01a → S6-02 推 main |
| EG-02 TC配置/唯一TM | running | 序列号转移不走 Seata |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | local-pass | S5-06 `ClosedLoopBlackBoxIT` 已在 main；不是生产 HTTP/TC |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S6-02 本地已通过；先等前序 main CI |

## 本轮已实现（S6-02）

- 登记：`TRANSFER_PREPARED → IN_TRANSIT → RECEIVING → ACTIVE`，`serial_transfer` UQ(serial,transfer)，owner_epoch 目的确认后 +1。
- 未见源仓释放事实不得 `startReceiving`。旧 epoch / 其他操作引用拒绝。目的同接收引用重放。
- 库存：源仓 `SEALED` 后旧 ACTIVE 观察与收货恢复不得改回 AUTHORIZED；目的登记未在途保持 HOLD；同操作重放不加量。

## 未完成

- 按序发布 S6-01、S6-01a、S6-02。S6-03…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
