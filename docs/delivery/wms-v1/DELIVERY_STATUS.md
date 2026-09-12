# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S7-02 本地已通过。S6-01…S7-02 仍待 timeout-fix main CI 后再按序 merge。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：`feat/wms-s7-02`。`origin/main` 仍是 `8d4ca21`。S6-01 与 main 分叉，必须 merge 不能 FF。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：`job_run`/`job_shard` 持久化、活跃分片唯一、心跳/回收/fence 与文档。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、把本切片当 AC-20。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | timeout-fix verify `34666539607` 进行中；成功后再 merge S6-01→S7-02 |
| EG-02 TC配置/唯一TM | running | 任务协议不走 Seata |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | local-pass | S5-06 已在 main |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S7-02 本地已通过；先等前序 main CI |

## 本轮已实现（S7-02）

- 四库各自 `job_run`/`job_shard`；`live_guard` 保证活跃分片唯一。
- `JobRunService`：规划幂等、领取、心跳、检查点、完成/失败、过期回收递增 epoch。
- `JobRunIT`：同 runKey 重放；冲突活跃分片 `DUPLICATE_ACTIVE_SHARD`；回收后旧 fence `STALE_FENCE`。
- 入/出/履约迁移随既有 IT 应用到 v005/v006。

## 未完成

- 按序 merge 发布 S6-01…S7-02。S7-03…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
