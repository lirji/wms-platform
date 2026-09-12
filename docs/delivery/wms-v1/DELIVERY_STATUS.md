# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：计划 S7-04 内部对账本地已通过，待 main CI 后与 S7-03 一并发布。
- `origin/main`=`a57f366`。用户工作区 `main` 未切换。
- 用户要求按唯一计划做到 S9 / 50 AC，切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 当前分支：`feat/wms-s7-04-reconcile`（含已推送的 `feat/wms-s7-03-projection`=`8d911d7`）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：稳定 cutoff 内部对账、差异工作台、三方 watermark；审批不改写余额；不发明 OQ-03。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | main verify `34668132832` 覆盖 `a57f366`；`cancel-in-progress` 期间不另推 main |
| EG-02 TC配置/唯一TM | running | 巡检清理 XID，不 Confirm/Cancel |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | local-pass | S5-06 已在 main |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S0、S4-01…S7-02 已在 remote main；S7-03/S7-04 待 merge |

## 切片对照（以 DELIVERY_PLAN 为准）

| 计划切片 | 内容 | 状态 |
| --- | --- | --- |
| S7-01 / S7-02 | XXL 目录与 job_run | 已在 main |
| 租约抢占 / 过期巡检 | 本地提交，未入 main | `e2d31f6` / `ed77bef` |
| S7-03 | 查询投影 | 已提交 `8d911d7`，任务分支已推 |
| S7-04 | 稳定 cutoff 内部对账 | 本地 IT 通过，待提交 |
| S7-05 | 中断恢复、旧 worker、投影缺口、消息恢复负载 | 未开始 |
| S8–S9 | 外部对账/UI/容量 | 未开始 |

## 本轮已实现（计划 S7-04）

- `reconciliation_cutoff` / `reconciliation_case` / `source_execution_fact`（V018）。
- 余额↔流水、预占 remaining、序列号数量在稳定 `closed_at` 下判差，不改写 `stock_balance`。
- 三方水位不齐 → `SOURCE_INCOMPLETE`，不判 `MISSING_*`；宽限内缺过账 → `LATE_ARRIVAL`。
- 工作台列表不跑巡检；审批只改差异单状态并留 operationId。
- `./mvnw -B -ntp -pl wms-inventory -am verify -Dit.test=StockInternalReconcileIT`：2 tests, BUILD SUCCESS。

## 未完成

- 提交并推 `feat/wms-s7-04-reconcile`。等 `34668132832` 成功后在 `.local/main-integration` merge 发布。
- S7-05…S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

无生产部署。
