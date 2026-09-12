# Codex Progress

## 任务目标

按用户确认的收货分批，持续完成R13/R14/R15/R22及正常提交推送main。已授权Git联网；不强推、不部署生产、不改共享数据/其他工作树；无子Agent授权。

## 已完成

- 远程main与fix/count-serial-reservation为3e2c720；按批收货/质检/上架、源释放恢复、完整SN盘点输入及逐身份持久恢复、盘点占用保护均已发布。545ae48的main/分支CI成功，3e2 main CI34721632607运行中，不能推对应ref取消。
- 序列PICK当前切片已验证：来源V017原选择/逐SN占用/原回执；库存V041原预占与具体SN原子移动；53表迁移；库存serial-stock有界权限查询返回epoch；SerialExecutionSelectionV1，序列内部出库V2（普通V1摘要不变）。跨任务/订单行、错epoch、最后SN写失败全回滚、原重放不拉回、实际Kafka双Jar重启恢复、查询越权/游标边界、消息降级拒绝、HTTP数量/身份不一致400。
- /tmp/wms-serial-pick-first-it.log SUCCESS16IT；/tmp/wms-serial-pick-process-it.log SUCCESS18IT（内部V1时）；/tmp/wms-serial-pick-final-it.log 正常V2通过但异常探针缺发布元数据而失败；修正探针为真实入箱payload后 /tmp/wms-serial-pick-probes-it.log 06:22:49 SUCCESS来源HTTP2/实际进程1。全局登记与TC身份在PICK进程测试为明确夹具，不称真实全链。

## 已修改文件

- 当前独立分支fix/serial-outbound-execution，在唯一集成工作树/Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate；HEAD3e2c720，PICK切片待逻辑提交。
- 来源OutboundSerialService/Mapper、OutboundPostingService、DTO/controller/T3；库存SerialOutboundStockService/Mapper/SerialStockController、StockCommandService/MessageHandler；SourceCommandContextStore、SerialExecutionSelection；V017/V041、迁移清单、三个IT及实际进程/HTTPIT；OpenAPI生成89路径，required112；交付三文档和SERIAL_OUTBOUND_DESIGN。

## 未完成

- PICK契约/门禁/smoke及逻辑提交，随后继续SHIP（不得将PICK称整个序列出库完成）。当前没有Maven，21057已退出。
- SHIP：来源明确原已拣未发SN+epoch选择与T3、库存原预占身份扣减与不可变待登记发运事实、全球SHIPPED及有界可靠恢复/人工重排、公开可发SN查询/契约/真实故障验证。不能冒用MISSING；普通摘要不变。
- R13可信三方水位：SnapshotExportService.export与StockInternalReconcile.closeWindow不能只信非空字符串，需真实来源关闭/过账/回执凭证。
- R14公开序列调拨（sealSource/stageDestination仍只有测试调用）、TC终态通知/原RM资源及Fence迁移、全局提交后取消的业务补偿。
- R15上述来源恢复；归档仅候选计划，无授权期限不能虚构删除/导出。
- R22代码/阶段CI已过，最后全源码组合verify及最终remoteCI；未核实/转换共享生产历史时间。
- 单桶完整SN盘点观察最多200，>200分段完整输入协议未实现；外部WCS/容量/RTO/RPO/50AC签署不能伪造。

## 当前问题

- 所有exec显式唯一workdir，绝不输出完整进程参数或环境（可能有Cursor凭据）。禁止并发Maven或构建时改源码。全部隔离Testcontainers，不使用共享dev_infra数据。
- 远程main CI未完成不得推取消它；任务分支尚未push，PICK可以先本地逻辑commit，再完成SHIP一起正常推送。
- verify-contracts先暂存预期生成文件；scripts/check-required-its.py门禁；现有SBOM两个OSV命中不称零漏洞。

## 下一步建议

1. 完成PICK门禁/smoke/commit，当前测试全结束。
2. SHIP采用来源PICK历史新增shipment命令/posted进度，库存独立ship intent保存原PICK/SN/epoch/operation。全球新增SHIPPED事实，不重用MISSING；网络外呼在TX外，CAS租约/预算及人工恢复沿用既有规则。
3. 逐项完成以上R13/R14/R15剩余再组合verify和发布，不等待继续。

## 恢复 Prompt

读取本文件，继续唯一集成工作树fix/serial-outbound-execution的剩余四项，不重做已发布收货分批/盘点，不等继续。先确认最新工作树/Maven状态，保护用户根目录main f9710ef。
