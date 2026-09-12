# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S7-03…S7-05 已发布 remote main `38a7f6a`；S8-01 本地已通过。
- main verify `34668676522` 覆盖 `38a7f6a`，进行中；`cancel-in-progress` 期间不另推 main。
- 用户工作区 `main` 未切换（仍落后，保留未跟踪 `docs/design/11-edge-resilience.md`）。
- 当前分支：`feat/wms-s8-01`。未发明 OQ-03。未创建 `wms-console/`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8-04 创建 `wms-console/`、未评审 recon-platform 源码前不改该仓。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | `34668676522` 覆盖已发布 S7；S8-01 等该次结束后再合 main |
| EG-02 TC配置/唯一TM | running | 巡检清理 XID |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | local-pass | S5-06 已在 main |
| EG-05 外部与非功能 | pending | S8-02/S8-03 依赖独立 recon 仓；S8-05 设备环境可能 blocked |
| Git发布 | running | S7-03…S7-05 已在 `38a7f6a`；S8-01 待任务分支 |

## 本轮已实现（S8-01）

- `WarehouseQuantityFact` v1：十进制 quantity + unit，无 currency/amountMinor。
- `reconciliation_snapshot` / `snapshot_part`（V019）；三方水位不齐拒绝发布。
- 同场景/窗口重拉同一 snapshotId 与 manifest。
- `POST/GET /api/wms/v1/reconciliation-snapshots`。
- `SnapshotExportIT` BUILD SUCCESS。

## 未完成

- 等 `34668676522` 后再合 S8-01。
- S8-02 recon-platform 独立评审与计划（不猜模块写入）。
- S8-03…S9。50 项 AC。OQ-03。S8-04 才做 `wms-console/`。

无生产部署。
