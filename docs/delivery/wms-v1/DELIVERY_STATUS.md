# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S5-01（出库单/任务/包裹与来源命令，待发布）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s5-01。S4-07 已在 remote main `4d8ba86`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：outbound 单据/任务/包裹、PICK/CANCEL 来源命令、测试与文档。V003 迁移（V001 已被来源协议占用）。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、WCS simulator（S5-02）、outbound 写库存表、Seata。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S4-07 main verify 进行中；S5-01 本地通过后等该 run 结束再推 main |
| EG-02 TC配置/唯一TM | running | outbound 仍无 Seata |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5-06 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | 等 S4-07 main CI 结束后发布 S5-01 |

## 本轮已实现（S5-01）

- `V003__outbound.sql`：出库单/行、拣货任务、包裹。
- 按 allocation/attempt 幂等建单；部分拣货 physical/posted；包装；发运前取消未拣量。
- 不写 `stock_balance`/`reservation`。fulfillment Outbox 消费留后续切片。

## 本地验证

- `OutboundPickIT` + `OutboundProtocolIT`：2 项 0 失败。
- `ThreeServiceProtocolIT`：V003 后仍通过。
- `python3 scripts/check-docs.py`：PASS documents=21。

## 未完成

- 发布 S5-01。S5-02…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
