# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S3入库与序列号（S3-04 定向 IT 已通过，待完整 verify 后快进 main）。
- 用户 `/goal` 要求按唯一计划做到整个项目完成；50 项 AC 与 S9 仍未完成，目标保持完整。未开始`wms-console/`。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s3-04（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；`/goal` 连续做到整个项目；持续 Git 发布授权。
- 本轮允许：S3-04 FEFO/实时效期、序列号重复、两仓并发登记、质检不通过、错误上架库位、测试文档、快进 remote main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、开始 `wms-console/`、把定向 IT 当作 AC-08/09/15 黑盒通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S3-03 main `d88f8a5` 远程 verify 已启动 |
| EG-02 TC组合/唯一TM | running | 正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S3-04 待完整 verify 后快进；无生产部署 |

## 本轮已实现（S3-04）

- `FefoCandidateService`：只接受 FEFO；过期/非存储/非 OPEN 不入候选。
- 预占实时效期：`expires_at <= now` 拒绝 `LOT_EXPIRED`。
- 上架：必须质检且非 REJECTED；目标类型必须 STORAGE。
- 同仓重复序列号拒绝；两仓并发只一仓 AUTHORIZED，失败仓保留 HOLD+EXCEPTION。

## 未完成

- S3-04 快进 remote main。S3-05…S9。50 项 AC。`wms-console/`。OQ-03。

无生产部署。
