# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S5-02（WCS 命令/查询/回执端口与 simulator）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s5-02。S5-01 已在 remote main `017171c`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：`wms-integration` 模块、WCS 三端口、明确标识的 simulator、测试与文档。
- 测试目标：localhost 进程内 simulator。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、真实设备、inventory permit/STARTED（S5-03）、simulator 当生产适配器。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S5-01 已推 main；S5-02 实现中，等 S5-01 main CI 后再推本片 |
| EG-02 TC配置/唯一TM | running | integration 无 Seata |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5-06 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | 等 S5-01 main CI 结束后发布 S5-02 |

## 本轮已实现（S5-02）

- `WcsCommandPort` / `WcsQueryPort` / `WcsReceiptPort`。
- `SimulatorWcsAdapter.IMPLEMENTATION=SIMULATOR`：同命令重放、异内容冲突、未知回执拒绝。
- 不写库存，不派发物理设备。

## 未完成

- 发布 S5-02。S5-03…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
