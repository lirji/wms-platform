# 收货批次的序列号质检

当前切片已通过定向测试。原有`ReceiptQualityDecision`的数量字段和无身份摘要保持原格式；新增独立可选`serialQualityObservation`，包含V1及累计`acceptedSerials`、`rejectedSerials`。两清单规范化后互斥、合计1至200条，数量必须分别匹配acceptedQty/rejectedQty，且只能引用本次原收货批次名单。未出现的原序列号继续HOLD。

## 双侧权威与原子性

来源服务从本库原RECEIVE命令读取固定名单，拒绝序列批次只给数量或给其他批次身份。新的规范名单参与来源版本摘要，与质量命令、来源Outbox和版本受理在同一T1提交；旧普通批次不新增必填字段，也不重新计算旧摘要。

库存再次从本库serial_receipt_batch检查名单和本地身份，每个身份须仍属于原receipt_operation_id、货主、SKU、批次及仓。累计合格/不合格量必须与本批当前身份质量一致；不一致保持失败，不能用同桶其他批次数量修平。数量转桶、全部身份balance_id更新、质量版本、凭证、结果Outbox和Inbox在一个本地事务中提交；任何身份失败一起回滚。新质检凭证保存完整质量数量及身份快照，重放验证摘要，迟到旧命令不重做身份移动。

## 放行和占用约束

未被本次选择且仍HOLD的身份可以继续等待登记。改变质量或已有合格/不合格身份要求本地AUTHORIZED及已持久化ACTIVE登记状态；没有同步网络调用。已移出原收货位的GOOD身份只能继续列为GOOD，不能用另一个序列号补足相同数量后将原身份降级。任何改变身份质量的源桶若仍有reserved或free_execution_claim占用，均拒绝，包含数量净变化为零的身份交换。

本切片不自动处理调拨SEALED、已发运或失踪身份的质检变更；不确定场景保持拒绝。序列号PUTAWAY/PICK/SHIP仍待各自明确身份规则，不能把单个质量切片当作整个序列号生命周期完成。

## 验证计划与兼容发布

05:03:09 `/tmp/wms-serial-quality-first-it.log` BUILD SUCCESS：SerialReceiptBatchIT 7、ReceiveMessagingProcessesIT 1、SerialRegistryProcessesIT 1、InboundHttpIT 1，共10IT，零失败/错误/跳过。新增3项身份质检门禁，必需总数98；公开契约仍88条。没有新增依赖或迁移，原批次V037沿用2818ddf。

真实MySQL覆盖登记未授权、跨批身份、重放、净数量不变的交换、已移动身份及最终身份写失败。实际inbound/inventory Jar+Kafka验证来源质检消息到回执，登记授权前置使用明确夹具；另一个实际登记Jar测试从真实HTTP授权后执行库存质检。两者分别记录，不拼称自动三服务调度全链。

本次无新增迁移或依赖。部署顺序为先支持新字段的库存消费者，再启用来源发送身份质检；旧库存消费者会隔离序列号质量消息，需升级后通过原消息审计重放。不能删掉身份字段降级成数量消息。已受理的新身份命令或凭证在代码回退后仍需保留，回退不撤销已发生的库存质量变化。
