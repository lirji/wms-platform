# 可信对账水位

最新验收：`.local/reconciliation-fresh-artifacts-it.log` 于2026-09-13 08:48:03 BUILD SUCCESS，真实三JAR/三MySQL/Kafka/XXL进程1、采集器4、HTTP3及协议/摘要单测5通过。真实收货先过账但来源T3被隔离测试约束阻断时，窗口不完整且拒绝导出；移除约束并重启库存后按原检查点完成，导出实际数量，重放不重复过账。OIDC/XXL admin使用明确协议夹具。

此前fixed/diagnostic进程验证失败，定位到入库可执行JAR嵌入旧运行库，来源Page缺resultState。库存测试加入入库模块依赖，jar打包启用forceCreation，启动前比较三包嵌入运行库SHA256，防止增量构建复用旧依赖。中间current-jars测试因故障用例自身超过8 RPS预算失败，独立客户端隔离后通过；失败日志保留。最新已通过全部相关定向用例，未将旧报告混合宣称为新全量回归。

本任务对应R13/R15，唯一验收见[实施计划](../delivery/wms-v1/DELIVERY_PLAN.md)的WATERMARK。来源提供端及库存历史屏障/门禁已发布main e22f0b2；库存采集、公开入口和XXL接线经真实三服务进程验收后，由2e7451c/051a7eb发布main。来源COMPLETE响应本身不是库存完整快照证明。

## 权威与边界

inbound/outbound分别读取自己的source_command和source_execution，不读写对方数据库。来源窗口按企业、仓、cutoffId固定原cutoff，cutoff是UTC排他上界，精度最大微秒。T1与窗口采集使用同一仓范围行锁；新命令在取得锁后取时间，不能补插在已关闭上界之前。原命令重放仍核对原业务身份。分页只读取200项和下一页探针，SQL另设3秒超时；同一仓T1因此串行，生产吞吐尚未测量，不宣称零成本。

来源每页核对实际APPLIED/REJECTED/CANCELLED终态、原物理量/已回执量、原库存postingId及持久化过账上下文。APPLIED要求数量相等和原postingId；REJECTED/CANCELLED要求零回执数量和空postingId，不能把已结算旧尝试永久当作未收到回执。缺回执或UNKNOWN时保留COLLECTING和原检查点。命令观察数量不能跨重试直接累加为业务实物总量；库存接收端仍须核对每个非过账终态确无库存凭证。检查点、观察数量和链式SHA-256同事务推进；最终写失败时整页回滚。清单只在来源窗口COMPLETE后提供，丢页、换仓、换窗口必须使最终计数/摘要不匹配。

## 入口与启用

版本1摘要使用UTF-8紧凑JSON：初值为SHA256(`[1,sourceService,enterpriseId,warehouseId,cutoffId,规范化Instant]`)；每项取SHA256(`上次十六进制摘要 + 换行 + [commandId,action,executionId,quantity,postedQuantity,postingId,occurredAt,resultState]的紧凑JSON`)。数量为去末尾零的十进制字符串，时间使用Instant格式，事实按commandId排序；非过账postingId使用JSON null，不使用伪造字符串。不可依赖JSON对象的隐含属性顺序。跨语言固定向量在SourceWindowDigestTest；协议已随e22f0b2发布，默认关闭。

- 来源内部入口：`POST /internal/wms/v1/warehouses/{warehouseId}/reconciliation-windows/{cutoffId}`，正文cutoff；200表示来源证明完整，202表示还需采集或等待T3。
- 事实页：同路径的`GET /facts?cutoff=...&cursor=...`，每页最大200；未完成返回409 SOURCE_INCOMPLETE，原时刻冲突返回400。
- 服务JWT须包含`recon.evidence`、对应企业/仓范围，subject必须在`WMS_RECONCILIATION_ALLOWED_SUBJECTS`中。默认空列表拒绝。
- `WMS_RECONCILIATION_WINDOW_ENABLED`默认false，入口默认不启用。必须先扩展迁移、升级全部来源写节点并退出旧节点，再由部署负责人启用。旧写节点不遵守应用屏障，不能共存签发可信窗口。生产启用尚未执行。
- 根.env.example与compose.yaml已透传两个来源的开关和受信主体；不修改真实.env或现有服务配置。

来源新增inbound V017/outbound V019，只有普通表和Mapper，不要求提升数据库权限。最初触发器方案在开启binlog的隔离MySQL上因SUPER权限不足失败，已移除；没有修改账号权限或数据库全局配置。迁移扩展可与旧应用共存，但启用关窗有上述额外前置，代码回退前须关闭证明入口并评估已签发窗口的可信性。

## 库存核验与恢复

库存必须通过受信服务连接取得两个来源的完整页，持久化原窗口、来源摘要/总数、游标、领取代际及核验进度。逐项匹配本库原command/action/sourceExecutionId、postingId、数量和截止前库存过账，最终检查完整计数和摘要。来源已确认但库存凭证晚于cutoff的窗口仍不完整，须使用更晚的新窗口，不回填历史快照。

库存V043新增历史屏障：ledger和posting的Mapper写入口先持有企业/仓共享锁，拒绝早于closed_before的时间；冻结取排他锁并等待在途写事务提交。范围表包含迁移时间水位，随仓复制。旧库存写节点全部退出前不得启用可信窗口。

V044为既有cutoff追加evidence_version，历史记录默认0，保留原数据。closeWindow不再因三个字符串非空而置完整；对账检查证明版本和历史冻结，快照创建与历史读取都核对版本1、冻结上界及原三方标识。只有采集器完成正反向核验后才原子设置版本1；否则SOURCE_INCOMPLETE。VerifiedWindowFixture只用于独立下游测试，采集器测试另行提供原数据库凭证。

V045扩展原cutoff的采集状态/有界进度JSON/领取代际，新增reconciliation_collection_audit；历史屏障新增唯一活动cutoff。56张仓表复制范围包含检查点、claim_epoch及审计。状态为UNREQUESTED→PENDING→RUNNING→PENDING/ISOLATED/COMPLETE，可审计取消为CANCELLED。

1. 本库持久化窗口及两个来源的采集检查点；每个来源依次执行关窗、读取并匹配事实、反向扫描本库截止前posting、完成。网络在事务外，领取代际防旧结果覆盖，分页和失败恢复有界。每个仓只允许一个活动采集窗口，取消只停止采集，不撤销已发生业务。
2. 接收每条来源事实时匹配本库不可变posting的ID、command、action、执行ID和数量，并检查posting时间严格早于cutoff。保存来源链式摘要及有序command成员摘要；再分页扫描本库同来源posting计算成员摘要与数量，防止来源漏项。无须每个窗口复制一份完整历史事实；已有source_execution_fact只保存每命令一次，重复时核对原内容而非静默吞冲突。
3. 每次调用只处理一个阶段的一页；HTTP最多4秒、正文128KiB，固定两来源URL，不跟随重定向。并发全局8/单企业2，速率全局32/单企业8次每秒，线程池队列64；本地SQL最多200项加探针。15秒领取租约，12次连续失败后隔离；成功推进清零连续失败数，避免大窗口因正常分页耗尽预算。等待来源T3采用有界退避，审计重排保留原游标并增加代际。
4. 历史快照排除截止后第一笔版本1流水才从零入库的预建桶；正库存缺历史流水仍拒绝完整导出。三方token覆盖原范围、来源事实/回执和反向posting摘要。最后完成写失败时事务回滚，重启后仍用原检查点核验。

## 运行与公开接口

- 库存`WMS_RECONCILIATION_COLLECTOR_ENABLED=false`默认关闭。启用需配置`WMS_RECONCILIATION_INBOUND_URL`、`WMS_RECONCILIATION_OUTBOUND_URL`、`WMS_RECONCILIATION_TOKEN_DIRECTORY`；跨信任边界使用HTTPS，隔离开发HTTP须显式`WMS_RECONCILIATION_ALLOW_HTTP=true`。配置为类型化绑定。
- 凭据由外部控制器只读挂载：目录内文件名为企业ID的SHA-256十六进制加`.jwt`，单文件不超过16KiB、不能为符号链接，支持原子轮换。Compose只透传配置，不生成或写入服务JWT；运行人员须提供受控挂载。JWT不写进仓库、真实.env或本文。
- `POST /api/wms/v1/warehouses/{warehouseId}/reconciliation-windows/{cutoffId}`仅接受cutoff，要求recon.export；202表示持久受理。GET要求recon.read，COMPLETE时返回服务器三方标识供原快照接口使用。
- 同路径`/retries`及`/cancellations`要求recon.remediate，正文expectedClaimEpoch/reason。重排只允许隔离状态；旧代际409。取消不撤销库存业务、不降低历史冻结边界。
- 常驻XXL `stockInternalReconcile`参数`企业,仓`自动续跑本仓活动窗口，完成后继续最近完整窗口的有界对账。原`企业,仓,cutoffId`参数仍兼容。调度器/执行器必须实际配置并运行；202不会在HTTP线程里遍历全仓。
- 先追加迁移并升级所有来源/库存写节点，旧节点退出后开启来源受信入口及库存采集器。回退需先关闭采集，不能回到忽略版本门禁的旧应用继续提供完整快照。取消采集或回退代码都不撤销已提交业务。

未新增中间件或依赖，未执行生产启用。来源读自己数据库；库存只通过服务HTTP取证。

## 当前证据

采集器核心`.local/reconciliation-collector-fixed-it.log`于08:22:46通过4项真实MySQL IT，覆盖单活动范围、租约接管/旧代际、12次隔离/审计回滚、两个来源原凭证和最终写失败恢复、迟到/额外posting。来源使用显式端口夹具；首次测试复合var声明编译失败已修正，不能记成业务失败或成功。

`.local/reconciliation-window-http-it.log`于08:26:51通过HTTP2；`.local/reconciliation-migration-snapshot-it.log`于08:30:44 BUILD SUCCESS，迁移6/快照3/HTTP2/契约5，HTTP沿真实库存XXL方法和服务JWT客户端接线，来源为HTTP夹具。非空复制包含进度、领取代际和审计；历史首流水及缺失正库存行为已验证。最新结果见下文。

共同T1路径回归`.local/source-window-t1-regression.log`于07:45:22 BUILD SUCCESS（InboundProtocolIT2、OutboundProtocolIT2、OutboundPickIT7）。补充拒绝/取消后，故障注入先因误伤同测试库另一仓已完成窗口失败，已收窄到PAGE仓；`.local/source-window-terminal-fixed-it.log`于07:49:46 BUILD SUCCESS（SourceWindowIT3、摘要向量1、契约5）。失败日志保留，不记为通过。

`.local/source-window-application-guard-it.log`于2026-09-13 07:33:41 BUILD SUCCESS：真实MySQL的SourceWindowIT2，覆盖201项分页、缺T3、最后写失败、重启后摘要一致、在途T1阻塞关窗及拒绝旧时刻新命令。T3状态是明确数据库夹具。

`.local/source-window-http-it.log`入库回归5项通过；新增HTTP越权断言发现500映射错误，已补显式403处理；`.local/source-window-http-fixed-it.log`出库HTTP3于07:36:18通过。补充未完成窗口409、UTC映射与减小SQL读取正文后，`.local/source-window-final-it.log`于07:38:34 BUILD SUCCESS（来源HTTP3、SourceWindowIT2、契约5），随后固定数组摘要的跨语言向量于07:39:23通过。尚无库存侧或完整跨服务水位验收。

库存屏障 `.local/inventory-history-guard-it.log` 的StockCommandIT3通过，覆盖在途提交/旧流水/旧posting/原成功重放。`.local/inventory-evidence-gate-it.log` 的对账4、导出2、HTTP1通过；迁移最初缺时间列，补列后发现既有元数据过滤误排DEFAULT_GENERATED普通列，已改为只排除真实生成列。`.local/inventory-evidence-gate-fixed-it.log` 对账4通过；`.local/inventory-history-migration-fixed-it.log` 于08:10:45 BUILD SUCCESS，WarehouseMigrationIT6通过，覆盖55表清单和非空历史屏障复制。两次失败均保留，不算通过。下游通过仍不代表库存可信采集器已完成。
