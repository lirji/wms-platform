# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S6-01（调拨总单、仓级子单、在途行、发出/接收按操作键去重）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s6-01。S5-06 已在 remote main `97e35fa`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：fulfillment `V004__transfer.sql`、`TransferService`/`TransferIT` 与文档。
- 测试目标：localhost / Testcontainers MySQL 8.4.11；履约本库。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、额度 token（S6-01a）、把本切片当 AC-16 生产通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S5-06 已推 main；等该 verify 结束后再推 S6-01 |
| EG-02 TC配置/唯一TM | running | 调拨不走 Seata |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | local-pass | S5-06 `ClosedLoopBlackBoxIT` 已在 main；不是生产 HTTP/TC |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | 等 S5-06 main CI 结束后发布 S6-01 |

## 本轮已实现（S6-01）

- fulfillment：`transfer_order`/`transfer_leg`/`transfer_line`/`transfer_fact`。
- `issue`/`receive` 按仓+动作+operationId 去重；超计划 `OVER_ISSUE`；超在途 `OVER_RECEIVE`。
- CHECK：`received+loss+quota<=issued` 且 `issued<=planned`。额度 token 留给 S6-01a。

## 未完成

- 发布 S6-01（先等 S5-06 main CI）。S6-01a…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
