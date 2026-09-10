# S0技术探针QA报告

## 环境与范围

2026-09-10，macOS arm64、Microsoft JDK21.0.11、Docker29.7.2；MySQL8.4.11与Seata2.6.0均使用Testcontainers专属容器。未操作共享数据库/消息/TC或生产环境。当前仅启动骨架及技术探针，业务AC整体仍planned。

## 实际验证

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `./mvnw -B -ntp verify` | 构建成功 | Maven多模块及三服务可编译打包 |
| `python3 scripts/smoke-services.py` | 三进程健康UP，业务路径401/403 | 独立进程与默认拒绝；未接业务数据库 |
| `./mvnw -B -ntp -Pwarehouse-it verify` | 6项，失败0、错误0、跳过0 | 多SKU同仓回滚；200并发最多100件占用；缺仓/未知仓拒写及账号隔离；Boot-MyBatis分片装配；Fence原子回滚；重复Confirm和空回滚/晚Try |
| `./mvnw -B -ntp -Ptc-it verify` | 2项，失败0、错误0、跳过0 | 文件模式Finished限制；DB审计区分提交/回滚、重启后查询恢复、审计拒写原子失败后TC恢复 |
| `./mvnw -B -ntp -Ptc-it -Dit.test=TcDatabaseEvidenceIT verify` | 最终修改后1项，失败0、错误0、跳过0 | 加入重启端口就绪等待后定向复验 |
| Python/POM/CI YAML语法 | 通过 | 本地语法；远程CI未运行 |

## 修复与限制

- JDBC基础依赖缺少分片/MySQL/authority SPI：显式加入同版插件后修复。
- Seata传递ANTLR4.8与ShardingSphere生成版本4.13.2冲突：父POM固定4.13.2后SQL测试通过；AT路径不启用、不宣称兼容。
- Fence测试直接绑定一个物理数据源；不等同于多仓RM动态路由和真实TC二阶段故障恢复。
- TC探针揭示现有getStatus恢复路径不足，不能将探针成功当作EG-02完成。还需终态证据可靠保存/读取与TM宕机窗口验证。
- Kafka/XXL实际联调、启动CAS/RPC故障、全链路、身份/序列号、外部设备/UI/对账/容量均未验收。

## 结论

本轮技术探针通过；整体S0及项目验收未完成，状态in-progress。测试断言不能降级成允许失败/静默跳过来绕过后续门禁。

## S0-09终态审计切片

真实TC+MySQL证明候选审计可保留提交9/回滚11；TC会话清理且重启恢复查询后证据仍在。注入审计INSERT失败3.5秒后，TC仍保留非终态会话且审计零记录；撤销故障后TC自行落盘提交证据并清理。没有业务RM，不能证明业务放行已经实现或EG-02完成。详见[候选方案](../../implementation/TC_TERMINAL_EVIDENCE.md)。

初次运行失败原因分别是Docker自定义网络地址池耗尽、DB模式默认回滚恢复阈值超过30秒探针窗口。改用独立容器在默认bridge上的IP直连，并仅在测试将retryDeadThreshold设为1000毫秒后通过。未删除共享网络、未调整共享TC。最终源码定向复验37.970秒；完整tc-it前序运行77秒。耗时是本机测试值，不是业务SLO。

SQL注释检查器修复多行表选项误报和反引号字段漏检；正/负例检查通过。结构检查20份文档、51条仓库链接、50项AC、64个唯一任务，仍只证明结构。

## S0-05a单RM双仓与TC在途重启补充

最终命令`./mvnw -B -ntp -Ptc-it -Dit.test=TcDatabaseEvidenceIT verify`通过：1项，失败0、错误0、跳过0，构建约99秒；此@Test内追加了TwoWarehouseTccProbe，不增加虚假的测试数量。

- 一个真实RM客户端注册A/B两个资源，分别使用独立库/账号；TC持久化上下文驱动取连接路由，MyBatis与Fence共享Spring事务。
- A确认成功、B写效果后抛异常：A效果1，B效果0且Fence仍TRIED，TC成功审计0。
- 在这个窗口重启测试TC；客户端自行重连后，B完成、A仍一次，两个Fence均COMMITTED，审计最终为提交9。
- 后续新事务两仓各Try30再Cancel，仅释放本次30，原已确认30保持；各一次CANCEL且Fence为ROLLBACKED。
- 缺少路由上下文取连接直接失败；未配置默认仓。

初次在途重启验证30秒超时，源码定位原生客户端首次重连调度延迟60秒、后续10秒；仅把对应断言窗口设90秒后通过，没有强制重连或放宽业务断言。最终日志观察RM重新注册与后续真实TC回调，不能把这个耗时当作已满足业务RTO。

限制：TM/RM同一测试JVM；未用正式HTTP/代理Try；未验证两个RM独立进程、RM/TM宕机、ShardingSphere接入该Fence事务、启动CAS或业务放行Outbox。既有warehouse-it与文件TC回归未改语义，复用此前证据；最终定向命令覆盖本次变化。整体EG-02仍running。
