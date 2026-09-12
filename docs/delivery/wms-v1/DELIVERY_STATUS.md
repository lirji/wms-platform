# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S9-03/S9-06 本地实现中。S8-01/S8-04 仍待合 main。
- `origin/main`(wms) 仍为 S7 合入 `38a7f6a`；S8+S9-05 在 `feat/wms-s9-05`，本切片在 `feat/wms-s9-03`。
- recon `origin/main`=`01a443b`（S8-02 + S8-03）。用户工作区 `main` 未切换。
- 未发明 OQ-03。S8-05 无授权设备环境，保持 blocked。模拟与硬件分开记录。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户要求做到 S9 / 50 AC 且不必逐步确认。
- 测试目标：localhost / Testcontainers MySQL 8.4.11；console 为 typecheck/vitest/build。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、把数量写入 Money、把 simulator 当真实设备。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | WMS main verify `34668676522` 结束后再合 S8-01/S8-04/S9-03 |
| EG-05 外部与非功能 | running | S8-02/S8-03 已在 recon main；S8-04 UI 待真实 OIDC 联调；S9-03 本地矩阵已写 |
| Git发布 | running | recon S8-03 已发布；WMS S8/S9-03 等 main CI |

## 本轮（S9-03 / S9-06）

- 事件/快照 `CompatibilityGate`：缺 schemaVersion 当 N-1，当前 v1 接受，未知版本拒绝；观察开关只计数。
- `CompatibilityMatrixIT` + `verify-contracts.sh` + `check-required-its.py`。
- failure-it 增补身份唯一与旧摘要重放，并把 AC-45..50 列入 CI 必选名单。

## 未完成

- 合入 WMS S8-01/S8-04/S9-03。S8-05 设备（blocked）。S9-01/02/04。50 AC 汇总证据未齐。OQ-03。UI accepted。

无生产部署。
