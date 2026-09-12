# Codex Progress

## 任务目标

完成后端评审剩余R13/R14/R15/R22。用户确认按收货分批并继续剩余四项，持续授权逻辑提交、必要验证后正常推送分支并合入远程main。不中途等待继续，不强推、不操作生产、共享数据或其他工作树。没有授权使用子Agent。

## 已完成

- R01–R12、R16–R21、R23–R24已有实现和定向验证；不代表全部50AC/真实WCS/生产容量签署。
- 普通RECEIVE、按原RECEIVE commandId分批QUALITY/PUTAWAY、普通PICK/SHIP/CANCEL已通过实际服务Jar/Kafka/独立数据库；质检累计量与各批上架额度受约束。
- 序列号真实HTTP登记、原epoch/ref转移、本地HOLD、持久恢复意图、有界调用与受审计重排已有验证。不能在整个盘点行事务内执行逐SN远程循环。
- TC只读审计、来源绑定、恢复屏障、可靠仓确认与完整出库授权已经接通。原生RM持久登记意图、实际TM分配执行器及原XID/branch重启恢复通过真实TC和四个业务Jar；空事务清理必须有原TC回滚证据，未知begin不盲重启，晚取消不伪装TCC回滚。
- 多cell普通库存消息按完整企业仓归属清单使用独立组，数据库ACTIVE/代际同事务校验；未知仓和陈旧路由隔离。两个真实库存进程及普通收货探针通过。
- 当前序列号收货切片：来源T1固定完整规范清单、V037 serial_receipt_batch、聚合过账后stage-only身份绑定，数量/全部身份/恢复意图/回执同事务；旧命令无原清单不回填。4项MySQL故障/重放测试及真实来源收货链通过；真实登记Jar验证同原收货命令两个身份各自ACTIVE、重放不加量。

## 已修改文件

当前序列收货切片尚未提交：
- wms-contract的SerialReceiptObservation及OpenAPI可选请求扩展；scripts/generate-openapi.py、required-its-default.txt（95项）。
- wms-runtime SourceCommandContextStore；wms-inbound ReceiveRequest/Controller/InboundReceiptService的观察绑定与重放校验。
- wms-inventory SerialReceiptBatchService/Mapper/XML/V037，SerialReceiptService.stagePostedIdentity不加量，StockCommandMessageHandler按主数据校验观察，Persistence注册，迁移49表清单。
- SerialReceiptBatchIT 4；ReceiveMessagingProcessesIT真实序列来源闭环；SerialRegistryProcessesIT同原命令多身份真实HTTP；WarehouseMigrationIT稳定id与JSON复制断言。
- docs/implementation/SERIAL_RECEIPT_BATCH.md、交付状态与本文件。

## 未完成

- 当前切片：收尾smoke/契约/文档/必需95检查及逻辑提交；前一提交34cc的远程CI运行中，不能推同ref取消它。
- R13：序列QUALITY/PUTAWAY/PICK/SHIP明确身份链、可信来源水位。
- R14：调拨来源释放的可靠传播，盘点逐身份远程结果持久化；TC终态通知/原资源和Fence迁移；已全局提交后的取消补偿。原生RM仓仍拒绝迁移，不能用本地CONFIRMED替代TC已收妥证据。
- R15：七个catalog handler已有执行器，serial与count完整来源恢复仍依赖上述链路。归档仅候选计划，没有擅自导出或删除、没有编造保留期限。
- R22代码、UTC定向与已发布阶段CI通过，没有核实/转换共享或生产历史时间；最后全部源码的组合verify仍待。
- OQ03、真实WCS、签署容量/RTO/RPO及50AC外部验收不能伪造完成。

## 当前问题

- 唯一工作目录 /Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate，分支fix/backend-review-remediation。所有exec显式workdir。根用户工作树保持main f9710ef，不切换、不覆盖。
- HEAD及远程main/任务分支均34cc41764612b0a83e9767e7ee71124327b5e7f3。e10ef1b创建命令回执、2e8ddf4自动TM执行、7ec2fbc重启readiness修复、34cc417多cell路由均已发布。
- main CI34718036171、分支CI34718029842运行中；0a1main成功，0a1分支失败唯一为已修复的HTTP就绪竞争。95a1/bd6两路CI均成功。
- 所有Maven已结束。最新31145于04:55:17成功（/tmp/wms-serial-batch-registry-it.log）；此前64532于04:54:18成功13IT（/tmp/wms-serial-batch-second-it.log）。当前smoke session71662运行，日志/tmp/wms-serial-batch-smoke.log。禁止并发Maven或编译时改Java/XML/配置。
- 序列首轮69555于04:51:19失败：收货/批次测试已通过，但新表缺迁移id，3个迁移用例失败；已修复尚未发布V037主键并复验通过，不修改已发布迁移。
- 本切片没有依赖变更；复用0a1 SBOM173组件/163purl及2个既有OSV命中（Tomcat11.0.24、fastjson1.2.83），不称零漏洞。
- 旧完整全仓基线c534/95a1：223用例/108类、零失败/跳过，/tmp/wms-c534-default.log 03:12:03；不能覆盖当前未提交源码。最新必需清单95需收尾核对。

## 下一步建议

1. 核对smoke71662，stage生成契约后verify-contracts，检查required95/文档/diff，提交序列批次切片。远程34cc CI结束后才正常发布后续提交，不取消。
2. 接着序列QUALITY/PUTAWAY。ReceiptQualityDecision现无身份；建议独立可选SerialQualityObservation及重载，不改变无观察的旧JSON摘要。contract模块没有编译期Jackson依赖，不为@JsonInclude盲加依赖。来源T1比较完整累计accepted/rejected身份；库存按原批次名单/本地归属/登记授权校验，数量转桶与逐身份balance绑定同事务。已移出接收位的GOOD身份不能被累计数量替换降级；净数量不变的身份交换也须校验。
3. 盘点observeIdentities当前拒绝空集合（全丢失），且重放只insertIgnore可能扩写身份，需固定完整输入。CountService的FOUND/AUTHORIZED跳过仍须检查SKU/桶；插入FOUND的ownerEpoch、原operation也需正确。先持久逐身份原输入/远程结果，事务外调用，再本地行数量+身份原子提交，不能将HTTP端口直接塞入现有整行循环。
4. 普通SHIP不能冒用全局MISSING；SEALED来源不是实物数量；转移轮回保留原epoch/ref历史。完成其余链路及全量组合验证后，正常推送main并核验CI。

## 恢复 Prompt

请读取本文件及docs/delivery/wms-v1/BACKEND_REMEDIATION.md，在唯一工作树连续完成已批准的剩余四项。先核对活跃进程，禁止并发Maven或编译中改源码。当前序列号收货13IT及真实多身份登记通过，待收尾提交，继续序列质检/上架/盘点及可信水位等，不等待“继续”，无证据不标全部完成。
