# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S2库存事务内核（S2-01 本轮；S1-01..06 已在 main；S0 工程门禁仍 running）。
- 用户已批准按计划连续执行后续未完成切片，不再等待「继续」。前后端均由当前实施者负责；未开始`wms-console/`。
- 仅操作 wms-platform 隔离工作树 `.local/s1-masterdata`；不部署生产，不修改共享 dev-infra，不触碰原目录 ADR-11 脏文件与 auth-platform 未提交 IAM 改动。
- 分支：feat/wms-s2-01（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；用户 `/goal` 要求当前任务做完并连续执行后续未完成切片；持续 Git 发布授权。
- 本轮允许：S2-01 Quantity、StockBucketKey、ReservationState、InventoryPolicy、测试与文档、任务分支提交并快进远程 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、SpiceDB/ReBAC、`wms-console/`、ADR-11 实现、正式 fulfillment 模块、编造 OQ-03、库存表过账/permit（S2-02 起）。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S1-06 远程 CI verify #34494563824 仍 running。S0 组合门禁与 SBOM 仍未关闭 |
| EG-02 TC组合/唯一TM | running | 沿用 S0 探针；正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | 本地测试 IdP=auth-platform Casdoor；生产 IdP 未锁。OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选，尚未实现 |
| EG-05 外部与非功能 | pending | S8/S9执行 |
| Git发布 | running | S2-01 待提交 `feat/wms-s2-01` 并快进远程 main；无生产部署 |

## 本轮已实现（S2-01）

- `Quantity`：十进制字符串、SKU 精度 0..6、禁止截断、DECIMAL(20,6) 上限。
- `StockBucketKey`：企业/仓/货权/库位/SKU/lot/质量；无批次用 `NO_LOT`；未知质量拒绝；锁顺序按稳定键排序。
- `ReservationState`：TRIED/CONFIRMED/CONSUMED/CANCELLED/RELEASED；CONFIRMED 后不得走 TCC Cancel；HELD/EXPIRED 拒绝。
- `InventoryPolicy`：余额不变量、非序列号可用量资格、门禁命令矩阵；FIFO/FEFO 无静默默认；序列号禁止桶公式。

## 先前已实现（S1-06）

- `V003__stock_effect.sql` 与 action-effects HTTP。远程 main `20dc6a7`。S2 库存事务迁移为 **V004**。

## 先前已实现（S1-05 / S1-02 / S1-04）

- 主数据查询、种子、OIDC、Casdoor 开通。Casdoor JWT × 隔离库存 HTTP 仍 blocked。

## 未完成

- S2-02 Mapper 与 V004。S2-03 起过账原语。隔离 compose 双 Cell 种子 + Casdoor JWT。
- AC-03..06 正式业务验收仍 planned。本轮不是过账/permit/50 项 AC 通过。
- 50 项业务 AC、S0 XXL 真触发、SBOM/CVE、`wms-console/`。

HTTP 写接口除效果身份登记/重授权外未交付库存余额变更。无生产部署。
