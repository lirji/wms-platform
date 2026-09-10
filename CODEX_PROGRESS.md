# Codex Progress

## 任务目标

用户已批准“先补齐几个执行门禁后推进业务开发”。沿用v0.4独立三服务设计、Seata TCC跨仓预占及其他本地事务+可靠消息，执行唯一计划`docs/delivery/wms-v1/DELIVERY_PLAN.md`。当前S0，未完成全项目。

## 已完成

- 补EG-01..05执行门禁：唯一TM、TC终态证据、业务决定截止阶段、S5完整闭环与后续外部验收；幂等任务已并入各阶段。
- 本地任务分支`feat/wms-s0-foundation`；origin为GitHub lirji/wms-platform，远程ls-remote无分支，本地实现提交5d986dd；.idea保留并忽略。
- 创建Maven Wrapper3.9.12/父POM、contract骨架、inbound/outbound/inventory独立服务入口及默认拒绝业务访问的配置。
- 三进程smoke通过：健康UP、业务路径拒绝。只证明启动，不是业务验收。
- `warehouse-it`六项真实MySQL/分片/Fence探针通过（0失败/错误/跳过）；覆盖同仓回滚、200次并发预占最多100次成功、缺仓/未知仓拒写、账号隔离、Fence局部原子与重复/空回滚。
- 修复实际依赖冲突：ShardingSphere5.5.3需显式插件，ANTLR runtime固定4.13.2替换Seata传递4.8；MySQL测试镜像8.4.11复用本机版本、独立容器。
- 新增GitHub CI、本地运行手册及候选版本记录；尚未远程执行CI。

## 已修改文件

- `pom.xml`、`.mvn/wrapper/maven-wrapper.properties`、`mvnw`/`mvnw.cmd`、`.gitignore`。
- `wms-contract`、`wms-inbound`、`wms-outbound`、`wms-inventory`、`wms-test-support`。
- `scripts/smoke-services.py`、`.github/workflows/verify.yml`。
- `README.md`、唯一计划/状态及`docs/implementation/VERSION_LOCK.md`、`S0_RUNBOOK.md`。
- 原设计文档均未提交，属于前期本任务成果；`.idea`不提交。

## 未完成

- `tc-it`已通过1项，真实TC提交/回滚清理后都查询为Finished；终态持久化适配仍未完成，不能据此放行EG-02。
- EG-02完整TC+两仓RM+动态Fence路由、全局终态证据持久化、启动CAS/RPC重试、Kafka/XXL实际验证、许可证/漏洞与镜像锁等尚未完成。
- 全部业务AC仍planned，仅上述S0子项有技术证据；不能进入跨仓业务实现并宣称S0已通过。
- 业务开发S1..S9、真实对账/设备/UI/容量和恢复均未完成。
- 本轮源码自审、文档/SQL注释/配置语法检查完成；本地提交5d986dd已完成；首次main授权、Git发布/远程CI仍待完成。

## 当前问题

- 用户业务决定已通过异步工具询问，尚未收到：唯一TM（建议wms-fulfillment）、序列号唯一范围（建议企业+SKU+serial）、OIDC接入；保持相关门禁pending。
- Seata2.6.0 DefaultCore源码表明getStatus在会话清理后返回Finished，无法独自分辨提交/回滚；真实探针已证实。需要可恢复的终态证据方案，不能把Finished当成功。
- 原生Fence直接数据源验证通过，不等同于与ShardingSphere多数据源动态路由组合通过。
- 远程为空，task-git-delivery要求不擅自创建远程main；已提交具体可审查成果5d986dd，并通过异步工具询问首次main创建授权，等待回答。已有持续授权仍适用普通提交推送。

## 下一步建议

1. 阅读QA_REPORT与VERSION_LOCK，TC查询限制已实测；继续终态证据适配、两仓RM与启动CAS/RPC故障验证，不重复已通过的局部探针。
2. 完成独立可推进的S0任务；用户决定到达后更新OQ和实现门禁，不替换整个设计。
3. 按必要检查结果分批提交。若S0依赖/决定确实阻塞，记录可恢复状态与具体缺口，不假称全交付。

## 恢复 Prompt

请读取CODEX_PROGRESS.md和唯一DELIVERY_PLAN/DELIVERY_STATUS，继续已授权实施。先检查git工作树及已保存的QA证据，当前在feat/wms-s0-foundation、已有初始实现提交5d986dd。保护.idea，沿用现有代码和证据；Seata全局终态与用户必要业务决定尚未闭合，不能将7项局部探针当完整S0或业务验收。不要重新规划全部项目，不要求反复输入继续。
