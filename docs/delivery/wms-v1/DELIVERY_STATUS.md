# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S3入库与序列号（S3-05 定向 IT 已通过，待完整 verify 后快进 main）。
- 用户 `/goal` 要求按唯一计划做到整个项目完成；50 项 AC 与 S9 仍未完成，目标保持完整。未开始`wms-console/`。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s3-05（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；`/goal` 连续做到整个项目；持续 Git 发布授权。
- 本轮允许：S3-05 收货 session/part/离线观察映射、分批额度、身份恢复、测试文档、快进 remote main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、开始 `wms-console/`、把定向 IT 当作 AC-47 黑盒通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S3-04 main `2256a53` 远程 verify 需另核 |
| EG-02 TC合同/唯一TM | running | 正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S3-05 待完整 verify 后快进；无生产部署 |

## 本轮已实现（S3-05）

- inbound `V004`：`inbound_receipt_part` 与 `device_observation_binding`。
- `receiveObserved`：同设备会话序号重放恢复原命令；新分批才加实物；父行额度超量拒绝。
- 缺设备/会话/分批身份 `AMBIGUOUS_OBSERVATION` 隔离；同序号异摘要 `OBSERVATION_CONFLICT`。
- 重置设备会话复用原 part，不创造第二身份。

## 未完成

- S3-05 快进 remote main。S4…S9。50 项 AC。`wms-console/`。OQ-03。

无生产部署。
