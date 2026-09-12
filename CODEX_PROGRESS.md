# Codex Progress

## 任务目标

完成已批准的后端 24 项整改（先 R16–20，再按原计划），最终推进 S9/50 AC。阶段发布已合入 origin/main f9710ef；用户已确认质检按每次收货分批，继续完成剩余 R13/R14/R15/R22。

## 已完成

- R01–R12、R16–R21、R23–R24 已实现并有定向验证，详见 docs/delivery/wms-v1/BACKEND_REMEDIATION.md。容量执行器验证不等于真实容量达标。
- R13 已实现真实 Kafka/MySQL 收货双进程闭环、库存投影、受审计重试及积压观测；R15 已接通过期巡检、租约回收、快照续跑、盘点逐行恢复。
- R14 登记服务已接独立数据库、认领/激活/查询 HTTP、服务主体/scope/企业/仓权限、幂等审计及当前状态重查。SerialRegistryHttpIT、SerialRegistryActivateIT 最新定向回归于 2026-09-12 23:35 BUILD SUCCESS（/tmp/wms-registry-authority-retry-it.log）。
- 任务分支 fix/backend-review-remediation，基线 origin/main db02821；14 个任务提交 ee255e0 至 7e258d0 已推送 origin/fix/backend-review-remediation。独立集成工作树 .local/backend-remediation-integrate 已从 db02821 快进到 7e258d0，无冲突。

## 已修改文件

- R13 分批质检已提交 26c4472；当前分批上架/批次列表/控制台切片修改 InboundReceiptService、ReceiptQuality Mapper/Service、V013/V014、StockCommandService/消息适配/批次库存额度、SourceCommandContextStore、HTTP DTO/契约、双进程测试及入库/PDA 页面。

- 既有整改分布于后端模块、运行库、契约、配置和验证脚本；14 个提交可查 git log db02821..7e258d0。
- 当前登记服务批次：wms-serial-registry/、deploy/init/mysql-apps/30-serial-registry.sh、compose.yaml、deploy/compose.local.yml、.env.example、OpenAPI 生成器/产物、required-its 脚本/清单及进度文档。

## 未完成

- R13：分批质检首轮双进程真实链路通过，等值小数重放补验已通过；PUTAWAY 与批次列表已双进程验证，控制台 typecheck/33 测试/build 通过；PICK/SHIP/CANCEL、序列号观察链路仍待。
- R14：库存到登记服务真实有界 HTTP 适配、转移相关入口与恢复、履约 TM/TC 真实协调和终态证据传播。
- R15：serialTransferRecovery、stockInternalReconcile、archivePlanner；对账现有查询有界性不足，归档只能规划，不能未经授权删除。
- R22：固定时间语义、UTC API 与历史 DATETIME 兼容；禁止猜测旧库时区。
- WarehouseMigrationStore.COPY_TABLES 遗漏消息恢复、盘点等仓权威表，需补允许列表及迁移验证。
- 最终组合 profiles、远程 CI、SBOM 最终依赖图复核；实际容量签署/隔离环境、真实 WCS、OQ-03、50 AC。既有 Tomcat/fastjson 安全问题保持记录，不默认为安全验收。

## 当前问题

- 阶段发布验证：独立 worktree 的完整默认 verify 于 2026-09-13 00:12:57 成功，99 类/200 用例，无失败、错误或跳过；55 必需用例、四进程 smoke、Python 4 测试、文档/契约/Compose、控制台类型检查/33 测试/构建均通过。
- 分支 CI 34703330446 控制台成功，Java 暴露并发测试固定 WH-A 的错误假设。已按实际获胜仓重放，并断言其他仓拒绝；定向真库验证于 00:13:38 成功。新提交远程 CI 待核验，不能把旧 CI 失败写成通过。

- 用户已确认：质检按收货分批。原收货 commandId 是批次权威引用；质量结论按该批累计数量和递增版本处理，不能按整条入库行分摊。
- serial-registry 部署需真实 WMS_SERIAL_ALLOWED_SUBJECTS 和独立库凭据；新初始化脚本不自动作用于旧数据卷，禁止重建现有卷。当前没有生产部署。
- 消息功能与人工恢复开关默认关闭；人工恢复须所有 worker 理解 retry_base_epoch 后启用，不能重置 claim_epoch。

## 下一步建议

1. 当前没有运行 Maven。分批质检已提交 26c4472；分批上架 /tmp/wms-batch-putaway-it.log 于 00:35:29、批次列表 /tmp/wms-batch-list-it.log 于 00:37:32 BUILD SUCCESS。控制台 npm typecheck/33 测试/build 已通过，正在提交本切片。仅在 .local/backend-remediation-integrate 的 fix/backend-review-remediation 工作，主目录 main 保持不动。
2. 随后补 R13 出库消息及 R14 真实登记/TM/TC 适配，接 R15 三任务和 R22 时间兼容；补迁移数据清单，更新契约与有意义的集成验证。
3. 继续观察 main CI 34704623423；不能取消其他运行，不并发 Maven 写同一 target，不在 Maven 编译中修改 Java/XML。完成各逻辑单元后更新进度并提交，最终正常合并推送。

## 恢复 Prompt

读取 CODEX_PROGRESS.md、docs/delivery/wms-v1/DELIVERY_PLAN.md 和 BACKEND_REMEDIATION.md，按已确认的分批质检口径继续剩余四项；核对真实 Git/CI 状态，不重新规划、不等待“继续”。发布后仍保留 R13/R14/R15/R22 及迁移清单等未完成项，不把阶段合并宣称为 24 项或 50 AC 全部完成。
