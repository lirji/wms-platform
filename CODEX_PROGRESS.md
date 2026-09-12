# Codex Progress

## 任务目标

按用户确认的收货分批，连续完成R13/R14/R15/R22。已持续授权逻辑提交、验证后正常推送任务分支并合入远程main；不等待“继续”，不强推、不部署生产、不操作共享数据或其他工作树。没有子Agent授权。

## 已完成

- 远程main与任务分支fix/backend-serial-remediation已发布至545ae48：普通消息闭环/按收货分批质量上架、实际TM/TC/原生RM及履约执行恢复、多cell消息路由、序列收货批次/身份质检/分次上架/源释放可靠恢复。
- 34cc417原分支和main CI均完整成功。545ae48新任务分支CI34719958715已成功；main CI34719965152仍运行，结束前不能推main取消它。
- HEAD f30eb4a盘点完整身份输入已提交未推，空集合、原轮次/原身份重放、整数版本和实际HTTP已验证，最终24IT/四Jar smoke通过。
- 当前WIP逐身份盘点：V040两表及52表迁移；不可变行原操作/主体/观察/身份快照；逐SN事务外登记、持久DONE凭证，齐备READY后原行数量/身份/流水/APPLIED同事务。原生FOUND写实际epoch与receipt操作，保存原历史。
- 第二轮63452于05:53:16结束：31IT中30通过；实际登记Jar丢激活回执和最后本地写失败后恢复通过，租约接管解冻后旧回执无更新通过，迁移新表完整数据通过。唯一失败为HTTP测试使用不存在count.apply权限，被正确403拒绝；已按原契约修正adjustment.apply，未放宽服务权限。

## 已修改文件

- wms-inventory的CountSerialAdjustmentService/CountSerialRecovery/CountSerialMapper及XML、V040、CountApplyRecovery、CountService、Controller、LocalSerialMapper、catalog接线、迁移清单、serial-recoveries列表/审计重排。
- CountSerialIT、CountIT、CountFreezeRaceIT、MasterdataHttpIT、WarehouseMigrationIT、SerialRegistryProcessesIT。
- 登记SerialCommandService对原MISSING返回同事务历史盘亏确认；不重写当前归属，CLAIM/ACTIVATE仍实时验证。SerialRegistryHttpIT覆盖后续FOUND后原MISSING重放。
- scripts/generate-openapi.py、required-its-default.txt新增3项到108；OpenAPI已生成并验证；docs/implementation/COUNT_SERIAL_RECOVERY.md。交付三文档待最终结果同步。

## 未完成

- 81502已05:56:11 SUCCESS（34IT）；60291已05:57:19 SUCCESS，/tmp/wms-count-route-final-it.log，最后CountApplyRecovery领取/应用/失败事务route→plan guard回归10IT通过。当前无Maven。smoke69567四个实际Jar已全部通过；契约88、必需108、文档50/118链接及diff检查通过。
- 最终验证后更新契约、smoke、108门禁/文档、审查提交本切片；main当前CI完成后发布f30及新提交。
- R13：序列PICK/SHIP、可信来源水位。
- R14：公开序列调拨接入（sealSource/stageDestination目前仍只有测试调用）、TC终态通知/原资源与Fence迁移、全局提交后取消的业务补偿。
- R15：以上来源恢复接线；归档仅候选计划，不虚构保留期限或执行删除/导出。
- R22：代码与阶段CI通过，最后全源码组合verify及最新远程CI待；未检查/转换共享生产历史时间。
- 单桶完整SN观察最多200；更大桶分段观察协议未实现，不能拆成多个独立完整集合伪造通过。OQ03真实WCS/容量/RTO/RPO/50AC外部签署不伪造。

## 当前问题

- 唯一工作树/Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate，所有exec显式workdir；分支fix/backend-serial-remediation，HEAD f30eb4a，remote545ae48。根用户工作树main f9710ef保持不动。
- 所有集成测试仅隔离Testcontainers；真实登记进程测试的库存侧由测试JVM调用，不称公开全链。没有其他Maven、smoke或Git会话。
- 真实登记首次epoch=1（0仍是合法协议值），历史MISSING后FOUND为2；已修复新夹具原来误用0，没改服务规则。C3本地MISSING夹具epoch1。
- PRESENT也先等待原收货/转移授权，再固定快照；不能先固化HOLD的0然后授权1导致永久冲突。等待不耗行预算；包括旧执行器在计划COMPLETED后回执，已无更新退出。
- CountApplyRecovery已补claim/应用/失败进度事务route→plan guard，60291的10IT已通过；之后仅修正一行缩进，无语义改变。
- 日志不要输出进程完整参数或环境，可能含Cursor凭据。无新依赖，既有SBOM两个OSV命中不称零漏洞。

## 下一步建议

1. Maven已全部通过，验证与smoke已通过，正在提交盘点恢复切片。
2. 完成当前计数切片契约/文档/门禁/smoke及逻辑提交、main CI结束后正常发布。
3. 序列PICK实际路径StockCommandService.applyOutbound→InventoryApplicationService.postOutboundReservation；来源OutboundPostingService在wms-outbound/order，来源T1上下文SourceCommandContextStore。需要明确SN+ownerEpoch、原allocation/attempt/orderLine占用，T2数量和身份原子移动，来源T3保存每SN可发运证明。
4. SHIP需要独立SHIPPED事实/全局终态及可靠原epoch恢复，不得冒用MISSING。当前SerialStockSelection仅SN，没有epoch，可新增有版本的执行选择契约保持旧普通消息摘要不变。来源V017、库存V041可用。
5. 可信水位当前SnapshotExportService.export和StockInternalReconcile.closeWindow仍只把调用者非空三字符串当完整；须真正来源关闭/库存过账/回执证明，不能换个标志冒充完成。
6. 继续TC资源迁移与晚取消补偿、公开序列调拨、最后组合verify及CI，不因单切片通过而停。

## 恢复 Prompt

请读取本文件，在唯一工作树fix/backend-serial-remediation继续。先确认唯一Maven60291和/tmp/wms-count-route-final-it.log；禁止构建中编辑源码。完成盘点逐身份切片，继续R13/R14/R15/R22剩余，不重复已发布工作，不等我“继续”。
