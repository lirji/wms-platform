# S0候选版本与实际证据

## 状态

本文件记录已解析和测试的候选组合，不是生产版本锁定结论。EG-02尚未通过：TC全局终态持久化证据、真实两仓RM恢复与完整装配仍待验证。不得仅凭本文件存在将S0标完成。

| 组件 | 本轮版本 | 已验证范围 |
| --- | --- | --- |
| JDK | Microsoft OpenJDK 21.0.11+10-LTS | 本机编译/进程启动 |
| Maven / Wrapper | 3.9.12 / 3.3.4 only-script | Wrapper生成并用于构建；分发URL为Maven Central |
| Spring Boot | 4.1.1 | 三个独立进程启动，健康端点及默认拒绝业务访问 |
| MyBatis starter | 4.1.0 | 依赖可解析；MyBatis原生会话+Mapper真实MySQL条件更新；Boot的MyBatis自动配置连接ShardingSphere通过 |
| MyBatis core | 3.5.19 | 与 starter 4.1.0 BOM 一致；inventory 主数据 Mapper 注解编译。进程仍不启用 JDBC/Flyway 自动配置 |
| ShardingSphere JDBC及插件 | 5.5.3 | 双物理数据源、确切仓路由、缺仓拒写、本地回滚 |
| Seata client | 2.6.0 | 原生SpringFenceHandler单物理数据源事务、二阶段重复/空回滚；不是两仓全局事务验证 |
| Seata Server | 2.6.0 | 真实TC提交/回滚及清理后查询探针通过；两者清理后均为Finished，不能直接作为恢复成功证据 |
| MySQL | mysql:8.4.11 | 复用dev-infra现有镜像版本创建专属临时容器，不操作共享实例；本地 compose 三实例同标签 |
| Testcontainers | 2.0.5 | Docker29.7.2下真实启动MySQL，Docker不可用直接失败 |
| ANTLR runtime | 4.13.2 | 覆盖Seata传递的4.8，修复ShardingSphere解析器ATN版本冲突 |
| XXL-JOB core | 3.4.2 | 依赖可解析；warehouse-it 证明 handler 清理后无当前全局事务；`XxlAdminTriggerIT` 经官方 admin 3.4.2 真实触发 BEAN handler，执行器未持有 TCC。不是集群/分片验收 |
| XXL-JOB admin | xuxueli/xxl-job-admin:3.4.2 | 隔离 compose 可启动；warehouse-it 对官方镜像做 `/auth/doLogin` + `/jobinfo/trigger`。官方镜像 linux/amd64，本机 arm64 经模拟。不是集群/分片/生产调度 |
| Kafka broker | apache/kafka:3.8.0 | 与 dev-infra 同标签；隔离 compose 可启动；warehouse-it 用同标签 Testcontainers 验证生产/消费且消费不 bind XID |
| Kafka client | kafka-clients 3.8.0 | 与 broker 对齐；仅测试探针使用，未做事务消息/生产 Outbox |
| Redis | redis:7-alpine | 与 dev-infra 同标签；仅本地缓存编排，未做业务缓存验收 |

## 关键装配决定

- ShardingSphere 5.5.3的JDBC基础模块不自动提供全部插件；显式加入sharding-core、MySQL parser/connector、standalone memory及authority-simple。探针使用内存元数据仓库，库存数据是持久化MySQL；生产元数据治理另需验证。
- ANTLR冲突由实际SQL测试发现，不能只编译验证兼容。统一4.13.2用于本项目TCC组合；Seata AT SQL解析路径不启用也未验收，不宣称该修订适用AT。
- 首批原生Fence验证使用固定物理DataSource，后补单RM双仓ContextDataSource路由及TC在途重启通过；后续双独立RM各自单Cell经ShardingSphere/Fence组合已有探针；启动CAS与真实branchRegister重复Try所有权夹具已通过；单RM跨物理库原子性未承诺，不能把各自测试通过当作组合通过。
- 服务默认不接数据库、OIDC issuer 为空时拒绝业务 HTTP；健康 UP 只代表进程。配置 `WMS_INVENTORY_JDBC_URL` 后 inventory 才 Flyway 并提供主数据只读查询。CI 用 Testcontainers 覆盖迁移、种子复跑与 JWT 仓隔离，不要求现场 Casdoor。
- ordinary开发组件优先dev-infra；WMS 隔离本地栈见 `deploy/compose.local.yml`，不加入共享网络。故障探针使用Testcontainers或另起 `COMPOSE_PROJECT_NAME=wms-fault` 创建/回收自己的资源，无权重启或清理共享组件。

## 来源与限制

候选版本从Maven Central对应artifact元数据核对；源码按Apache Seata v2.6.0读取。官方资料：[Seata发布历史](https://seata.apache.org/release-history/seata-server/)、[ShardingSphere 5.5.3](https://shardingsphere.apache.org/document/5.5.3/en/overview/)、[Seata DefaultCore源码](https://github.com/apache/incubator-seata/blob/v2.6.0/server/src/main/java/org/apache/seata/server/coordinator/DefaultCore.java)。DefaultCore.getStatus在会话不存在时返回Finished，与真实TC探针结果一致；仍需可靠终态保存适配。

2026-09-11 已生成候选 SBOM / 许可证清单 / OSV 快照，路径见下节。这不是生产依赖安全门禁，也没有维护窗口或例外签署。构建解析的完整版本记录在 QA 证据中。

## 本机镜像证据

- Seata：`apache/seata-server@sha256:cfd2e903e768ede846feb20553e4c97ddcc76f7ffd7c1f4b580104446cd4e38f`。
- MySQL本机镜像：`mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb`。
- XXL admin 本机镜像：`xuxueli/xxl-job-admin@sha256:fa6ad9343f41be414446685f00c28456444577c8209948760c8089b493c39e35`（linux/amd64；本机 Docker Desktop arm64 经模拟运行 `XxlAdminTriggerIT`）。

这是本次实际运行镜像记录；跨架构CI还需核对对应manifest，不把本机平台镜像摘要当成通用生产锁。

## 许可证 / SBOM / OSV（候选，非生产锁）

生成命令：`./scripts/generate-sbom.sh`（Maven profile `-Psbom`，不加入默认 `mvn verify`）。产物：

- CycloneDX 聚合 BOM：`docs/implementation/sbom/wms-platform.json`（75 个组件）
- 第三方许可证清单：`docs/implementation/sbom/THIRD-PARTY.txt`（license-maven-plugin 285 条，含测试传递依赖）
- OSV 快照：`docs/implementation/sbom/osv-findings.md`（2026-09-11，67 个 purl，1 次命中）

许可证观察（不是法务签署）：

- BOM 声明以 Apache-2.0 为主。
- `com.xuxueli:xxl-job-core:3.4.2` 在 THIRD-PARTY 中记为 GNU GPL v3。
- `org.antlr:ST4:4.3` 许可证未知；`missing-licenses.properties` 未手工覆盖，`failOnMissing=false`。

OSV 命中（未升级 Boot/Tomcat，不把空扫描当成目标）：

- `pkg:maven/org.apache.tomcat.embed/tomcat-embed-core@11.0.24` ← Spring Boot 4.1.1 传递
  - GHSA-9xv2-5v5q-p794：DIGEST 认证重放窗口绕过；建议上游 11.0.25
  - GHSA-gcx9-497g-6cp6：安全约束顺序导致访问控制绕过；建议上游 11.0.25
  - GHSA-h3x4-894j-xpx5：FORM 认证不正确授权；建议上游 11.0.25

本文件仍不是生产版本锁定。EG-02、维护窗口、CVE 例外签署均未通过。

TC DB终态审计隔离候选已实测提交/回滚清理、重启与审计写入故障恢复，详见[候选说明](TC_TERMINAL_EVIDENCE.md)；不改变当前生产版本门禁未通过的结论。
