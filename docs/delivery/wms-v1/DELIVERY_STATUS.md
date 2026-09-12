# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S8/S9 已合入远程 main，S9-05 证据与外部门禁未齐。
- `origin/main`(wms)=`2d270ba`（含 S8-01/S8-04/S9-02/S9-03/S9-04/S9-06）。用户工作区 `main` 未切换。
- 任务分支：`feat/wms-s9-02` @ `7588f18`。
- recon `origin/main`=`01a443b`。
- 未发明 OQ-03。S8-05 无授权设备。S9-01 无签署容量输入。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户要求做到 S9 / 50 AC 且不必逐步确认。
- 测试目标：localhost / Testcontainers MySQL 8.4.11。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、把 simulator 当真实设备、把合成峰值当签署容量。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | 已发布 `2d270ba`；新 main verify 与 `feat/wms-s9-02` `34671018575` 待核 |
| EG-05 外部与非功能 | running | S9-02/04 本地两库；S9-01 agreed-peak 仍 blocked；S8-05 仍 blocked |
| Git发布 | pass（本切片） | 远程 main 含 `7588f18` 与 merge `2d270ba` |

## 本轮（S9-02 / S9-04）

- `warehouse_route` + 短暂停写迁移：全量/增量/停写/切 epoch/旧库拒写/切流后禁回退。
- `IsolatedRestoreIT` 测本地 RTO/RPO 缺口，不宣称生产 SLO。
- `run-capacity.sh --scenario agreed-peak` 无签署输入退出 2。
- 本地：`WarehouseMigrationIT` 2/0，`IsolatedRestoreIT` 1/0。restore 打印 localRtoMs≈476–525、localRpoMs≈495–547，缺 OP-AFTER。
- 合入时保留 main 更完整的 `wms-console` gitignore（含 `.vite/`、`coverage/`）。

## 未完成

- 新 main CI。S8-05。S9-01 签署峰值。S9-05 50 AC 全量证据。OQ-03。UI accepted。

无生产部署。
