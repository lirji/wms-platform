# S0技术探针QA报告

## 环境与范围

2026-09-10，macOS arm64、Microsoft JDK21.0.11、Docker29.7.2；MySQL8.4.11与Seata2.6.0均使用Testcontainers专属容器。未操作共享数据库/消息/TC或生产环境。当前仅启动骨架及技术探针，业务AC整体仍planned。

## 实际验证

| 用例/命令 | 实际结果 | 证明范围 |
| --- | --- | --- |
| `./mvnw -B -ntp verify` | 构建成功 | Maven多模块及三服务可编译打包 |
| `python3 scripts/smoke-services.py` | 三进程健康UP，业务路径401/403 | 独立进程与默认拒绝；未接业务数据库 |
| `./mvnw -B -ntp -Pwarehouse-it verify` | 6项，失败0、错误0、跳过0 | 多SKU同仓回滚；200并发最多100件占用；缺仓/未知仓拒写及账号隔离；Boot-MyBatis分片装配；Fence原子回滚；重复Confirm和空回滚/晚Try |
| `./mvnw -B -ntp -Ptc-it verify` | 1项，失败0、错误0、跳过0 | TC提交返回Committed、回滚返回Rollbacked；清理后均查询为Finished |
| Python/POM/CI YAML语法 | 通过 | 本地语法；远程CI未运行 |

## 修复与限制

- JDBC基础依赖缺少分片/MySQL/authority SPI：显式加入同版插件后修复。
- Seata传递ANTLR4.8与ShardingSphere生成版本4.13.2冲突：父POM固定4.13.2后SQL测试通过；AT路径不启用、不宣称兼容。
- Fence测试直接绑定一个物理数据源；不等同于多仓RM动态路由和真实TC二阶段故障恢复。
- TC探针揭示现有getStatus恢复路径不足，不能将探针成功当作EG-02完成。还需终态证据可靠保存/读取与TM宕机窗口验证。
- Kafka/XXL实际联调、启动CAS/RPC故障、全链路、身份/序列号、外部设备/UI/对账/容量均未验收。

## 结论

本轮技术探针通过；整体S0及项目验收未完成，状态in-progress。测试断言不能降级成允许失败/静默跳过来绕过后续门禁。
