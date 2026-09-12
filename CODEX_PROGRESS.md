# Codex Progress

## 任务目标

按已批准 `docs/delivery/wms-v1/` 做到 S9 / 50 AC。本轮按前端缺口顺序落地：序列号入站+盘点观察、202 状态机、恢复入口、主数据补面。已持续授权逻辑提交、验证后推任务分支并合入远程 main。不发明 OQ-03，不把 simulator 当设备，不把合成峰值当签署容量。

## 已完成

- 2026-09-13 控制台 `feat/console-serial-jobs` 已合入远程 main `b6f44ac`：收货/PDA/质检/上架/盘点接到公开序列号观察字段；202 不换幂等键并有界轮询 `GET /operations/{id}`；单据头展示实物/库存同步；401 提供去登录；任务页接序列恢复与消息重排；履约页接 attempt 执行（不写 ALLOCATED）；主数据拆成商品/库位/批次三表并补单位写入与门禁只读查询。拣/发序列号清单未公开，页面未发明。
- 控制台 `npm test` 21 文件 / 41 用例通过；`typecheck` 与 production build 通过。不是 50 AC / AC-26 accepted。

## 未完成

- AC-26 现场黑盒（断网重连、对账修复、真实设备）仍 open。
- S8-05 无授权设备。S9-01 无签署容量。OQ-03。跨仓 ALLOCATED 仍要真实 TC。
- 公开拣/发/调拨序列号契约未入 OpenAPI，前端不得先画表单。
- 后端 R13/R14 剩余：序列 PICK/SHIP、公开序列调拨、可信水位、TC 资源/Fence 迁移、晚取消补偿。

## 下一步建议

1. 有 Casdoor + 隔离栈时做 AC-26 黑盒：序列号收货路径、202 不换键、401/429、对账审批。无现场栈则保持 open。
2. 等拣/发序列号公开契约后再做出发身份 UI。
3. S8-05 / S9-01 / 真实 TC ALLOCATED 仍 blocked，不发明证据。

## 恢复 Prompt

读取本文件与 `docs/delivery/wms-v1/DELIVERY_STATUS.md`。控制台切片已在远程 main `b6f44ac`。工作树 `/Users/liruijun/personal/LLM/wms-platform`。不要切到 `.local/backend-remediation-integrate`。未完成项从上表继续，不要重做已发布后端收货/质检/上架/盘点切片。
