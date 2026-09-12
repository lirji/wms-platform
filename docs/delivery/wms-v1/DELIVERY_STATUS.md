# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S8-04 本地实现中，待合 main。
- `origin/main`(wms) 仍为 S7 合入；S8-01 在 `feat/wms-s8-01`，S8-04 在 `feat/wms-s8-04`。
- recon `origin/main`=`01a443b`（S8-02 + S8-03）。用户工作区 `main` 未切换。
- 未发明 OQ-03。S8-05 无授权设备环境，保持 blocked。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户要求做到 S9 / 50 AC 且不必逐步确认。
- 测试目标：localhost / Testcontainers MySQL 8.4.11；console 为 typecheck/vitest/build。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、把数量写入 Money、把 simulator 当真实设备。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | WMS main verify `34668676522` 结束后再合 S8-01/S8-04 |
| EG-05 外部与非功能 | running | S8-02/S8-03 已在 recon main；S8-04 UI 待真实 OIDC 联调 |
| Git发布 | running | recon S8-03 已发布；WMS S8 等 main CI |

## 本轮（S8-04）

- 新增 inbound/outbound/fulfillment JDBC 条件接库与工作台 HTTP。
- `wms-console/`：OIDC、九个工作台、202/409/权限/asOf/TCC 状态条；无页面 Mock。
- `InboundHttpIT`/`OutboundHttpIT`/`FulfillmentHttpIT` 定向通过。

## 未完成

- 合入 WMS S8-01/S8-04。S8-05 设备（blocked）。S9。50 AC 汇总证据。OQ-03。UI accepted。

无生产部署。
