# WMS 实施状态

## 当前阶段与授权

- 状态：in-progress；阶段：S0工程与兼容验证。
- 用户已批准补齐执行门禁并推进业务开发，沿用[唯一计划](DELIVERY_PLAN.md)。
- 仅操作wms-platform和明确隔离的本地测试资源；不部署生产，不修改共享组件配置，前端交Cursor。
- 分支：feat/wms-s0-foundation；origin为GitHub；本地未有初始提交，远程查询未返回分支。首次main创建尚待处理。

## 门禁

| 门禁 | 状态 | 证据/下一步 |
| --- | --- | --- |
| EG-01 工程/CI | running | 三服务构建/smoke、GitHub CI配置已完成；远程CI未运行 |
| EG-02 TC组合/唯一TM | running | 6项数据库/分片/Fence探针及1项真实TC探针通过；终态证据适配/两仓RM及TM决定未闭合 |
| EG-03 业务决定 | pending | 已询问认证和序列号范围；相关实现等待回答 |
| EG-04 完整闭环 | pending | S5退出必选，尚未实现 |
| EG-05 外部与非功能 | pending | S8/S9执行 |
| Git发布 | pending | 空远程，首次main创建需明确授权；不影响本地实现 |

## 已完成

- 原10份设计与50项planned验收保留，四项幂等补充任务已并入各阶段。
- 补充S0终态证据POC、TM决定及S5完整闭环门禁。
- 核查本机Java/Maven/Docker，dev-infra已有共享MySQL/Kafka等；故障测试使用独立资源。

## 下一步

生成S0工程、候选版本锁与隔离验证；实际命令和结论随切片更新。前期DOCUMENT_CHECK仅是文档结构证据，不代表本次业务验证。

## 本轮实际实现与验证

- 新增五模块Maven工程，三个服务仅开放健康接口，其余路径拒绝；业务代码尚未进入S1。
- 完成6项warehouse-it技术探针及1项tc-it终态能力探针；三独立进程smoke通过。详见[QA报告](QA_REPORT.md)、[版本记录](../../implementation/VERSION_LOCK.md)及[本地手册](../../implementation/S0_RUNBOOK.md)。
- 修复实际ANTLR4.8/4.13.2冲突和ShardingSphere插件装配；S0探针采用MySQL8.4.11独立容器。
- TC提交/回滚清理后均为Finished，不能作为恢复成功证据；这不是可以忽略的错误，EG-02仍未完成。
- 用户必要业务决定仍待回复：TM归属、序列号范围、认证接入。原有结构检查证据已更新，但全部业务AC仍planned。
- 本地工作可作为独立S0准备切片提交；不宣称S0全部通过或全项目交付。远程为空，首次main创建尚待授权，生产部署无授权。
