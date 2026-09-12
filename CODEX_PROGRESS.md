# Codex Progress

## 任务目标

连续完成后端评审剩余R13/R14/R15/R22。用户确认按收货分批并继续剩余四项，持续授权逻辑提交、验证后正常推送分支并合入远程main。不等待“继续”，不强推，不部署生产，不操作共享数据或其他工作树。没有授权子Agent。

## 已完成

- 普通RECEIVE、原收货批次QUALITY/PUTAWAY及PICK/SHIP/CANCEL实际来源/库存Jar、Kafka及独立库；原订单行和桶额度约束。TC原生RM/实际TM、原XID/branch重启恢复、可信审计/仓确认/出库授权与多cell路由已经发布。
- HEAD 32d9393。待发布3提交：2818ddf序列收货批次（13IT及真实登记复验）、4c41c60序列QUALITY（10IT）、32d9393逐SN分次上架（12IT）。四Jar smoke/required100/API88/docs47均通过；没有活跃旧Maven/smoke。
- 远程main及任务分支仍34cc417；main CI34718036171已经完整成功。分支CI34718029842截至05:20仍在verify，不能推相同ref取消它。待结束后正常推送全部验证提交到分支并合main。

## 已修改文件

待提交的源释放切片（已通过验证）：
- Inventory V038 serial_release_intent；SerialReleaseMapper/XML/RegistryPort/RecoveryService；源封闭同事务stage、重放核对原epoch/摘要、拒绝同流水跳过其他SN扣减与已占用源桶。
- SerialRegistryService.observeSourceRelease返回sourceRelease历史凭证，跨后续转移重放不改变当前归属；SerialRegistryHttpClient实现原事实释放，验证完整凭证。
- 既有serialTransferRecovery两类各10秒领取预算；既有serial-recoveries有界联合分页/审计重排；迁移复制50表。
- SerialRegistryProcessesIT、SerialTransferRecoveryIT、SerialSealIT、SerialRegistryHttpClientTest、MasterdataHttpIT、WarehouseMigrationIT扩展；required新增2至102。
- docs/implementation/SERIAL_SOURCE_RELEASE.md及本文件。OpenAPI脚本及生成契约已加可选sourceRelease证明与X-Wms-Serial-Release-Proof: 1协商头。

## 未完成

- 首轮33161已05:22:25 SUCCESS（26IT+4单元）；最终20222已05:24:43 SUCCESS（兼容/门禁6IT+4单元）。无活跃Maven。smoke19978已成功退出，四个实际Jar通过；/tmp/wms-source-release-smoke.log；必需102、API88、文档48/113链接均通过，生成OpenAPI已暂存。
- 旧任务分支CI仍运行，但相同34cc的main CI完整通过。准备当前切片提交后建立fix/backend-serial-remediation发布本批4提交，并正常推main；不取消旧分支CI。
- R13：序列PICK/SHIP、可信来源水位。
- R14：当前源释放收尾；盘点逐身份远程结果持久恢复，TC终态通知/原资源与Fence迁移，已全局提交后的业务取消补偿。原生RM仓仍拒绝迁移，不能用本地CONFIRMED假装TC已收妥。
- R15七类catalog handler已有执行器，完整serial/count来源恢复依赖以上。归档仅候选计划，没有擅自删除/导出或编造保留期限。
- R22代码/UTC测试和已发布阶段CI通过；未核实或转换共享/生产历史时间。最后全部源码组合verify及最新CI仍待。
- OQ03真实WCS/签署容量、RTO/RPO和50AC外部验收不能伪造。

## 当前问题

- 唯一工作目录/Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate，分支fix/backend-review-remediation。所有exec显式workdir。根用户工作树main f9710ef保持不动。
- 禁止并发Maven或构建中改源码。文档、Git只读可独立进行。不要输出进程完整参数/环境，避免泄露Cursor凭据。
- 来源释放新意图独立于收货恢复表，防旧worker把SEALED判SUPERSEDED。原释放历史恢复不检查当前local_serial状态，否则下一轮转移将误丢已提交事实。
- 测试仅隔离Testcontainers；serial真实进程测试库存由测试JVM调用，不称整个调度全链。新增源释放成本有界，但10+10秒是领取预算，不含最后HTTP/数据库等待硬保证。
- 无新依赖；沿用0a1 SBOM173组件/163purl及2个既有OSV命中（Tomcat11.0.24、fastjson1.2.83），不称零漏洞。

## 下一步建议

1. 核对smoke19978，按逻辑提交源释放，建立fix/backend-serial-remediation（保留旧分支CI），正常推送本批验证提交到新分支/main，核验refs和新CI。
2. 序列PICK/SHIP：复用SerialStockSelection，但实际库存runtime路径是StockCommandService.applyOutbound；来源类在wms-outbound/.../order。需要逐SN原allocation/attempt/orderline的持久领取/发运事实，身份和数量同事务。普通SHIP不得冒用MISSING，需要全局SHIPPED及原epoch的可靠发运恢复；初始epoch0合法。维持旧普通契约。
3. 盘点observeIdentities需允许空集合表示全丢失，固定完整输入防同命令扩写。原CountService.applySerialIdentities在整行事务循环网络，不能直接注入HTTP。先持久每身份输入/结果，事务外调用，再按存证本地原子落地数量及身份。FOUND校验SKU/桶和原operation/实际epoch，不删除历史转移事实。
4. 继续可信来源水位、TC原资源/Fence迁移与晚取消补偿，全仓最终验证及远程CI。不因完成一个切片暂停。

## 恢复 Prompt

请读取本文件及docs/delivery/wms-v1/BACKEND_REMEDIATION.md，在唯一工作树连续完成剩余四项。先核对smoke19978；没有活跃Maven。待源释放检查后收尾、正常发布验证提交，继续序列出库/盘点/水位/TC迁移和补偿。不重新规划、不等“继续”，无证据不标全部完成。

最新边界：库存公开调拨业务没有调用sealSource/stageDestination，现有源释放是领域同事务+实际恢复器/登记HTTP证据；完整公开调拨仍须接线，不能标全链完成。源释放新代码锁序门禁→local→balance，读源桶后锁内复核。
