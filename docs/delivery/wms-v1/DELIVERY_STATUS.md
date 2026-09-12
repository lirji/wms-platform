# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S5-06（同批四库闭环：收货入账→两仓预占→授权→拣货发运→来源回执，含一次丢失响应）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s5-06。S5-05 已在 remote main `aa2bba2`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：`ClosedLoopBlackBoxIT` 同 JVM 四库；build-helper 编译 inbound/fulfillment/outbound 测试源；文档。
- 测试目标：localhost / Testcontainers MySQL 8.4.11；同 JVM 四库，不是 HTTP/真实 TC。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、把本切片当 AC-10/12/13/14/15/25 生产通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S5-05 已推 main；等该 verify 结束后再推 S5-06 |
| EG-02 TC配置/唯一TM | running | 本片用 `reserveTried`/`confirmTried` 模拟，不是真实 TC |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | local-pass | `ClosedLoopBlackBoxIT` 同 JVM 四库；待 Git 发布；不是生产 HTTP/TC |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | 等 S5-05 main CI 结束后发布 S5-06 |

## 本轮已实现（S5-06）

- 同批：两仓各收 5、预占 3、拣发 3；剩余 on_hand=2 reserved=0；两张出库单 SHIPPED。
- 履约 `markAllocated` 后读 Outbox `operation_id` 作为出库授权。
- WH-A 拣货过账提交后再重放：状态 APPLIED，PICK posting 仍 1；T3 inbox 重放不二次加 posted。
- 不是 HTTP/真实 TC/WCS/设备。未发明 OQ-03（NO_LOT、EA）。

## 未完成

- 发布 S5-06（先等 S5-05 main CI）。S6…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
