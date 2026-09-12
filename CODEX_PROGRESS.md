# Codex Progress

## 任务目标

按已批准计划做到 S9 与 50 项 AC。未发明 OQ-03。

## 已完成

- WMS S0–S7 及 S8-01/S8-04/S9-02/S9-03/S9-04/S9-06 在更早的 `origin/main`。
- 控制台登录与 Docker 编排已在远程 main `1ca61a8`。
- 前端架构重设计 F0–F4（2026-09-12）：单应用、仓写入 `/w/:warehouseId`、PDA `/pda/:warehouseId/receive`，无页面 Mock 表。
- 2026-09-12 现场 Casdoor + 四服务只读联调与 Ant Design 作业台。
- F5（2026-09-12，`feat/console-command-wiring`）：作业详情接到已落地写命令。
- 2026-09-12 AC-26 现场：Casdoor `wms-ops` 走完收货→质检→上架→准备跨仓→拣→部分发→未拣回库；attempt 为 PLANNED 不是 ALLOCATED。见 `docs/delivery/wms-v1/AC26_LIVE_WALK.md`。
- F6（2026-09-12，`feat/console-ops-density`）：建单/作业命令进抽屉；首页 KPI + 入库/出库/任务活队列；401=会话过期，403 才展示仓与 scope；顶栏 Popover 显示令牌权限。Casdoor 已为 `wms-ops` / `wms-wh-a` / `wms-wh-b` 写入 42 个 OpenAPI 作业 scope；`wms-denied` 仍无作业权限。

## 未完成

- S8-05 真实设备。S9-01 签署容量。OQ-03。跨仓 ALLOCATED 真实 TC。50 AC 全量通过。AC-26 仍 open（不是 UI accepted）。

## 下一步

已登录用户必须重新登录才能拿到带作业 scope 的新 JWT。S8-05 / S9-01 / AC-42 保持 blocked。本轮不推 main（避免再次触发 main verify）。不把 F6 写成 50 AC 或 AC-26 accepted。
