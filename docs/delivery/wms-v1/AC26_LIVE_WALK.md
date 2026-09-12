# AC-26 现场走查（2026-09-12）

Casdoor `wms-ops` + 隔离栈 `wms-local`（控制台 `127.0.0.1:18180`）。库存进程只服务 Cell A，列表可见仓只有 WH-A。未伪造 TC 确认。未把 202 写成业务成功。本记录不含口令或 access_token。

## 结论

用户路径已用活身份走完：**收货 → 质检 → 上架 → 准备跨仓分配 → 拣 → 部分发运 → 未拣取消回库**。跨仓 attempt 停在 `PLANNED`，`tcObservedStatus` 为空，**不是 ALLOCATED**。出库单头上的 `ALLOCATED` 是仓内出库单据状态，不是 TCC 整单成功。

**AC-26 仍 open，不能写成 UI accepted / 50 AC 通过。**

## API（控制台反向代理）

戳记 `AC26-20260912-164238`。

| 步骤 | HTTP | 要点 |
| --- | --- | --- |
| 收货 | 202 | `physicalStatus=RECEIVED` `stockSyncStatus=PENDING` |
| 同幂等键再收 | 400 | `OVER_RECEIVE`：无设备观察的收货路径会先加实物，同键不是恢复 |
| 质检 | 200 | `ACCEPTED` |
| 同质检再提交 | 400 | `DUPLICATE_INSPECTION` |
| 上架 WH-A-STO | 202 | `PUTAWAY` + `PENDING` |
| 履约 attempt WH-A=4 / WH-B=2 | 201 | `state=PLANNED`，未 ALLOCATED |
| 本仓出库（client 授权） | 201 | 仓内执行，不是 TCC confirm |
| 规划拣 4 / 拣 4 | 201 / 202 | 拣 202 + PENDING |
| 包装 4 / 发 2 | 201 / 202 | 部分发运 |
| 取消未拣 | 202 | `CANCEL_REQUESTED`，回库任务 RESTOCK |
| 重复外部单号 | 409 | `DUPLICATE_DOCUMENT` |
| `wms-wh-a` 读 WH-B | 403 | `WAREHOUSE_FORBIDDEN` |
| `wms-denied` 读 WH-A | 403 | 令牌 `warehouses` 为空 |

种子：SKU 5 条；WH-A 库位 `WH-A-RCV` `WH-A-STO` `WH-A-STG` `WH-A-SHP`。

## 浏览器（Casdoor PKCE）

登录后打开 API 单据：入库 `RECEIVING` 已收/已上架均为 10；履约页黄条「跨仓预占等待全局完成」且 `attemptState=PLANNED`；出库 `PACKING`，拣 4 / 发 2 / 取消 2，另有 RESTOCK 任务。

控制台再走一圈写命令（戳记 `UI2-1789202854081`）：

| 命令 | HTTP | 页面横幅 |
| --- | --- | --- |
| 创建入库 / 收货 | 201 / 202 | 「货已执行，库存待同步」RECEIVED PENDING |
| 质检 / 上架 | 200 / 202 | PUTAWAY PENDING |
| 履约 + 单仓准备分配 | 201 / 201 | attempt `PLANNED`，未写成 ALLOCATED 成功 |
| 出库 + 规划拣 | 201 / 201 | 仓内单头可为 ALLOCATED |
| 拣 / 部分发 / 取消未拣 | 202 / 202 / 202 | PICKED / SHIPPED / CANCEL_REQUESTED，均 PENDING |
| 重复外部单号 | 409 | 「版本冲突」且不换幂等键 |

无头 Chrome 截图中文会缺字，证据以 DOM 文案和 HTTP 状态为准。

## 仍缺

- 真实 TC Committed + 全仓 CONFIRMED 才能 mark ALLOCATED；控制台没有、也不会做伪造入口。
- 库存 Cell A 单进程，不是两物理库存库联调。
- 无设备观察的收货同键重放会 `OVER_RECEIVE`，不是命令恢复。
- 入库行 `id` 全局唯一；复用 `LINE-1` 原先 500，已改为 409 `DUPLICATE_DOCUMENT`。页面默认行号改为空，回退用本次命令键。
- 未做断网重连、对账审批修复、S8-05 设备、S9-01 签署峰值。未发明 OQ-03。
