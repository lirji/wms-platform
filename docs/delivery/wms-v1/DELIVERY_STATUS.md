# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S5-05（安全关闭独立证据、executionAttempt 重授权、迟到旧命令、补偿 DEFERRED/casePart）。
- 用户要求按唯一计划做到 S9 / 50 AC，且切片完成后不必再说「继续」。
- 仅操作隔离工作树 `.local/s1-masterdata`；不部署生产，不改共享 dev-infra。
- 分支：feat/wms-s5-05。S5-04 已在 remote main `5865281`。

## 授权记录

- 来源：已批准 DELIVERY_PLAN；持续 Git 发布；用户明确不要逐步确认。
- 本轮允许：inventory `reject`/`safeClose`/`acceptCommand`/`applyCompensate`；inbound/outbound 来源协议重授权与迟到回执；`ReauthorizationIT` 与协议 IT。
- 测试目标：localhost / Testcontainers MySQL 8.4.11；同 JVM 本库，不是 HTTP/WCS/设备。
- 排除：生产部署、共享 dev-infra、编造 OQ-03、未到 S8 创建 `wms-console/`、把本切片当 AC-48/49/50 生产通过。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | S5-04 已推 main；S5-05 本地通过后等该 run 结束再推 |
| EG-02 TC配置/唯一TM | running | outbound/inbound/integration 无 Seata |
| EG-03 业务决定 | running | OQ-03 仍待 |
| EG-04 完整闭环 | pending | S5-06 |
| EG-05 外部与非功能 | pending | S8/S9 |
| Git发布 | running | 等 S5-04 main CI 结束后发布 S5-05 |

## 本轮已实现（S5-05）

- inventory：REJECTED 保持原状态；`safeCloseRef` 独立写入；新尝试必须引用上一命令本地 `safe_close_id`；STARTED/UNKNOWN/APPLIED 拒绝安全关闭；迟到旧命令不二次过账；原 posting 未到补偿记 DEFERRED，到达后同 casePart 只入账一次，超额 `OVER_REVERSE`。
- inbound/outbound：`safeClose` 后下一 attempt；迟到旧 APPLIED 回执只更新自己的历史，不改新 effect `applied_command_id`。
- IT：`ReauthorizationIT` 3 项、`OutboundProtocolIT` 迟到回执、`InboundProtocolIT` 迟到回执。不是 HTTP/设备，不能当作 AC-48/49/50 生产通过。

## 未完成

- 发布 S5-05（先等 S5-04 main CI）。S5-06…S9。50 项 AC。OQ-03。S8 `wms-console/`。

无生产部署。
