# Codex Progress

## 任务目标

按已批准计划做到 S9 与 50 项 AC。未发明 OQ-03。

## 已完成

- WMS S0–S7 及 S8-01/S8-04/S9-02/S9-03/S9-04/S9-06 在更早的 `origin/main`。
- 控制台登录与 Docker 编排已在远程 main `1ca61a8`。
- 前端架构重设计 F0–F4（2026-09-12）：单应用、仓写入 `/w/:warehouseId`、PDA `/pda/:warehouseId/receive`，无页面 Mock 表。
- 2026-09-12 现场 Casdoor + 四服务只读联调与 Ant Design 作业台。
- F5（2026-09-12，`feat/console-command-wiring`）：作业详情接到已落地写命令——入库收货/质检/上架，出库规划拣/拣/包/部分发/未拣取消，调拨发出/授权/接收/损耗，盘点排空冻结/点数/复盘/审批/调整，任务回收/领取，对账 APPROVE/REJECT。补了 attempt、pick-tasks、transfer 写 HTTP、count 写 HTTP、job retries、对账仓路径别名。

## 未完成

- S8-05 真实设备。S9-01 签署容量。OQ-03。AC-26 全链路现场走查（跨仓 ALLOCATED 仍要真实 TC）。50 AC 全量通过。

## 下一步

发布 `feat/console-command-wiring`。S8-05 / S9-01 / AC-42 保持 blocked。不把命令接入写成 50 AC 或 AC-26 accepted。
