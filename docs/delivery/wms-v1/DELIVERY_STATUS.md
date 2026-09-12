# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S8-01 待合 main；S8-02 数量场景已在 recon-platform 任务分支。
- `origin/main`(wms)=`38a7f6a`。用户工作区 `main` 未切换。
- 当前 WMS 分支：`feat/wms-s8-01`。recon 工作树：`recon-platform/.local/s8-quantity` `feat/wms-s8-02-quantity`=`8ba5a95`。
- 未发明 OQ-03。未创建 `wms-console/`。未改 recon 原目录脏 `.gitignore`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN S8-02；持续 Git 发布。
- 测试目标：localhost；recon 用模块单测 + recon-core 金额回归。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、S8-04 前 `wms-console/`、把数量写入 Money。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | WMS main verify `34668676522`；S8-01 等结束后再合 |
| EG-05 外部与非功能 | running | S8-02 本地 95+3 tests 绿；S8-03 快照消费未接线 |
| Git发布 | running | WMS S7 已在 main；S8-01/S8-02 任务分支已推 |

## 本轮

- S8-02 评审见 `S8-02-RECON-REVIEW.md`。新建 `recon-wms`，不改 `ReconRecord`/`Money`。
- `./mvnw -pl recon-wms,recon-core -am test`：recon-core 95、recon-wms 3，BUILD SUCCESS。

## 未完成

- 发布 S8-01 到 WMS main、S8-02 到 recon main。
- S8-03 消费快照与差异联动。S8-04 console。S8-05 设备。S9。50 AC。OQ-03。

无生产部署。
