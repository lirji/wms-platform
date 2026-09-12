# Codex Progress

## 任务目标

完成已批准的后端 24 项整改（先 R16–20，再按原计划），最终推进 S9/50 AC。当前用户要求先将现有改动提交并合入远程 main，保留未完成整改。

## 已完成

- R01–R12、R16–R21、R23–R24 已实现并有定向验证，详见 docs/delivery/wms-v1/BACKEND_REMEDIATION.md。容量执行器验证不等于真实容量达标。
- R13 已实现真实 Kafka/MySQL 收货双进程闭环、库存投影、受审计重试及积压观测；R15 已接通过期巡检、租约回收、快照续跑、盘点逐行恢复。
- R14 登记服务已接独立数据库、认领/激活/查询 HTTP、服务主体/scope/企业/仓权限、幂等审计及当前状态重查。SerialRegistryHttpIT、SerialRegistryActivateIT 最新定向回归于 2026-09-12 23:35 BUILD SUCCESS（/tmp/wms-registry-authority-retry-it.log）。
- 任务分支 fix/backend-review-remediation，基线 origin/main db02821；14 个任务提交 ee255e0 至 7e258d0 已推送 origin/fix/backend-review-remediation。独立集成工作树 .local/backend-remediation-integrate 已从 db02821 快进到 7e258d0，无冲突。

## 已修改文件

- 既有整改分布于后端模块、运行库、契约、配置和验证脚本；14 个提交可查 git log db02821..7e258d0。
- 当前登记服务批次：wms-serial-registry/、deploy/init/mysql-apps/30-serial-registry.sh、compose.yaml、deploy/compose.local.yml、.env.example、OpenAPI 生成器/产物、required-its 脚本/清单及进度文档。

## 未完成

- R13：质检、PUTAWAY/PICK/SHIP/CANCEL、序列号观察完整消息链路。
- R14：库存到登记服务真实有界 HTTP 适配、转移相关入口与恢复、履约 TM/TC 真实协调和终态证据传播。
- R15：serialTransferRecovery、stockInternalReconcile、archivePlanner；对账现有查询有界性不足，归档只能规划，不能未经授权删除。
- R22：固定时间语义、UTC API 与历史 DATETIME 兼容；禁止猜测旧库时区。
- WarehouseMigrationStore.COPY_TABLES 遗漏消息恢复、盘点等仓权威表，需补允许列表及迁移验证。
- 最终组合 profiles、远程 CI、SBOM 最终依赖图复核；实际容量签署/隔离环境、真实 WCS、OQ-03、50 AC。既有 Tomcat/fastjson 安全问题保持记录，不默认为安全验收。

## 当前问题

- 阶段发布验证：独立 worktree 的完整默认 verify 于 2026-09-13 00:12:57 成功，99 类/200 用例，无失败、错误或跳过；55 必需用例、四进程 smoke、Python 4 测试、文档/契约/Compose、控制台类型检查/33 测试/构建均通过。
- 分支 CI 34703330446 控制台成功，Java 暴露并发测试固定 WH-A 的错误假设。已按实际获胜仓重放，并断言其他仓拒绝；定向真库验证于 00:13:38 成功。新提交远程 CI 待核验，不能把旧 CI 失败写成通过。

- 质检结果按每次收货分批，还是整条入库行生效？必要业务问题已询问，未获答；不得按超时默认，可继续独立工作。
- serial-registry 部署需真实 WMS_SERIAL_ALLOWED_SUBJECTS 和独立库凭据；新初始化脚本不自动作用于旧数据卷，禁止重建现有卷。当前没有生产部署。
- 消息功能与人工恢复开关默认关闭；人工恢复须所有 worker 理解 retry_base_epoch 后启用，不能重置 claim_epoch。

## 下一步建议

1. 当前任务分支位于 .local/backend-remediation-integrate；主工作目录被外部切换至旧 main，保持原状，不能覆盖未跟踪文件。14 个整改提交已远程推送，补充并发测试修正与验证记录构成本次阶段发布。正常推送任务分支并将验证提交快进到 origin/main；以 git merge-base --is-ancestor 7e258d0 origin/main 和最新修正提交检查实际发布结果，不强推、不取消运行、不绕过保护。
2. 核对新提交的 GitHub verify：默认本地回归通过，远程及 warehouse-it/tc-it/failure-it 全组合仍需跟踪。日志 /tmp/wms-remediation-isolated-verify.log、/tmp/wms-serial-race-publish-it.log，持久证据见 BACKEND_REMEDIATION.md 阶段发布验证。所有本地 Maven 已结束。
3. 阶段发布不关闭剩余 4 项。继续 R14/R15 独立工作，收到业务口径后接 R13，随后 R22 和迁移清单、最终全量验收。

## 恢复 Prompt

读取 CODEX_PROGRESS.md、docs/delivery/wms-v1/DELIVERY_PLAN.md 和 BACKEND_REMEDIATION.md，从当前 Git 发布步骤继续；核对真实 Git/CI 状态，不重新规划、不等待“继续”。发布后仍保留 R13/R14/R15/R22 及迁移清单等未完成项，不把阶段合并宣称为 24 项或 50 AC 全部完成。
