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
- F7（2026-09-12，`feat/console-ops-density` `4f63771`）：登录一列；Ant token 单轨；列宽/复制 id；URL `q`/`cutoffId`/`cursor`；无 scope 不画命令；409 不 dump JSON；单据抽屉分页签；路由懒加载。用户已要求合入远程 main。
- F8（2026-09-12，`feat/contract-http-gaps`）：有领域的公开契约缺口已接 HTTP 与页面。主数据写/按 id 读；仓任务按 `taskType` 分 inbound PUTAWAY / outbound PICK|RESTOCK；领取；库存流水与 operationId；效果列表；catalog 建档；jobs 仓任务；stock 流水；recon 次要导出快照。未编造 moves/holds/ALLOCATED。TP99 unverified。

## 未完成

- S8-05 真实设备。S9-01 签署容量。OQ-03。跨仓 ALLOCATED 真实 TC。50 AC 全量通过。AC-26 仍 open（不是 UI accepted）。
- 无领域的公开路径仍未实现：`POST /moves`、`stock-holds`、履约取消、出库 execution-authorizations、独立 adjustment 资源。

## 下一步

S8-05 / S9-01 / AC-42 保持 blocked。主线 verify 若仍在跑则只推任务分支，不推会取消进行中 verify 的 main。已登录用户仍须重新登录拿 42 项 scope。硬刷新 `127.0.0.1:18180`。不把 F8 写成 50 AC 或 AC-26 accepted。
