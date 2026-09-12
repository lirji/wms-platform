# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S9-05 证据汇总。route-gate + AC-24 HTTP IT 已在远程 main `4dee112`。
- 任务分支：`feat/contract-http-gaps`（合入远程 main `16b94f0` 后发布）。
- 未发明 OQ-03。S8-05 无授权设备。S9-01 无签署容量输入。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户要求做到 S9 / 50 AC 且不必逐步确认。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、把 simulator 当真实设备、把合成峰值当签署容量。
- 本轮用户要求先停止进行中的 main verify，再把 `feat/contract-http-gaps` 推送到远程 main。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | `4dee112` 任务分支 verify `34672595394` success（含 tc-it）；main `34673286277` 被后续 push 取消；`a5ea7ad` `34674304734` 在 45m timeout 处取消（tc-it 已绿） |
| EG-05 外部与非功能 | running | S9-01 / S8-05 / AC-42 仍 blocked |
| Git发布 | pass（本切片） | 用户要求取消 main verify `34689089236` 后推送 `feat/contract-http-gaps` |
| S9-05 50 AC | fail | 见 [AC_EVIDENCE.md](AC_EVIDENCE.md) / [DELIVERY_REPORT.md](DELIVERY_REPORT.md) |

## 本轮

- 2026-09-12：控制台 README 写明本机 Vite `4181` 与门户 Docker `18180` 分工。不是 50 AC。
- 2026-09-12：`verify.yml` 在提交说明或 PR 标题含 `[skip ci]` / `[ci skip]` / `[no ci]` 时跳过 java 与 console。不是 50 AC。
- 2026-09-12：扩展 `seed-local.sh`，向隔离库存库写开账余额/投影/草稿盘点，向应用库写入库/出库/履约/调拨演示单。已发布远程 main `38ef86e`。未发明 OQ-03，未写 TCC ALLOCATED。不是 50 AC。
- `requireWritable` 不再把分片未声明/缺表伪装成停写；`TccFenceShardingIT` 纳入 `warehouse_route`。
- AC-24 HTTP：ISO cutoff、测试 JWT POST/GET、跨仓 403、数量非金额。
- 本地：`WarehouseRouteGateTest` 1/0；`TccFenceShardingIT` 1/0；`WarehouseMigrationIT` 2/0；`SnapshotHttpIT` 1/0；recon `WmsExportContractTest` 1/0。
- 2026-09-12：根目录 `compose.yaml` 在容器内编译启动五服务与 console，已在远程 main `87a233b`。
- 2026-09-12：控制台登录/`returnTo`/OIDC 已在远程 main。
- 2026-09-12：按 `docs/design/console-frontend/` 重做信息架构并落地作业模块。
- 2026-09-12：Casdoor + inbound/outbound/inventory/fulfillment 只读联调与浏览器新路由已走通；任务页改绑已实现的 `GET /jobs?warehouseId=`。
- 2026-09-12：控制台改为 Ant Design 作业台（侧栏、KPI、密表）。
- 2026-09-12 F5：作业详情接到已落地写命令；跨仓 ALLOCATED 仍要真实 TC。已发布远程 main `f466efc`，Docker 控制台与四服务已按该提交重建。不是 50 AC / AC-26 accepted。
- 2026-09-12 AC-26 现场走查：Casdoor 收货→质检→上架→跨仓准备（PLANNED）→拣→部分发→未拣回库。重复行主键改为 409。证据 [AC26_LIVE_WALK.md](AC26_LIVE_WALK.md)。仍 open。
- 2026-09-12 F6：队列页抽屉建单、单据命令抽屉、首页活队列、401≠403；Casdoor 作业 scope 已补全。需重新登录。不是 50 AC / AC-26 accepted。
- 2026-09-12 前端架构技能复查：不另起 IA。F7 已落地（登录一列、权限按 scope 隐藏、列宽/cursor/错误码、单据 Tabs）。用户要求合入远程 main 并重建 Docker console。不是 50 AC / AC-26 accepted。
- 2026-09-12 F8：公开契约里已有领域的缺口接到 HTTP 与控制台。主数据写、仓任务 list/get/claim、流水/operation/效果、catalog 建档、jobs 仓任务、stock 流水、recon 导出快照。ALLOCATED 不编造。TP99 unverified。不是 50 AC / AC-26 accepted。
- 2026-09-12 F9：补齐缺领域公开路径。移库/限制/独立调整（V021，限制≠location_gate，调整≠count_plan）；履约取消 202 只受理；出库 execution-authorizations 缺 Committed 证据则 409，出库单 ALLOCATED ≠ 履约 ALLOCATED。`DomainHttpIT` / `FulfillmentHttpIT` / `OutboundHttpIT` / `OutboundPickIT` / console 33 测通过。列表调整无 L1+L2；TP99 unverified。不是 50 AC / AC-26 accepted。

## 未完成

- S8-05。S9-01 签署峰值。50 AC 全量证据。OQ-03。AC-26 真实 TC ALLOCATED。AC-42。用户已要求取消进行中的 main verify 并推送本分支。

无生产部署。


## 后端评审整改

2026-09-12：用户批准先 R16–R20，其余按 DELIVERY_PLAN.md 末尾顺序执行。当前分支 fix/backend-review-remediation，基线 db02821；R16 running，其余 pending。未宣称任一整改完成或原 50 AC 通过。

- 后端整改 R16–R20 实现与定向测试通过，完整 Maven verify 执行中，尚未提交。证据和配置边界见 [BACKEND_REMEDIATION.md](BACKEND_REMEDIATION.md)。后续R项未修改。

- 2026-09-12 后端 R16–R20 首批本地验证完成：默认回归唯一 DTO 兼容性失败已修复并定向通过，必需24测试/四服务smoke/控制台33测试通过；详细限制见 BACKEND_REMEDIATION.md。R01–R04继续实施，完整配置组合与远程CI待执行。
