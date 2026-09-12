# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S9-05 证据汇总。route-gate + AC-24 HTTP IT 已在远程 main `4dee112`。
- 用户工作区 `main` 未切换（含未跟踪 `docs/design/11-edge-resilience.md`）。
- 任务分支：`feat/wms-s9-02-route-gate`。
- 未发明 OQ-03。S8-05 无授权设备。S9-01 无签署容量输入。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户要求做到 S9 / 50 AC 且不必逐步确认。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、把 simulator 当真实设备、把合成峰值当签署容量。
- 不推送仍在跑 verify 的 `main`（`34673286277`）。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | `4dee112` verify `34673286277` in_progress；`2d270ba` `34671971183` 因 tc-it 失败（已修） |
| EG-05 外部与非功能 | running | S9-01 / S8-05 / AC-42 仍 blocked |
| Git发布 | pass（本切片） | 远程 main 祖先含 `8351fd0` 与 `4dee112` |
| S9-05 50 AC | fail | 见 [AC_EVIDENCE.md](AC_EVIDENCE.md) / [DELIVERY_REPORT.md](DELIVERY_REPORT.md) |

## 本轮

- `requireWritable` 不再把分片未声明/缺表伪装成停写；`TccFenceShardingIT` 纳入 `warehouse_route`。
- AC-24 HTTP：ISO cutoff、测试 JWT POST/GET、跨仓 403、数量非金额。
- 本地：`WarehouseRouteGateTest` 1/0；`TccFenceShardingIT` 1/0；`WarehouseMigrationIT` 2/0；`SnapshotHttpIT` 1/0；recon `WmsExportContractTest` 1/0。

## 未完成

- `34673286277` 远程结果。S8-05。S9-01 签署峰值。50 AC 全量证据。OQ-03。UI accepted。AC-42。

无生产部署。
