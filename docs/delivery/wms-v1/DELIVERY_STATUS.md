# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S6-03a 本地已通过，等待 timeout-fix main CI 后再按序合入 S6-01…S6-03a。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：`feat/wms-s6-03a`。`origin/main` 仍是 `8d4ca21`（45m timeout）。S6-01 `01eeeaa` 与 main 分叉，必须 merge 不能 FF。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：库存 `V014__count_observation_serial.sql`、登记 MISSING/FOUND、门禁矩阵断言与文档。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、把本切片当 AC-18/19 生产通过、跨库存守恒与冻结竞态（S6-04）。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | timeout-fix verify `34666539607` 进行中；成功后再 merge S6-01→S6-03a |
| EG-02 TC配置/唯一TM | running | 盘点/登记不走 Seata |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | local-pass | S5-06 `ClosedLoopBlackBoxIT` 已在 main；不是生产 HTTP/TC |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S6-03a 本地已通过；先等前序 main CI |

## 本轮已实现（S6-03a）

- 门禁矩阵补充 OPEN/QUIESCING/FROZEN × COUNT_OBSERVE/COUNT_ADJUST/UNFREEZE/ARBITRARY_RELEASE。
- 序列号盘点必须提交观察身份集合；qty 必须等于见到的身份数且等于快照 + FOUND − MISSING。
- 盘亏本地先 `MISSING_PENDING`，登记确认后 `MISSING`；收货恢复不得复活。盘盈 `claimFound`/`activateFound` 后才新增本地 AUTHORIZED。
- 登记不可用或未收敛时行不解冻；`MISSING_PENDING` 按计划余额范围计数。

## 未完成

- 按序 merge 发布 S6-01…S6-03a。S6-04…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
