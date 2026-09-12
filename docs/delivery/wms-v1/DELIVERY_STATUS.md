# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S8/S9 已合入远程 main；正在修 `requireWritable` 把分片未声明 `warehouse_route` 误判为停写的 CI 回归。
- 用户工作区 `main` 未切换（含未跟踪 `docs/design/11-edge-resilience.md`）。
- 热修复分支：`feat/wms-s9-02-route-gate`（从 `origin/main` `2d270ba` 拉出）。
- AC-24 HTTP IT 仍在 `feat/wms-s9-05-ac24` @ `30747e9`，待 main verify 结束后再合。
- 未发明 OQ-03。S8-05 无授权设备。S9-01 无签署容量输入。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户要求做到 S9 / 50 AC 且不必逐步确认。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、把 simulator 当真实设备、把合成峰值当签署容量。
- 不推送仍在跑 verify 的 `main` / `feat/wms-s9-05-ac24`（`cancel-in-progress: true`）。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | `2d270ba` main verify `34671971183` 仍 in_progress；`feat/wms-s9-02` tc-it 已因本 bug 失败 |
| EG-05 外部与非功能 | running | S9-01 agreed-peak 仍 blocked；S8-05 仍 blocked |
| Git发布 | pending | 热修复未进远程 main |

## 本轮（route-gate）

- `requireWritable`：已注册 Mapper 则读路由行；无行放行；表缺失/SS 无表规则不伪装成 `STALE_ROUTE`；其它读失败带根因。
- `TccFenceShardingIT` 分片表名单加入 `warehouse_route` 并注册 Mapper。
- 本地：`WarehouseRouteGateTest` 1/0；`TccFenceShardingIT` 1/0（16.90s）；`WarehouseMigrationIT` 2/0。

## 未完成

- 等 `34671971183` 结束后把本修复合入 main。S8-05。S9-01 签署峰值。S9-05 50 AC 全量证据。OQ-03。UI accepted。

无生产部署。
