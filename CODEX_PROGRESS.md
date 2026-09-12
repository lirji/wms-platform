# Codex Progress

## 任务目标

完成已批准的后端 24 项整改（先 R16–20，再按原计划），最终推进 S9/50 AC。当前用户要求先将现有改动提交并合入远程 main，保留未完成整改。

## 已完成

- R01–R12、R16–R21、R23–R24 已实现并有定向验证，详见 docs/delivery/wms-v1/BACKEND_REMEDIATION.md。容量执行器验证不等于真实容量达标。
- R13 已实现真实 Kafka/MySQL 收货双进程闭环、库存投影、受审计重试及积压观测；R15 已接通过期巡检、租约回收、快照续跑、盘点逐行恢复。
- R14 登记服务已接独立数据库、认领/激活/查询 HTTP、服务主体/scope/企业/仓权限、幂等审计及当前状态重查。SerialRegistryHttpIT、SerialRegistryActivateIT 最新定向回归于 2026-09-12 23:35 BUILD SUCCESS（/tmp/wms-registry-authority-retry-it.log）。
- 任务分支 fix/backend-review-remediation，基线 origin/main db02821；既有 13 个任务提交 ee255e0 至 fa6fe64，均未推送。当前正在整理登记服务批次与阶段发布。

## 已修改文件

- 既有整改分布于后端模块、运行库、契约、配置和验证脚本；按逻辑拆分的 13 个提交可查 git log origin/main..HEAD。
- 当前登记服务批次：wms-serial-registry/、deploy/init/mysql-apps/30-serial-registry.sh、compose.yaml、deploy/compose.local.yml、.env.example、OpenAPI 生成器/产物、required-its 脚本/清单及进度文档。

## 未完成

- R13：质检、PUTAWAY/PICK/SHIP/CANCEL、序列号观察完整消息链路。
- R14：库存到登记服务真实有界 HTTP 适配、转移相关入口与恢复、履约 TM/TC 真实协调和终态证据传播。
- R15：serialTransferRecovery、stockInternalReconcile、archivePlanner；对账现有查询有界性不足，归档只能规划，不能未经授权删除。
- R22：固定时间语义、UTC API 与历史 DATETIME 兼容；禁止猜测旧库时区。
- WarehouseMigrationStore.COPY_TABLES 遗漏消息恢复、盘点等仓权威表，需补允许列表及迁移验证。
- 最终组合 profiles、远程 CI、SBOM 最终依赖图复核；实际容量签署/隔离环境、真实 WCS、OQ-03、50 AC。既有 Tomcat/fastjson 安全问题保持记录，不默认为安全验收。

## 当前问题

- 质检结果按每次收货分批，还是整条入库行生效？必要业务问题已询问，未获答；不得按超时默认，可继续独立工作。
- serial-registry 部署需真实 WMS_SERIAL_ALLOWED_SUBJECTS 和独立库凭据；新初始化脚本不自动作用于旧数据卷，禁止重建现有卷。当前没有生产部署。
- 消息功能与人工恢复开关默认关闭；人工恢复须所有 worker 理解 retry_base_epoch 后启用，不能重置 claim_epoch。

## 下一步建议

1. 本轮先完成 Git 阶段发布：核对 /tmp/wms-remediation-publish-verify.log 的完整默认回归（session95885）；不得并发 Maven 或在编译期间修改 Java/XML。同步证据，暂存明确范围，通过契约/文档/必要验证后提交。
2. 正常推送任务分支，在新的干净集成 worktree 基于最新 origin/main 集成并推送 main；检查 CI，不强推、不绕过保护、不取消其他运行。保护 .local/main-integration 和所有其他 worktree。
3. 阶段发布不关闭剩余 4 项。继续 R14/R15 独立工作，收到业务口径后接 R13，随后 R22 和最终全量验收。

## 恢复 Prompt

读取 CODEX_PROGRESS.md、docs/delivery/wms-v1/DELIVERY_PLAN.md 和 BACKEND_REMEDIATION.md，从当前 Git 发布步骤继续；核对真实 Git/CI 状态，不重新规划、不等待“继续”。发布后仍保留 R13/R14/R15/R22 及迁移清单等未完成项，不把阶段合并宣称为 24 项或 50 AC 全部完成。
