# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S5-03（STARTED 派发身份、UNKNOWN 占用、逆向上限）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s5-03。S5-02 已在 remote main `c7e1075`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：inventory startPermit/markUnknown/reverse bound；outbound 共享动作身份与 worker fence；测试与文档。
- 测试目标：localhost / Testcontainers MySQL 8.4.11；outbound 授权口为内存桩。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、真实设备、S5-04 黑盒、applyPick HTTP。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S5-02 已推 main；S5-03 本地通过后等该 run 结束再推 |
| EG-02 TC配置/唯一TM | running | outbound/integration 无 Seata |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5-06 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | 等 S5-02 main CI 结束后发布 S5-03 |

## 本轮已实现（S5-03）

- inventory：`startPermit` / `markUnknown`；UNKNOWN 拒绝取消；补偿 CAS `reversed_qty`。
- outbound：`action_id`/`device_command_id` 换主沿用；旧 epoch `WORKER_FENCED`；UNKNOWN 拒新派发。
- 出库授权口是端口，IT 用内存桩；库存占用在 inventory IT 证明。

## 未完成

- 发布 S5-03。S5-04…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
