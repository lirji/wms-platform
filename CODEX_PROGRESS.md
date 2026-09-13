# Codex Progress

## 任务目标

完成已批准R13/R14/R15/R22。唯一有限验收：docs/delivery/wms-v1/DELIVERY_PLAN.md，OUT → WATERMARK → TRANSFER → TC → COMP → FINAL。持续执行、正常Git发布已授权；不扩项，不操作共享或生产，不使用子Agent。

## 已完成

- OUT已发布main365a1eb、CI成功。WATERMARK已发布051a7eb，main CI34729813580全部成功。
- TRANSFER7659d34已发布任务分支和main，祖先核验通过。真实三JAR/三MySQL/Kafka/XXL、源恢复最终写失败、库存重启、两批目的收货、重复与非空57表迁移通过。新main CI34731081335运行中，不能推同ref取消verify。详见SERIAL_PUBLIC_TRANSFER.md。
- TC本地实现与定向验证完成，待提交发布。09:41:10 .local/tcc-original-application-replay-it.log BUILD SUCCESS：自动执行器5、真实TC/Kafka/迁移与原应用回调重放1、仓迁移6。
- 原B进程停止，迁移目标C重启，TC重启后重放此前真实原会话；目标09:40:17收到原B的XID/branch/资源回调并返回PhaseTwo_Committed。原Fence逐字段不变，业务效果一次。TC会话重放为明确隔离故障夹具，不是生产灾备演练。
- TC审计恢复4、原生RM数据库2、两库终态迁移1已通过。09:43:08 .local/tcc-terminal-final-guards-it.log证明缺原RM来源的预占仍阻断迁移；09:43:56 .local/tcc-notice-barrier-recovery-it.log恢复2通过，通知数量不能掩盖缺失授权。必需IT131、Compose静态与文档结构检查通过。

## 已修改文件

- git diff为TC切片：TcTerminalNotice、履约执行器/恢复扫描的逐仓Outbox、库存TcTerminalService及V048原证明、Inbox/topic接线。
- WarehouseMigrationStore为58仓表另精确复制原Fence；RuntimeTccCoordinator历史回调只读原终态；SeataRmDriver使用官方RegisterRM为原应用/资源登记别名并逐连接恢复；InventoryRmConfiguration显式等待Flyway。
- 相关真实数据库/TC/进程测试、必需清单、专题与交付文档、Kafka初始化主题。

## 未完成

- 提交TC至独立分支；main7659d34的CI完成后从干净发布树正常合入main。
- COMP：已提交TCC的取消业务补偿；已知未执行额度仅一次；实物未知保留处理中；持久重试审计。
- FINAL：组合/default verify、profiles、smoke、文档、main/CI、R22范围结项。
- OQ-03/AC-26现场/WCS、容量/RTO/RPO及生产历史时间保持外部边界，不补造通过。

## 当前问题

- 工作目录 /Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate；分支feat/tcc-terminal-migration，基于7659d34。根用户工作树feat/console-shadcn-dialog及其他工作树不动。
- Maven99914已BUILD SUCCESS，暂无活动构建。禁止在Maven运行时编辑源码/测试。
- 干净发布树.local/watermark-main-publish当前7659d34，可复用；正常main发布已授权，不包含生产部署。
- 重要TC来源限制：Seata2.6.0的TCC不会跨application回退；仅注册旧resource不足，当前已用原应用别名及真实TC重放修正验证。不要删此兼容逻辑或把新应用就绪当成回调证据。
- 首次迁移测试曾错传JdbcTemplate参数、遗漏UTC来源初始化，已修正；通知计数测试已加强为真实缺失授权场景。失败日志保留，不能算通过。
- COMP预研：FulfillmentService.requestCancel仅受理；AllocationExecutionWorker的原COMMIT意图不翻转，晚取消停在CANCEL_REQUIRES_COMPENSATION。OutboundAuthorizationService/OutboundOrderService负责执行门禁；已有postOutboundReservation(CANCEL)可释放原未拣行，但必须先阻断迟到授权/新派工并处理实物未知，不可直接调用legacy releaseUnpicked或TCC Cancel。

## 下一步建议

1. 审核暂存TC差异并提交推任务分支；已有main CI未结束时不要推main。
2. 从TC提交建立COMP分支，按唯一有限验收实现取消补偿，不扩展退货业务。
3. 各片发布后做一次最终组合与必需门禁；复用未变证据，不重复OUT/WATERMARK/TRANSFER。

## 恢复 Prompt

读取CODEX_PROGRESS.md与唯一计划，从首个未完成步骤继续。核对Git、工作树、活动Maven和最新日志；保留用户其他工作树，不等待逐片确认。只在必要业务信息、危险操作、权限或真实环境阻塞时暂停。
