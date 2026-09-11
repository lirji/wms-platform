# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S4跨仓履约（S4-01 本地 verify 已通过，待快进 main）。
- 用户 `/goal` 要求按唯一计划做到整个项目完成；50 项 AC 与 S9 仍未完成，目标保持完整。未开始`wms-console/`。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s4-01（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；`/goal` 连续做到整个项目；持续 Git 发布授权。
- 本轮允许：S4-01 `wms-fulfillment` attempt/XID/participant 映射、TC 观察副本、缺证据拒绝 ALLOCATED、独立库账号、测试文档、快进 remote main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、开始 `wms-console/`、真实 Seata Try/Confirm、把映射 IT 当作 AC-10/12 黑盒通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S3-05 main `59f18df`；本轮提交后核对远程 verify |
| EG-02 TC组合/唯一TM | running | `wms-fulfillment` 模块已建；尚未做真实 TCC Try |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S4-01 待快进；无生产部署 |

## 本轮已实现（S4-01）

- 独立模块 `wms-fulfillment`：履约单、attempt、固定参与仓、启动审计。无自研 decision 权威字段。
- XID 绑定一次；同 XID 不能挂两个 attempt；并发 claimLaunch 只一个胜者。
- TC 状态仅为观察副本；缺证据或参与者未全部 CONFIRMED 拒绝 ALLOCATED。
- 隔离 compose 增加 `wms_fulfillment` 库与账号；已有数据卷不会重跑 init。
- smoke 增加 fulfillment 进程：health UP，业务路径拒绝。

## 未完成

- S4-01 快进 remote main。S4-02…S9。50 项 AC。`wms-console/`。OQ-03。

无生产部署。
