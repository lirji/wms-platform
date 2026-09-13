# Codex Progress

## 任务目标

完成已批准R13/R14/R15/R22。唯一有限验收：docs/delivery/wms-v1/DELIVERY_PLAN.md，OUT → WATERMARK → TRANSFER → TC → COMP → FINAL。连续执行、正常Git发布已授权，不扩项、不操作共享或生产、不使用子Agent。

## 已完成

- OUT已发布main 365a1eb，CI34725376702成功。
- WATERMARK已发布main 051a7eb（包含2e7451c及控制台2c31c54），此前main CI34728530641成功，新CI34729813580仍运行。真实三服务水位、缺T3拒绝导出、库存重启检查点和非空迁移通过，证据见RECONCILIATION_WATERMARK.md。
- TRANSFER实现并定向验证完成，尚未提交发布。09:21:01 .local/public-serial-transfer-preparation-proof-it.log BUILD SUCCESS：真实履约/库存/登记JAR、三MySQL、Kafka、XXL，公开发出、权限拒绝、源登记成功后本地恢复写失败、库存重启、目的两批收货及重复不重复扣增。登记1及HTTP客户端5项同批通过。
- 09:23:31 .local/public-serial-transfer-migration-it.log BUILD SUCCESS：57表非空迁移6、两库核心事务1、真实登记进程恢复1；必需IT清单129项、文档结构检查通过。不是全仓最新回归。

## 已修改文件

- 当前git diff为TRANSFER：共享SerialTransferCommand；履约V020原命令/SN成员/序列模式及3个公开入口；库存V046目的仓、V047原命令、原恢复完成回执；既有Kafka Inbox/Outbox与XXL接线。
- 登记准备协议可选X-Wms-Serial-Prepare-Proof: 1，提供不可变历史准备凭证；库存重试不依赖当前身份仍是TRANSFER_PREPARED。源流水同时写查询投影Outbox。
- 公开接口默认关闭；OpenAPI99路径113操作、迁移清单57、必需IT129；相关测试、配置和文档随本批提交。
- TC_RESOURCE_MIGRATION.md是下一切片设计草稿，尚未实施，不混入TRANSFER提交。

## 未完成

- 提交TRANSFER，等待main正在运行的CI完成后正常发布，不能推同ref取消旧verify。
- TC：可靠终态通知、原资源/XID/branch/Fence迁移、未终结拒绝切流与真实回调恢复。
- COMP：全局提交后业务补偿、已知未执行额度仅一次、实物未知保留处理中与持久审计。
- FINAL：组合/default verify、profiles、smoke、文档、main/CI、R22结项。
- OQ-03/AC-26现场/WCS、容量/RTO/RPO及生产历史时间是外部边界，不能补造通过。

## 当前问题

- 工作目录 /Users/liruijun/personal/LLM/wms-platform/.local/backend-remediation-integrate；分支feat/public-serial-transfer，HEAD051a7eb。根用户工作树feat/console-shadcn-dialog及其他工作树不动。
- Maven74674已BUILD SUCCESS，未再启动构建；源码/测试与Maven不可重叠修改。
- 首次公开进程测试因prepare返回当前IN_TRANSIT状态而恢复失败，已改核验历史原凭证并真实重启通过。此前测试夹具路径、MyBatis Number绑定、RSAKey导入失败均保留日志，不计为成功。
- 现有TC迁移对任何inventory_tcc_intent阻断；Fence无企业/仓列，未复制。现有履约审计读取有来源绑定，但未可靠通知库存。下一片先完成这些具体边界，不能仅删除门禁。
- 发布工作树 .local/watermark-main-publish 保持干净可复用；无生产部署授权。

## 下一步建议

1. 检查暂存差异并提交TRANSFER，核验生成契约一致；main CI完成后发布，不重跑已通过的定向测试。
2. 按TC_RESOURCE_MIGRATION设计恢复TC切片，再COMP，最后统一组合验证。
3. 持续更新本文件及唯一交付状态；不把完成一片当作整体完成。

## 恢复 Prompt

读取CODEX_PROGRESS.md与唯一计划，从首个未完成步骤继续。核对Git、工作树、活动Maven和最新日志，复用有效证据，不重复OUT/WATERMARK，不等待逐片确认。只在必要业务信息、危险操作、权限或真实环境阻塞时暂停。
