# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S6-04 本地已通过。等待 timeout-fix main CI 后再按序 merge S6-01…S6-04。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：`feat/wms-s6-04`。`origin/main` 仍是 `8d4ca21`。S6-01 与 main 分叉，必须 merge 不能 FF。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：源仓封闭扣量、`TransferStockService`、S6-04 三份真实库 IT 与文档。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、把本切片当 AC-16/17/18/19 生产通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | timeout-fix verify `34666539607` 进行中；成功后再 merge S6-01→S6-04 |
| EG-02 TC配置/唯一TM | running | 调拨/盘点不走 Seata |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | local-pass | S5-06 已在 main；S6-04 两库守恒不是生产 HTTP |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S6-04 本地已通过；先等前序 main CI |

## 本轮已实现（S6-04）

- 源仓 `sealSource` 扣 1 个 on_hand；同释放引用重放不二次扣减；扣后不足覆盖预占则拒绝。
- 非序列号 `TransferStockService.issue` 与 fulfillment 分库分事务。
- `CountFreezeRaceIT`：QUIESCING 后并发冻结胜、新预占 `STOCK_FROZEN`。
- `SerialTransferRecoveryIT`：未见登记释放时目的 HOLD，恢复后源仓数量仍 0。
- `TransferConservationIT`：库存库 + 履约库，源 1 + 目的 3 + 在途 0 + 损耗 1 = 期初 5。

## 未完成

- 按序 merge 发布 S6-01…S6-04。S7…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
