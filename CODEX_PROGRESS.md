# Codex Progress

## 任务目标

完成已批准 WMS v1 到 S9 与 50 项 AC。有限后端范围 OUT → WATERMARK → TRANSFER → TC → COMP → FINAL 已发布。控制台按已公开契约接线序列拣发/调拨、对账窗口与仓级 action-effects。

## 已完成

- OUT `365a1eb`、WATERMARK `051a7eb`、TRANSFER `7659d34`、TC `a3b4c65`、COMP `f043117` 均在远程 main；COMP CI [34734659069](https://github.com/lirji/wms-platform/actions/runs/34734659069) 成功。
- 控制台 `a619d3f` 已快进远程 main：`serial-stock` / `shippable-serials` / `serialExecution` 拣发、序列调拨签发签收与命令查询、对账窗口与快照分段、仓级 action-effects。本地 28 文件 / 52 测、typecheck 通过。
- 有限范围回执：[docs/delivery/wms-v1/FINAL_RECEIPT.md](docs/delivery/wms-v1/FINAL_RECEIPT.md)。

## 未完成

- 控制台 main CI [34736085774](https://github.com/lirji/wms-platform/actions/runs/34736085774) 运行中；本回执文档尚未合入 main（等该 verify 结束再推，避免 cancel-in-progress）。
- PDA 拣/发、跨服务 202 轮询（公开 `GET /operations/{id}` 只有库存实现）、取消补偿查询（无公开 GET）、库位门禁写（无公开写）。
- OQ-03 / AC-26 现场 / S8-05 / S9-01 / 50 AC。

## 下一步

1. 等 `34736085774` 结束后，把 FINAL 回执快进远程 main。
2. 若要继续前端：PDA 拣/发（同一公开拣发契约）。
3. 不把模拟器当设备，不把本机开关当签署容量。

## 当前工作树

- `/Users/liruijun/personal/LLM/wms-platform/.local/console-public-serial-ops` @ `feat/console-public-serial-ops`（领先已发布 `a619d3f` 的回执文档）
- 根用户工作树有无关脏文件，未切换

## 恢复 Prompt

读取本文件。main 含 `a619d3f` 且其 verify 结束后再推回执。不要发明 OQ-03 或内部 HTTP。
