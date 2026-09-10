# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S3入库与序列号（S3-01 定向 IT 已通过，待完整 verify 后快进 main）。
- 用户 `/goal` 要求按唯一计划做到整个项目完成；50 项 AC 与 S9 仍未完成，目标保持完整。未开始`wms-console/`。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s3-01（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；`/goal` 连续做到整个项目；持续 Git 发布授权。
- 本轮允许：S3-01 入库单/质检/上架双累计、库存 quality_qualification、测试文档、快进 remote main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、开始 `wms-console/`、把定向 IT 当作 AC-07 黑盒通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S2-07 main `51ef403` 需另核远程 CI |
| EG-02 TC组合/唯一TM | running | 正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S3-01 待完整 verify 后快进；无生产部署 |

## 本轮已实现（S3-01）

- 入库 `V003__inbound.sql`（V001 已被协议占用）：order/line/质检/任务。
- 行上 received/putaway 的 physical 与 posted 分列；超收拒绝；T3 仅新 inbox 加 posted。
- 库存 `V009` `quality_qualification`：按 inspection 版本接收，旧版本不覆盖。

## 未完成

- S3-02…S9。50 项 AC。`wms-console/`。OQ-03。

无生产部署。
