# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S1基础资料与安全（S1-06 本轮实现；S1-01..05 已在 main；S0 工程门禁仍 running）。
- 用户已批准按计划连续执行后续未完成切片，不再等待「继续」。前后端均由当前实施者负责；未开始`wms-console/`。
- 仅操作 wms-platform 隔离工作树 `.local/s1-masterdata`；不部署生产，不修改共享 dev-infra，不触碰原目录 ADR-11 脏文件与 auth-platform 未提交 IAM 改动。
- 分支：feat/wms-s1-06（WMS）。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；用户 `/goal` 要求当前任务做完并连续执行后续未完成切片；持续 Git 发布授权。
- 本轮允许：S1-06 效果身份、digestVersion、查询与重授权契约、测试与文档、任务分支提交并快进远程 main。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、SpiceDB/ReBAC、`wms-console/`、ADR-11 实现、正式 fulfillment 模块、编造 OQ-03、库存过账/permit（S2）。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | 本轮远程 CI verify #34492571104 成功（6m51s，含 warehouse-it/tc-it/failure-it）。S0 组合门禁与 SBOM 仍未关闭 |
| EG-02 TC组合/唯一TM | running | 沿用 S0 探针；正式`wms-fulfillment`仍是S4 |
| EG-03 业务决定 | running | 本地测试 IdP=auth-platform Casdoor；生产 IdP 未锁。OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5退出必选，尚未实现 |
| EG-05 外部与非功能 | pending | S8/S9执行 |
| Git发布 | running | S1-06 待提交 `feat/wms-s1-06` 并快进远程 main；无生产部署 |

## 本轮已实现（S1-06）

- `V003__stock_effect.sql`：权威事实唯一映射不透明 `effectId`；尝试按 effect+attempt_no 唯一；写调用同键异内容拒绝。
- POST/GET `/action-effects`、POST `.../execution-attempts`：换客户端键复用身份；OPEN/STARTED 409 `STALE_EXECUTION_ATTEMPT`；UNKNOWN 202 `RECOVERY_PENDING` 不发新号；APPLIED 409 `EFFECT_ALREADY_APPLIED`。
- digest v1 重放忽略 v2 新增数量字段；`LegacyIdentityAdapter` 在事实不全时拒绝随机身份。
- S2 库存事务迁移改为 **V004**。

## 先前已实现（S1-05）

- GET `/api/wms/v1/warehouses/{warehouseId}/lots`：JWT 仓范围，越仓 403 `WAREHOUSE_FORBIDDEN`；临期/过期批次返回显式 RFC3339 UTC，不出现编造的 `2026-09-10T00:00:00Z`。
- GET `/api/wms/v1/skus/{skuId}/units`：企业范围；SKU-LOT 含 CS/`12`/`1`；缺失 SKU 404 `SKU_NOT_FOUND`。
- 空仓范围身份：仓库列表不含 WH-A/WH-B，读库位 403。ops CSV `WH-A,WH-B` 可见两仓。
- 种子复跑：2 仓 / 5 SKU / 6 sku_unit / 6 lot / 8 grant；CS 12:1；LOT-NEAR `2026-09-17T13:00:00Z`，LOT-EXP `2026-09-09T13:00:00Z`，LOT-STD 无生产/失效时刻。
- 本机 Casdoor 开通完成（issuer `http://localhost:8000`，client `wms-platform`）。未起隔离 compose，故 Casdoor JWT 打 inventory 进程仍 blocked。

## 先前已实现（S1-02 / S1-04）

- `scripts/seed-local.sh --profile isolated-wms`：必须显式 Cell A/B JDBC；拒绝 43306/`dev-infra`；WH-A→A、WH-B→B，SKU 种子两边都写。
- `V002__operator_grant.sql`：种子权限映射，运行时仍以 JWT 仓范围为准。S2 库存事务迁移现为 V004（V003 已给效果身份）。
- `wms-security`：issuer 空 denyAll；issuer 非空校验 JWT。`server.max-http-request-header-size: 64KB`。
- inventory 仅当 `WMS_INVENTORY_JDBC_URL` 非空时接库并 Flyway；GET warehouses/skus/locations；越仓 403 `WAREHOUSE_FORBIDDEN`。
- auth-platform `deploy/wms-platform-provision.py`：org/app `wms-platform`，用户 `wms-wh-a`/`wms-wh-b`/`wms-ops`/`wms-denied`，凭据 0600 文件。

## 未完成

- S2 库存事务内核。隔离 compose 双 Cell 种子 + Casdoor JWT 打 inventory HTTP。`GET tasks/{id}/action-effects` 待任务模块。
- AC-01/02/31/47..50 正式业务验收仍 planned。本轮不是过账/permit/50 项 AC 通过。
- 50 项业务 AC、S0 XXL 真触发、SBOM/CVE、`wms-console/`。

HTTP 写接口除效果身份登记/重授权外未交付。无生产部署。
