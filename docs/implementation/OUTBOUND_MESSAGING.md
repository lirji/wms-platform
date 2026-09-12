# 出库命令与库存回执

## 范围

本切片接通普通非序列号商品的 PICK / SHIP / CANCEL：来源 HTTP T1 → source_outbox → Kafka → 库存 Inbox T2 → 结果 Outbox → 来源 Inbox T3。真实组件、桶级额度、最终响应和回执信封验证均已通过。没有部署生产，真实履约 TM/RM、授权消息传播、序列号身份出库仍不在本切片的证明范围。

## 事实和数据所有权

出库的内部 outbound_line.id 与预占的原业务 order_line_id 不相同。来源端从自己的订单/任务派生 owner、SKU、单位、allocation/attempt 和 reservationOrderLineId，操作者提供实际批次；拣货库位来自任务，发运/取消指定实际原桶。T1把 postingContext、原订单行和 outboundSchemaVersion=1 同时冻结在命令与 Outbox；重试更换桶或预占身份拒绝，不给历史无上下文命令猜测回填。

库存端按可信 Topic/source/action 组合接收，在本库检查仓、商品、库位、单位、数量精度、批次归属；PICK/SHIP的序列号商品没有身份观察集合时拒绝；CANCEL仅释放未拣预占，不更改序列号身份。出库只使用原 allocation/attempt 的 CONFIRMED 预占，并按原订单行、完整库存桶、已拣/未拣类型、有无在途数量锁定明细。同桶其他订单行不能借用，TRIED 也不能执行。

每个命令最多消费200条预占分批，额外一条判断是否仍有后续；请求量超过本页可用量时拒绝，可减小数量继续，不因存在201条而永久卡住；PICK只拆未拣根行，SHIP有界消费同订单行同桶的多条已拣子行。CANCEL是释放未拣预占的独立业务动作，产生RELEASE凭证，库存实物不减少；不是对旧拣发命令创建CANCELLED墓碑。库存命令摘要包含完整上下文、原事实和 sourceExecutionId；凭证保留真实来源执行ID，流水operationId按服务/企业/仓/命令确定。

## 来源发运与回执

发运T1在订单锁内检查本集货桶的已回执拣货量，扣除已受理发运后不得为负，同时维持已包装未发的总量约束。outbound_bucket_progress按企业/仓/内部行/库位/批次保存额度，PICK回执与额度增加同事务，SHIP受理与额度条件消费同事务。不扫描完整历史，也不设置永久分批数上限；无上下文的旧拣货不能猜测为当前桶额度。T3从来源本库原命令确定动作和内部行ID，不能信任回执携带任意行。

取消支持qty指定本桶数量，省略沿用原行全部未拣剩余；可分批取消多个桶。取消停止旧未完成拣货任务，剩余量可重新规划；已取消任务拒绝新分批，但允许重放原已受理分批。取消只记录释放请求与等待回执，不创建无库位RESTOCK任务；未拣货物从未移出原桶。outbound_line.cancelled_posted_qty记录真实释放回执，只有拣货、发运、取消三类累计均追平，stock_sync_status才是POSTED。重复事件ID和不同事件ID的同一命令回执都不能重复累计。

## 事务和恢复

T1来源命令、实物/请求事实、固定上下文与待发布事件同事务。T2预占分批、余额、流水、凭证、命令终态、结果Outbox及库存Inbox DONE同事务。T3来源命令/执行回执、订单行累计和运行Inbox DONE同事务。最后一步失败必须回滚所有本库效果，不能因前序SQL成功当作业务已完成。

复用有界Outbox/Inbox的领取代际、租约、最多八次重试、隔离、指标和受审计原消息重排。出库Inbox每轮最多32条且20秒后不再启动新条目；时间预算不声称强行中止已开始的数据库操作。消息恢复不改变原正文、命令身份或已经发生的实物事实。

## 配置、兼容与回退

Compose的WMS_OUTBOUND_MESSAGING_ENABLED和库存消息开关均默认false。启用前准备本环境的outbound.commands、outbound.results及inventory.events；库存确认还使用fulfillment.results。来源独立账号只写outbound.commands、读outbound.results；库存账号读取相应来源Topic并写结果，生产ACL/TLS需由受控环境落实，开发PLAINTEXT不能证明身份认证。

inventory V035追加原订单行/桶/剩余量索引；outbound V015追加桶级额度表、cancelled_posted_qty和约束，旧取消缺少回执保持0，不伪造历史同步。HTTP库位及批次字段在消息关闭时允许同时省略，启用消息后必需且必须成组；先升级客户端再开启来源。历史无上下文命令隔离，不补猜测值。先扩展数据库和升级消费者，再开启来源发布；旧消费者不会处理新动作，因此新来源不能先于库存消费者启用。关闭消息开关只停止后续处理，不能撤销已发运、释放或已提交消息；代码回退和业务补偿分开处理，保留原事实用于恢复。

## 验证边界

OutboundMessagingProcessesIT使用两个本次构建Jar、两套MySQL、Kafka、JWT/JWKS，经HTTP实际拣货/包装/发运/取消；预占Try/Confirm和出库TC授权证据仍是明确夹具。目标断言包含：同桶两订单行互不借用，两次拣货合并一次发运，Outbox最后写入失败的T2回滚、Inbox最后写入失败的T3回滚、重启原消息恢复、换键重试/变更批次拒绝、错误来源动作隔离和重复取消回执。测试结果以本次验证日志为准。

补充OutboundReservationPostingIT真实数据库验证：未确认预占拒绝、订单行/分配重放冲突、同桶其他行保护、部分取消、两线程发运竞争、最终凭证写失败全回滚，以及201次拣货后按200+1发运。2026-09-13 02:49:43，/tmp/wms-outbound-bucket-final-it.log BUILD SUCCESS；本类3、出库双进程1、入库分批双进程1、出库HTTP1/领域5/协议2及依赖单元通过。02:42:57的首轮还通过原双库OutboundExecutionBlackBoxIT 2和派工1。它们都不证明真实WCS或生产容量。

最后复验：/tmp/wms-outbound-replay-final-it.log 02:51:39 BUILD SUCCESS；重新构建三服务Jar，出库双进程与HTTP再次通过。回执aggregateId/version必须与原命令匹配；重试已APPLIED的命令返回POSTED并给出原出库单状态URL。默认必需清单累计74项，最终全量及远程CI尚未完成。
