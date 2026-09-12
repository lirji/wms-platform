# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：计划 S7-03 投影本地已通过，待 main CI 后发布。
- `origin/main`=`a57f366`（live_guard 同列注释修复）。用户工作区 `main` 未切换。
- 用户要求按唯一计划做到 S9 / 50 AC，切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 当前分支：`feat/wms-s7-03-projection`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：查询投影 eventId+aggregateVersion、asOf/lag、重建追平校验；不发明 OQ-03。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | main verify `34668132832` 覆盖 `a57f366`；`cancel-in-progress` 期间不另推 main |
| EG-02 TC配置/唯一TM | running | 巡检清理 XID，不 Confirm/Cancel |
| EG-03 业务决定 | running | OQ-03 仍待；本切片未发明单位/失效默认 |
| EG-04 完整闭环 | local-pass | S5-06 已在 main |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S0、S4-01…S7-02 与注释修复已在 remote main；租约/过期巡检/投影仍在任务分支 |

## 切片对照（以 DELIVERY_PLAN 为准）

| 计划切片 | 内容 | 状态 |
| --- | --- | --- |
| S7-01 | XXL 任务目录 | 已在 main `af1f6fb`/`6b7850d` |
| S7-02 | job_run/job_shard | 已在 main `6b7850d` |
| 租约抢占 IT | 并发领取与旧 epoch 拒绝 | 本地 `e2d31f6`，未入 main |
| 过期巡检 | 只通知不释放、不发明效期 | 本地 `ed77bef`，未入 main |
| S7-03 | 查询投影 eventId+version、asOf/lag、重建追平 | 本地已通过 `InventoryProjectionIT`，未提交前工作树 |
| S7-04 | 稳定 cutoff 内部对账、差异工作台、三方 watermark | 未开始（不是过期巡检） |
| S7-05 | 中断恢复、旧 worker 回写、投影缺口、消息恢复负载 | 未开始 |

## 本轮已实现（计划 S7-03）

- `projection_inbox` / `inventory_view` / `projection_checkpoint`（V017）。
- 按 eventId 去重；按连续 `aggregateVersion` 更新；缺口不覆盖。
- 重建拷贝权威余额，高水位未追平拒绝切换（`REBUILD_LAG`）。
- `GET /api/wms/v1/inventory` 返回 items + asOf + lagSeconds；不注入 Clock bean。
- `./mvnw -B -ntp -pl wms-inventory -am verify -Dit.test=InventoryProjectionIT`：2 tests, BUILD SUCCESS。

## 未完成

- 等 `34668132832` 成功后再合入租约/过期巡检/投影（与 main 注释修复 SHA 分叉，需 merge 不能 FF）。
- 计划 S7-04…S9。50 项 AC。OQ-03。S8 才做 `wms-console/`。

无生产部署。
