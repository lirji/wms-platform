# Codex Progress

## 任务目标

完成已批准 WMS v1 到 S9 与 50 项 AC。有限后端范围 OUT → WATERMARK → TRANSFER → TC → COMP → FINAL 已发布。控制台按已公开契约接线序列拣发/调拨、对账窗口、仓级 action-effects，以及 PDA 收/拣/发。

## 已完成

- OUT `365a1eb`、WATERMARK `051a7eb`、TRANSFER `7659d34`、TC `a3b4c65`、COMP `f043117` 均在远程 main；COMP CI [34734659069](https://github.com/lirji/wms-platform/actions/runs/34734659069) 成功。
- 控制台 `a619d3f` 已在远程 main：桌面序列拣发/调拨、对账窗口、仓级 action-effects。
- PDA 拣/发已在任务分支实现：`/pda/:warehouseId/pick|ship`，同一 `serialExecution`，扫码不默认 ownerEpoch。本地 31 文件 / 56 测、typecheck 通过。
- FINAL 回执 `c07e9b0` 已在任务分支。

## 未完成

- 控制台 main CI [34736085774](https://github.com/lirji/wms-platform/actions/runs/34736085774) 仍在跑。PDA 与 FINAL 回执尚未合入 main（避免 cancel-in-progress）。
- 跨服务 202 轮询（`GET /operations/{id}` 只有库存实现）、取消补偿查询（无公开 GET）、库位门禁写（无公开写）。
- OQ-03 / AC-26 现场 / S8-05 / S9-01 / 50 AC。

## 下一步

1. 等 `34736085774` 结束后，把 FINAL 回执与 PDA 拣发快进远程 main。
2. 不发明跨服务 operations 轮询或补偿 GET。
3. 不把模拟器当设备，不把本机开关当签署容量。

## 当前工作树

- `/Users/liruijun/personal/LLM/wms-platform/.local/console-public-serial-ops` @ `feat/console-public-serial-ops`
- 根用户工作树有无关脏文件，未切换

## 恢复 Prompt

读取本文件。`a619d3f` 的 verify 结束后再推 main。不要发明 OQ-03 或内部 HTTP。
