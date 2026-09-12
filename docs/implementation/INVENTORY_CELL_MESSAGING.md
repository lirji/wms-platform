# 多物理cell的库存消息路由

当前切片已通过定向复验。既有单库存库可以继续使用一个共享消费组；不同物理cell不能共用该组竞争同一分区，否则消息可能仅到达不拥有该仓的进程。

## 明确路由与最终校验

启用`wms.messaging.inventory-cell-id`及`wms.messaging.inventory-routing-json`进入多cell模式。启用原生RM时cell默认沿用`wms.tcc.rm.cell-id`，显式消息cell必须一致；原生RM同时启用消息却没有清单时拒绝启动。清单需要完整表达参与该消息前缀的企业仓归属，不从临时收到的消息推断归属。

```json
{"schemaVersion":1,"routes":[
  {"enterpriseId":"ENT","warehouseId":"A","cellId":"A","routeEpoch":1},
  {"enterpriseId":"ENT","warehouseId":"B","cellId":"B","routeEpoch":1}
]}
```

配置最多256KiB/1000个企业仓，同一企业仓只能有一条；版本、代际严格校验整数范围。每个进程的清单必须包含本cell的仓。清单是投递范围配置，`warehouse_route`仍是写入权威：库存Inbox业务事务内锁定同企业/仓路由，只有cell、ACTIVE状态及routeEpoch全部一致才处理。

每cell及规范清单的SHA256生成独立消费组，同cell同清单的副本共享组。清单排序不改变组；配置内容变更产生新组，从broker保留日志重放，以免复用旧组中曾跳过该仓的位点。原Inbox事件身份和库存命令幂等防止已执行事件重复入账。

## 失败与恢复

只有清单明确属于其他cell的有效信封才跳过。未知仓仍落Inbox，处理时以`CELL_ROUTE_UNREGISTERED`隔离；非法信封沿用持久化毒消息处理。陈旧代际、缺路由、非ACTIVE或cell不一致以`CELL_ROUTE_CHANGED`隔离。不会以成功回执掩盖未执行库存命令。

已有隔离记录不会因新组重放就自动解除：先修复清单及权威路由，再使用既有受权限控制的消息审计重排入口。正常切换必须保留原Inbox/命令/库存幂等事实、保证消息保留窗口和迁移截点；本切片不证明TC资源迁移或跨库切流完成。已经超出broker保留范围的历史不能靠新组恢复，需独立可审计的补偿/恢复来源。

单库兼容模式只适用于一个物理库存库的副本，不可用于多个独立库。所有同cell副本必须使用一致清单；滚动时旧清单不能绕过数据库路由代际。运行时不热改清单，按正常配置变更及重启治理。监控沿用Inbox积压、隔离数和错误码；不新增无界租户指标标签。

## 兼容与验证

未增加中间件或依赖。消息信封及来源Outbox同时补严格V1整数上限，`4294967297`等超大数值不能截断为1。旧合法V1内容不改写，不新增用户消息必填字段。

首轮路由配置单元及真实两仓APPLIED/无跨仓Inbox断言已到达，但测试随后误用不存在的`physical_qty`列失败；已改为真实`on_hand_qty`，不能算首轮整体通过。陈旧路由断言精确关联原`ROUTE-STALE`事件。

04:42:04 `/tmp/wms-cell-routing-second-it.log` BUILD SUCCESS：RuntimeRmProcessesIT与扩展后的AllocationExecutionProcessesIT共2个真实进程IT；路由配置2项单元及消息版本边界测试通过。真实TC/TM分配链保留，额外向Kafka发送明确夹具收货消息，证明双cell分别入账、无跨仓Inbox、重复不重复加量、未知仓隔离和陈旧路由拒绝写入。夹具消息部分没有启动inbound，不称为收货来源全链验收。

远程0a1 main CI34716410427成功；分支34716400385唯一失败是RuntimeRmProcessesIT的B仓重启后HTTP Connect，readiness修复已单独提交7ec2fbc，同批复验通过。当前切片不改变依赖、迁移或公开API；整体最终全仓verify仍待。
