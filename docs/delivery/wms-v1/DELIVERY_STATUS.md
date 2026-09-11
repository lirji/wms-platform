# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S3入库与序列号（S3-02 模块定向 IT 已通过，待完整 verify 后快进 main）。
- 用户 `/goal` 要求按唯一计划做到整个项目完成；50 项 AC 与 S9 仍未完成，目标保持完整。未开始`wms-console/`。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s3-02（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；`/goal` 连续做到整个项目；持续 Git 发布授权。
- 本轮允许：S3-02 `wms-serial-registry` 身份/唯一认领、测试文档、快进 remote main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、开始 `wms-console/`、把定向 IT 当作 AC-08 黑盒通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S3-01 main `35cc0ac` 需另核远程 CI |
| EG-02 TC组合/唯一TM | running | 正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S3-02 待完整 verify 后快进；无生产部署 |

## 本轮已实现（S3-02）

- 新模块 `wms-serial-registry`，迁移 `db/migration/registry/V001__serial_registry.sql`。
- 唯一键 enterprise+SKU+normalized_serial；仓只是归属。
- `route_bucket` 由身份哈希得到，64 桶。两仓并发认领只一胜。

## 未完成

- S3-03…S9。50 项 AC。`wms-console/`。OQ-03。smoke 仍只拉起三服务。

无生产部署。
