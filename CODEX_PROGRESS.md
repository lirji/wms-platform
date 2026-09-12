# Codex Progress

## 任务目标

按已批准计划做到 S9 与 50 项 AC。未发明 OQ-03。

## 已完成

- WMS S0–S7 及 S8-01/S8-04/S9-02/S9-03/S9-04/S9-06 在更早的 `origin/main`。
- 控制台登录与 Docker 编排已在远程 main `1ca61a8`。
- 前端架构重设计 F0–F4（2026-09-12，`feat/console-frontend-ia`）：单应用、仓写入 `/w/:warehouseId`、PDA `/pda/:warehouseId/receive`、作业模块绑活 API，无页面 Mock 表。
- 2026-09-12 现场 Casdoor + 四服务只读联调：无令牌 401，带 JWT 经 console 反代 200；种子后 `WH-A` / 5 SKU / 4 库位可读。浏览器走通 `/login` → Casdoor PKCE → `/w/WH-A` 及各作业/PDA/旧路径重定向。不是 50 AC accepted。
- 2026-09-12 控制台改为侧栏作业台，并接入 Ant Design 5（自定义青绿主题）。KPI 行数来自接口，不写死库存。

## 未完成

- S8-05 真实设备。S9-01 签署容量。OQ-03。AC-26 全链路写作业。50 AC 全量通过。

## 下一步

发布 `feat/console-enterprise-ui`。S8-05 / S9-01 / AC-42 / AC-26 写作业保持 blocked。不把前端改版写成 50 AC 通过。
