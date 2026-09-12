# Codex Progress

## 任务目标

完成后端24项整改中剩余R13/R14/R15/R22。用户已确认“按收货分批，然后继续剩余四项”。持续授权独立任务分支、逻辑提交、验证后正常合并推送远程main；不部署生产。

## 已完成

- R01–R12、R16–R21、R23–R24已有实现/定向验证，阶段版本origin/main f9710ef。不是50 AC整体通过。
- 独立集成工作树已有8个待推送提交：26c4472分批质检；9a9b9fc分批上架/批次入口；8019a45有界对账/归档候选；1247334数据库时间来源；bb3651e消息健康/CI去重复；1d645e2登记转移/盘点HTTP；455115c有界登记调用/持久化恢复/审计重排；3ac54b9仓迁移47表/目标隔离/冻结与部分切流恢复。
- RECEIVE/QUALITY/PUTAWAY真实Kafka、双服务Jar、双MySQL闭环通过；批次以原RECEIVE commandId固定，质量累计版本与上架额度均校验。控制台typecheck、33测试、build通过。
- 登记服务真实HTTP、受控主体/企业/仓鉴权、转移原始epoch/ref、FOUND旧记录修复通过。库存有界HTTP与凭据轮换、丢回执/本地提交失败恢复、人工重排通过（/tmp/wms-serial-route-final-it.log 01:35:57）。
- 仓迁移最终5测试通过（/tmp/wms-migration-validation-final-it.log 01:47:05）。不代表真实TC/Fence搬迁与全后台排空已验证。
- 当前TC切片代码和定向验证完成，正在提交：JdbcTcStatusPort只读审计、明确TC集群/TM来源绑定、有界恢复游标、TC查询不持业务锁、陈旧回写拒绝；终态不可覆盖、分支确认必须有完整身份、当前attempt校验、Outbox约束失败原子回滚和重复内容核对。
- TC切片最终证据：/tmp/wms-tc-fulfillment-final-it.log 02:05:32 BUILD SUCCESS（4个真实TC/DB新用例+7个履约回归）；/tmp/wms-tc-barrier-combined-it.log 02:04:03 BUILD SUCCESS（重新编译当前跨服务源码，ClosedLoopBlackBoxIT 1及依赖单元）。真实TC用例的仓确认来自明确夹具，不能算完整库存RM链路。
- standalone warehouse-it 01:09通过；standalone tc-it /tmp/wms-ci-tc-only.log 01:50:06通过2个大探针。没有在同工作树并发Maven。

## 已修改文件

- 当前TC切片：wms-fulfillment新TcEvidenceScope/Mapper/Configuration/JdbcTcStatusPort、AllocationRecoveryMapper和V013；AllocationRecoverySweep/Job、FulfillmentService/Mapper/Persistence、application.yml、测试scope Seata依赖和复用TC测试资源。
- TcAuditRecoveryIT及原3个履约夹具、.env.example、scripts/required-its-default.txt（68必需用例）。OpenAPI仍87路径。
- docs/implementation/FULFILLMENT_TC_RECOVERY.md及交付计划/状态/整改记录。

## 未完成

- R13：PICK/SHIP/CANCEL消息闭环；序列号观察/质量/移位及可信来源水位。
- R14：真实fulfillment TM发起、inventory RM服务调用、仓确认可靠消息和出库授权传播；库存消息接序列号stage-only入口、来源转移释放可靠传播；盘点逐序列号持久化登记进度。
- R15：七个catalog handler已实现执行器，serial恢复来源链仍依赖R13/R14；归档只是候选计划，未导出/删除，未编造保留期。
- R22代码与定向证据已完成，未审计/转换共享或生产历史时区；最终全量默认、failure-it、SBOM、远程CI仍待。
- OQ-03、真实WCS、真实容量签署/隔离环境、50 AC业务验收仍有外部工作，不能伪称全部完成。

## 当前问题

- 唯一工作目录：/Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate，分支fix/backend-review-remediation。根用户工作区main f9710ef保持不动。所有exec显式workdir；禁止并发Maven或编译中编辑源码/配置。
- 当前无运行Maven，最新session32714已成功退出。
- 远程f971 main CI34704623423和任务分支34704615403均失败结束，原因健康UP/DOWN竞争已由bb3651e修复，本地已验证，新切片尚未推送。推同ref前核对运行CI，不能取消别人或强推。
- TC候选触发器仅隔离测试安装；应用不迁移TC库。旧attempt缺allocation_tc_binding保持显式待恢复，不按当前集群配置猜测补齐。TC审计enabled默认false，SELECT专用账号，4连接/1s语句/1.5s网络，健康5s缓存。
- TC返回Committed不等于立刻可读终态；真实测试复用候选探针的retryDeadThreshold=1000ms只缩短隔离测试清理窗口，不改生产配置/承诺RTO。
- 当前fulfillment生产Flyway只扫db/migration/fulfillment，历史runtime Inbox V009–V011却在父目录，运行消息接线前需追加兼容迁移补齐，不能改已执行版本或直接换目录造成低版本迁移缺口。
- Inventory ReservationConfirmed原payload只有reservationId/state/xid/branchId，缺attempt/allocation/action/route，消息适配时应生成完整原始事实，不能随恢复猜测。InventoryMessaging目前忽略它，fulfillment尚无Kafka配置。
- 盘点不能直接注入HTTP循环：一行多序列号会限流整体回滚且反复从首个身份重试；必须先持久化每身份远程结果，远程在业务事务外，最终本地调整不再访问网络。现CountService同步端口只在领域测试使用，运行默认拒绝。
- 全局序列号设计还有SHIPPED/SCRAPPED/RETURN_CLAIMED，不能用MISSING替代正常出库；SEALED调拨来源不可作为物理数量；轮回转移需明确新epoch历史，不能清空旧事实硬重用。

## 下一步建议

1. 提交当前已验证TC审计/分配屏障切片。然后补fulfillment运行Inbox迁移、库存确认完整消息及可信消费者，继续真实TM/RM和出库授权消息链。
2. 补R13出库/序列号观察与R14盘点逐身份登记；保持原有分批质检口径，不重新询问是否继续。
3. 全部必要检查通过后推任务分支，fetch/main正常集成并推HEAD:main，检查远程CI；根工作区保持不变。

## 恢复 Prompt

读取CODEX_PROGRESS.md和docs/delivery/wms-v1的DELIVERY_PLAN.md、BACKEND_REMEDIATION.md，在独立集成工作树继续已批准剩余四项。先核对当前Maven/Git状态，复用已有证据；不要把阶段提交、真实TC候选测试或handler存在当作完整业务验收，不要等待“继续”。
