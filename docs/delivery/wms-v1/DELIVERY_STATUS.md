# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S5-04（同单短拣/包装/发运/取消回库与拣后效期）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s5-04。S5-03 已在 remote main `74c4ffc`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：inventory 短拣拆行/`applyPick`/`applyShip`/`startShipPermit`；outbound `shipPartial`/`consumeShip`；双库黑盒 IT 与文档。
- 测试目标：localhost / Testcontainers MySQL 8.4.11；同 JVM 双库，不是 HTTP。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、真实设备、履约 HTTP、把本切片当 AC-13/14/15 通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S5-03 已推 main；S5-04 本地通过后等该 run 结束再推 |
| EG-02 TC配置/唯一TM | running | outbound/integration 无 Seata |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5-06 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | 等 S5-03 main CI 结束后发布 S5-04 |

## 本轮已实现（S5-04）

- inventory：`pickReserved` 只转本次 q；`releaseUnpicked` 只放源桶剩余；`applyPick`/`applyShip` 同命令不二次移动/扣减；`startShipPermit` 实时效期。
- outbound：`shipPartial` 受包装未发约束；`consumeShip` 仅新 inbox 加 posted；取消后已发+取消=分配则 SHIPPED。
- 黑盒：同 JVM 双库；短拣 3/5、重复发运、取消回库、拣后 `LOT_EXPIRED` 且已拣保留。不是 HTTP/履约/WCS。

## 未完成

- 发布 S5-04。S5-05…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
