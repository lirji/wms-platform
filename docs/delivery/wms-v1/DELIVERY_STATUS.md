# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S2库存事务内核（S2-07 本轮定向 IT 已通过，待完整 verify 后快进 main）。
- 用户 `/goal` 要求按唯一计划做到整个项目完成；50 项 AC 与 S9 仍未完成，目标保持完整。未开始`wms-console/`。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s2-07（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；`/goal` 连续做到整个项目；持续 Git 发布授权。
- 本轮允许：S2-07 effect 锁入口、命令尝试/安全关闭、posting 效果唯一、来源同事实复用、测试文档、快进 remote main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、把定向 IT 当作 50 项 AC 黑盒通过、开始 `wms-console/`。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S2-05 main `5179777` 发布后需另核远程 CI；S0/SBOM 未关 |
| EG-02 TC组合/唯一TM | running | 正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S2-07 待完整 verify 后快进；无生产部署 |

## 本轮已实现（S2-07）

- 库存 `V008`：`stock_posting` 唯一键改为效果级，不再带 action。
- 入出库 `V002`：`source_command` 补 previous/safe_close 字段。
- T1/T2 入口先锁 effect；同事实换客户端键复用原命令；安全关闭后才发下一 attempt。
- 补偿是独立 CASE_PART 效果，同 case 只一 posting。

## 未完成

- S3…S9。50 项 AC 正式黑盒。`wms-console/`。OQ-03。

无生产部署。
