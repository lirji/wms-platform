# Codex Progress

## 任务目标

完成已批准 WMS v1 到 S9 与 50 项 AC。当前连续交付：控制台把已公开契约接到作业台（出库序列拣发、序列调拨、对账窗口、仓级 action-effects）。不扩项、不发明内部 HTTP、不操作共享或生产。后端有限计划仍是 docs/delivery/wms-v1/DELIVERY_PLAN.md 的 OUT → WATERMARK → TRANSFER → TC → COMP → FINAL。

## 已完成

- OUT `365a1eb`、WATERMARK `051a7eb`、TRANSFER `7659d34`、TC `a3b4c65`、COMP `f043117` 均已在远程 main。COMP CI [34734659069](https://github.com/lirji/wms-platform/actions/runs/34734659069) 成功。
- 控制台公开契约接线（本工作树 `feat/console-public-serial-ops`，基线 `f043117`）：
  - 出库：`GET serial-stock`、`GET shippable-serials`，拣/发提交 `serialExecution`（`serialId` + 当前 `ownerEpoch`）。
  - 调拨：`POST serial-issues`、`POST serial-transfer-receipts`、`GET serial-commands/{commandId}`。
  - 对账：窗口 GET/POST/重试/取消，快照分段 GET。
  - 仓级 `action-effects` 列表/详情/`execution-attempts`。
- 取消补偿无公开查询入口，未造页面。内部 `serial-registry` / `recon.evidence` 未接线。

## 未完成

- 本地 console：28 文件 / 52 测、typecheck 通过。待提交并合入远程 main。
- COMP FINAL 发布回执尚未写。
- PDA 拣/发、作业 202 跨服务轮询、库位门禁写、已提交取消补偿 UI。
- OQ-03 / AC-26 现场 / S8-05 授权设备 / S9-01 签署容量 / 50 AC 全量证据。

## 下一步

1. 提交本任务文件，推 `feat/console-public-serial-ops` 并快进远程 main。
2. 写 COMP FINAL 回执（CI 已成功）。
3. 下一批前端：PDA 拣/发、跨服务 202 轮询、取消补偿（等公开 GET）。
4. 不把模拟器当设备，不把本机开关当签署容量。

## 当前工作树

- 本任务：`/Users/liruijun/personal/LLM/wms-platform/.local/console-public-serial-ops` @ `feat/console-public-serial-ops`
- 根用户工作树有无关脏文件，未切换、未吸收
- 后端 COMP 树：`.local/backend-remediation-integrate`，勿改

## 恢复 Prompt

读取本文件与 `docs/delivery/wms-v1/DELIVERY_STATUS.md`。从前端公开契约接线的验证/发布继续；COMP FINAL 等 main CI。不要重开架构，不要发明 OQ-03。
