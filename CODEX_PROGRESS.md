# Codex Progress

## 任务目标

完成已批准后端R13/R14/R15/R22，唯一有限验收为docs/delivery/wms-v1/DELIVERY_PLAN.md“剩余整改有限验收”：OUT → WATERMARK → TRANSFER → TC → COMP → FINAL。连续执行，不逐片问继续；不扩项、不操作共享/生产、不使用子Agent。正常Git提交/main推送持续获授权。

## 已完成

- OUT已发布main365a1eb：PICK dd22cd0、SHIP 1dd3d18，含原订单行/桶/epoch、分批身份、独立SHIPPED证明、丢回执重启及审计恢复。main CI34725376702的java和console均success；证据见docs/implementation/SERIAL_OUTBOUND_DESIGN.md。
- 文档17份和控制台已整合入上述main，根控制台工作树不动。
- WATERMARK来源端本地提交695e84f/5af8081：来源原T1屏障、持久200项分页、APPLIED/REJECTED/CANCELLED实际T3、固定数组摘要、受信主体scope和默认关闭开关。尚未发布；详情RECONCILIATION_WATERMARK.md。
- 库存侧已补V043历史屏障和V044证明版本：ledger/posting写入口共享锁防旧时间迟提交；对账、快照创建/历史读取不再相信三个字符串或旧complete位，核验版本1和冻结边界。当前没有生产路径可设置版本1，可信采集器仍未完成。
- 新表随仓迁移，清单55表。发现迁移元数据错误排除DEFAULT_GENERATED普通时间列，已改为仅排除真实计算生成列。双库非空复制测试通过。

## 已修改文件

- 以git diff为准：inventory的InventoryMapper/StockCommandMapper、ReconciliationMapper/StockInternalReconcile、SnapshotMapper/SnapshotExportService及对应XML；WarehouseMigrationStore/MigrationCopyMapper、V043/V044。
- StockCommandIT、StockInternalReconcileIT、SnapshotExportIT、SnapshotHttpIT、WarehouseMigrationIT及仅限下游测试的VerifiedWindowFixture。
- OpenAPI生成描述、required-its-default（119项）、计划/状态/水位/迁移文档及本文件。

## 未完成

- WATERMARK可信HTTP采集器：持久页进度/领取代际、两个来源原command/action/执行ID/posting/数量/截止匹配、正反向集合与摘要核验、可靠恢复及公开请求状态/审计入口，全部核验后才能设置证明版本1。
- TRANSFER公开序列调拨、TC原资源/XID/branch/Fence迁移和终态通知、COMP全局提交后业务补偿、FINAL组合验收及R22结项。
- 外部OQ-03、AC-26现场/WCS、容量/RTO/RPO、生产历史时间不能用夹具或推测代替。

## 当前问题

- 后端工作树：/Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate，分支fix/reconciliation-watermark，HEAD5af8081，远程main365a1eb。695e84f/5af8081仅本地，库存改动未提交。根feat/console-serial-jobs及文档工作树保持不动。
- 来源数据库触发器曾因binlog/SUPER失败，已移除，不再尝试提权。所有旧来源/库存写节点退出后才能启用可信关窗；默认不开启。Compose只用.env.example解析，不读真实凭据。
- 验证：.local/inventory-history-guard-it.log StockCommandIT3通过；.local/inventory-evidence-gate-it.log 对账4/导出2/HTTP1通过，迁移失败。补时间列后因DEFAULT_GENERATED过滤再次失败，已定位修正；.local/inventory-evidence-gate-fixed-it.log 对账4通过；.local/inventory-history-migration-fixed-it.log 08:10:45 BUILD SUCCESS，迁移6通过。不可把失败日志写成成功。
- 契约检查.local/inventory-evidence-contract.log于08:11:00 BUILD SUCCESS；文档检查55篇244链接通过。所有Maven已退出。源码修改与Maven不得同时进行，测试输出写.local，只读关键结果；同类失败两次先诊断再复验。
- 来源证据：.local/source-window-terminal-fixed-it.log（SourceWindowIT3/向量1/契约5）和.local/source-window-t1-regression.log（InboundProtocol2/OutboundProtocol2/OutboundPick7）通过。更早OUT及来源详细证据在正式文档，不重跑无关切片。
- 旧main CI34723887946曾JobCatalogClusterIT 19/20触发失败，日志.local/previous-main-ci-failed.log保留；后续main34725376702已全通过，不重跑碰运气。

## 下一步建议

1. 核对契约检查和git diff，文档结构检查后按完整库存屏障/门禁逻辑单元提交并完成正常分支/main发布，跟踪CI；不称整个WATERMARK完成。
2. 从RECONCILIATION_WATERMARK.md的既定库存采集设计继续：原来源凭证分阶段核对、网络事务外、历史先冻结、领取代际阻止旧回执、12次连续失败后审计重排；缺来源/迟posting保持不完整。每仓活动采集有界，不复制整个历史到每个窗口。
3. 接线前不提供任意置complete的API，不把VerifiedWindowFixture当作真实采集验收。补真实HTTP/JAR、重启/缺失/迟到/重复和非空迁移验证。
4. WATERMARK后继续TRANSFER/TC/COMP/FINAL。总范围不扩大，门禁未通过不能声称整体完成。

## 恢复 Prompt

读取本文件及唯一计划，从WATERMARK未完成部分继续。核对工作树/HEAD/正在运行的Maven，不重做OUT或已发布文档，不等待逐片“继续”。当前技能与授权已读取，沿用有效证据；只在业务信息、危险操作、权限或真实环境阻塞时暂停。
