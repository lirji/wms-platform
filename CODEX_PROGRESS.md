# Codex Progress

## 任务目标

连续完成后端评审剩余R13/R14/R15/R22。用户确认按收货分批并继续剩余四项，持续授权逻辑提交、验证后正常推送分支并合入远程main。不等待“继续”，不强推，不部署生产，不操作共享数据或其他工作树。没有授权子Agent。

## 已完成

- 普通RECEIVE、原收货批次QUALITY/PUTAWAY及PICK/SHIP/CANCEL实际来源/库存Jar、Kafka及独立库；原订单行和桶额度约束。TC原生RM/实际TM、原XID/branch重启恢复、可信审计/仓确认/出库授权与多cell路由已经发布。
- 2818ddf序列收货批次、4c41c60身份质检、32d9393分次上架、545ae48源释放恢复均已发布远程main。HEAD和新分支fix/backend-serial-remediation均545ae489d607baa1b5ff787ea5f1e25b803d1beb，refs已核验。旧任务分支远程34cc417不动，保留其CI。
- 34cc417 main CI34718036171完整成功；旧分支CI34718029842仍需跟踪。545ae48 main CI34719965152与新分支CI34719958715运行中；不要在这两路verify结束前推相同ref。
- 源释放最终20222于05:24:43 SUCCESS（6IT+4单元）；首轮26IT+4单元通过；smoke19978四Jar成功，必需102/API88/docs48。所有源释放Maven/smoke/Git进程已退出。

## 已修改文件

当前未提交盘点观察切片：
- 新SerialCountObservation；CountCommandRequests/Controller公开可选serialObservation，明确0至200个完整实见身份，HTTP沿用实际200。
- 库存V039追加observation_kind/serial_input_json；CountMapper绑定原输入、查询轮次和有界原身份，INSERT替代吞错误的IGNORE；CountService完整输入比较、空集合、跨审批后原结果重放、轮次递增、主数据策略和影响行数校验。
- CountSerialIT新增2项（总4），MasterdataHttpIT新增真实观察HTTP；scripts/generate-openapi.py/required-its-default（新增3至105，OpenAPI已生成）。
- docs/implementation/COUNT_SERIAL_OBSERVATION.md及本文件，其他交付记录待结果后更新。

## 未完成

- 首轮47350已05:31:37 SUCCESS（28IT）；最终95698已05:33:53 SUCCESS（24IT）。当前无Maven。smoke65601已成功退出，四Jar通过；/tmp/wms-count-observation-smoke.log；必需105/API88/docs49/115链接通过，OpenAPI已生成并暂存。
- R13：序列PICK/SHIP、可信来源水位。
- R14：源释放已发布；盘点逐身份远程结果持久恢复，TC终态通知/原资源与Fence迁移，已全局提交后的业务取消补偿。原生RM仓仍拒绝迁移，不能用本地CONFIRMED假装TC已收妥。
- R15七类catalog handler已有执行器，完整serial/count来源恢复依赖以上。归档仅候选计划，没有擅自删除/导出或编造保留期限。
- R22代码/UTC测试和已发布阶段CI通过；未核实或转换共享/生产历史时间。最后全部源码组合verify及最新CI仍待。
- OQ03真实WCS/签署容量、RTO/RPO和50AC外部验收不能伪造。

## 当前问题

- 唯一工作目录/Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate，分支fix/backend-serial-remediation。所有exec显式workdir。根用户工作树main f9710ef保持不动。
- 禁止并发Maven或构建中改源码。文档、Git只读可独立进行。不要输出进程完整参数/环境，避免泄露Cursor凭据。
- 来源释放新意图独立于收货恢复表，防旧worker把SEALED判SUPERSEDED。原释放历史恢复不检查当前local_serial状态，否则下一轮转移将误丢已提交事实。
- 测试仅隔离Testcontainers；serial真实进程测试库存由测试JVM调用，不称整个调度全链。新增源释放成本有界，但10+10秒是领取预算，不含最后HTTP/数据库等待硬保证。
- 无新依赖；沿用0a1 SBOM173组件/163purl及2个既有OSV命中（Tomcat11.0.24、fastjson1.2.83），不称零漏洞。

## 下一步建议

1. 盘点观察定向与smoke已通过，提交当前切片；545ae48两路CI结束后发布，不能取消旧验证。
2. 序列PICK/SHIP：复用SerialStockSelection，但实际库存runtime路径是StockCommandService.applyOutbound；来源类在wms-outbound/.../order。需要逐SN原allocation/attempt/orderline的持久领取/发运事实，身份和数量同事务。普通SHIP不得冒用MISSING，需要全局SHIPPED及原epoch的可靠发运恢复；初始epoch0合法。维持旧普通契约。
3. 继续Count逐身份持久恢复。当前观察修复已在测，最多200单桶身份，较大桶多段观察协议未实现（不能伪拆成多个完整观察）。CountApplyRecovery现只在整行事务调用new CountService(...).applyLine，没有registry；不要直接注入HTTP。需逐SN持久原输入/epoch/receipt/结果，事务外markMissing或claimFound/activateFound，结果齐备后原子修改身份和数量。FOUND必须修复实际ownerEpoch和receipt_operation_id，保持历史转移事实；普通发运不能用MISSING。
4. 继续可信来源水位、TC原资源/Fence迁移与晚取消补偿，全仓最终验证及远程CI。不因完成一个切片暂停。

## 恢复 Prompt

请读取本文件，在唯一工作树fix/backend-serial-remediation连续完成剩余四项。先核对smoke65601；无活跃Maven。盘点观察待检查后收尾，继续盘点逐身份事务外恢复、序列出库、公开序列调拨、水位及TC迁移/晚取消补偿。不等“继续”，不重复已发布工作，无证据不标全部完成。

## 盘点恢复下一切片设计草稿（尚未实施）

- 当前CountApplyRecovery先claim行租约30秒/预算8，再整行事务调用CountService.applyLine；不能将逐SN HTTP塞进该租约/事务。CountService旧带SerialCountRegistryPort构造主要为内存测试使用，正式Controller/作业都无registry。
- 建议count_adjustment_intent按原计划行持久固定首个调整operation/actor/observation/输入摘要，后台必须复用先前HTTP原命令；逐SN子意图保存原receipt/epoch/SKU/lot/balance及MISSING或FOUND动作、网络结果、claimEpoch/lease/次数。网络每次只处理有界子集，齐备才允许当前行数量及身份同事务落地。PRESENT不需要远程动作，但最终核对原桶及身份；未登记HOLD的MISSING先等待原receipt恢复，不伪造epoch或markMissing未认领身份。
- FOUND结果必须保存并写真实ownerEpoch和新receipt_operation_id；已有AUTHORIZED但不同SKU/桶不能直接continue。重复/乱序依靠原输入和全局操作，不能删除旧转移/释放历史；当前指针更新与不可变历史分开。
- 行级nextRecovery不应在正常逐SN推进时消耗8次业务失败预算；只选择原序列调整证据READY的行，普通数量行保持现流程。Controller applications可先本地stage并202待登记，不在请求事务调用网络。新增配置/表后需要实际Registry Jar+两库和本地最终写失败/丢HTTP回执/接管测试。
