# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S7-01 本地已通过。S6-01…S6-04 与 S7-01 仍待 timeout-fix main CI 后再按序 merge。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：`feat/wms-s7-01`。`origin/main` 仍是 `8d4ca21`。S6-01 与 main 分叉，必须 merge 不能 FF。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：XXL 3.4.2 任务目录、条件执行器、双执行器重复触发验证与文档。
- 测试目标：localhost / Testcontainers MySQL 8.4.11 + 官方 admin 3.4.2。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、把探针当生产调度、把本切片当 AC-20。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | timeout-fix verify `34666539607` 进行中；成功后再 merge S6-01→S7-01 |
| EG-02 TC配置/唯一TM | running | XXL handler 清理上下文，不得 Confirm/Cancel |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | local-pass | S5-06 已在 main |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | S7-01 本地已通过；先等前序 main CI |

## 本轮已实现（S7-01）

- `WmsJobCatalog` 固定 10 个 BEAN handler；库存/履约/出库各自注册。
- `wms.xxl.admin-addresses` 非空才启动 `XxlJobSpringExecutor`，smoke 不连 admin。
- `JobCatalogClusterIT`：两执行器注册；同参数重复触发 runs=20、effect=10；无 XID。
- `WmsJobCatalogTest` 与 `OidcDisabledWebIT` / `AllocationRecoverySweepIT` 回归通过。

## 未完成

- 按序 merge 发布 S6-01…S7-01。S7-02…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
