# 全链路本地测试数据

```bash
bash test-data/init-test-data.sh
```

清理 TESTFL 补充数据：

```bash
bash test-data/cleanup-test-data.sh
```

只验证：

```bash
bash test-data/verify-test-data.sh
```

先启动隔离 MySQL（`./deploy/up.sh` 或只起 `deploy/compose.local.yml`），并保证仓库根目录 `.env` 含各库口令。脚本拒绝 `43306` / `dev-infra`。SQL 优先 `docker compose exec`，没有容器时再退回宿主机 `mysql`。

数量按测试场景生成最小充分集，不按表灌固定条数。普通实体默认 1~5、上限 10；核心业务默认不超过 100（关系表/字典/行不计入，链路需要时可超过）。`>1000` 才是压测模式，本目录不做。实际条数以 `init-test-data.sh` 结束时的 `COUNT` 为准。

## 生成哪些数据

调用与 `scripts/seed-local.sh` 相同的官方入口类（`SeedLocal` / `SeedInbound` / `SeedOutbound` / `SeedFulfillment`）。不整段调用该脚本，因为 Cell B 缺时区记录时它会整链失败。

- 企业 `ENT-DEMO`，货主 `OWNER-SELF`
- 仓 `WH-A`（Cell A 库存已种子）；`WH-B` 单据在 apps 库，库存种子取决于 Cell B 时区记录
- SKU-STD / SKU-LOT / SKU-SN / SKU-NEAR / SKU-EXPIRED
- WH-A 开账：STD 120 GOOD、LOT 48、NEAR 24、EXPIRED 6 HOLD
- 入库 `INB-DEMO-OPEN-A`（APPROVED）、`INB-DEMO-RCV-A`（RECEIVING 8/20）
- 出库 `OB-DEMO-ALLOC-A`（ALLOCATED，仅单据状态）
- 履约 `FF-DEMO-PLANNED` + `ATT-DEMO-PLANNED`（PLANNED）
- 调拨 `TR-DEMO-AB`（OPEN；目的仓库存可能未种子）
- 草稿盘点 `CNT-DEMO-DRAFT-WH-A`

本目录额外写入 `TESTFL-` 前缀：

- `TESTFL-INB-SN-A`：序列号 SKU 待收货
- `TESTFL-INB-LATE-A`：预期到货已过期
- `TESTFL-OB-PENDING-A`：待执行授权
- `TESTFL-FF-EXPIRED`：截止已过的 PLANNED attempt

## 核心账号与 ID

数据库账号来自 `.env`，不是业务操作员。种子授权 subject：`wms-wh-a`、`wms-wh-b`、`wms-ops`。登录账号由外部 OIDC / Casdoor 提供，不在本脚本里创建。

## 可测场景

见 `scenarios.json`。标准路径：入库收货 → 库存台账 → 履约分配（保持 PLANNED）→ 出库拣发 → 调拨。不要把 `OB-DEMO-ALLOC-*` 当成 TCC 已确认。

## 外部依赖

不启动 Mock。业务 HTTP 需要有效 JWT（`WMS_TEST_BEARER_TOKEN`）。无令牌时验证只检查库表，应用接口记为 SKIPPED。

不写 Redis（缓存由读路径生成）。不向 Kafka 投消息（默认消息开关关闭，Topic 由 compose `kafka-init` 创建）。不写序列号登记库（现有种子明确避免无登记身份）。

Cell B：若库已有表但无时区来源记录，官方 `SeedLocal` 会拒绝写入。本脚本不编造 `legacy-evidence`，跳过 Cell B。根 Compose 库存进程只接 Cell A。
