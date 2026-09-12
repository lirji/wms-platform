# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S7-04 本地已通过。`origin/main`=`6b7850d`（S7-02）。S7-03/S7-04 等本轮 main CI 后再合入。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：`feat/wms-s7-04`。用户工作区 `main` 未切换。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：expiryEligibilitySweep 通知、不发明 OQ-03、不释放预占。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | main verify `34667892739` 覆盖 S6-01…S7-02 |
| EG-02 TC配置/唯一TM | running | 巡检清理 XID，不 Confirm/Cancel |
| EG-03 业务决定 | running | OQ-03 仍待；本切片未发明失效时刻 |
| EG-04 完整闭环 | local-pass | S5-06 已在 main |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S6-01…S7-02 已在 remote main；S7-03/04 仅任务分支 |

## 本轮已实现（S7-04）

- `expiry_notice` 按 lot+窗口幂等。
- 巡检只通知已有 `expires_at` 且已失效的批次；未到期批次不落单。
- 预占保持 TRIED；接口仍 `LOT_EXPIRED`。未改 lot 时刻。

## 未完成

- 等 CI 后合入 S7-03/S7-04。S7-05…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
