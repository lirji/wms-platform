# 版本记录与验证范围

## 状态

核对基线 `main c5c96e3`（2026-09-13）。后端声明来自[根 POM](../../pom.xml)、[Wrapper](../../.mvn/wrapper/maven-wrapper.properties)，镜像来自[应用 Dockerfile](../../deploy/app.Dockerfile)、[前端 Dockerfile](../../wms-console/Dockerfile)和[Compose](../../deploy/compose.local.yml)。以下版本是仓库声明与既有验证记录，不是现场探测或生产版本锁定。

后端代码与依赖相对 `3e2c720` 未变，其[main CI](https://github.com/lirji/wms-platform/actions/runs/34721632607)已通过；控制台后续变更见[当前状态](../delivery/wms-v1/DELIVERY_STATUS.md)，不把旧 CI 当成新前端验证。真实 TM/TC/RM 与故障恢复已有阶段证据，见[原生 RM](RUNTIME_TCC_RM.md)、[自动执行](FULFILLMENT_EXECUTION.md)和[TC 恢复](FULFILLMENT_TC_RECOVERY.md)。TC 终态通知、资源/Fence 迁移及全局提交后的取消补偿仍未完成，不能继续沿用“正式 TM/RM 尚未实现”，也不能据局部通过标完整验收。

| 组件 | 本轮版本 | 已验证范围 |
| --- | --- | --- |
| JDK | Microsoft OpenJDK 21.0.11+10-LTS | 本机编译/进程启动 |
| 容器编译 JDK/JRE | eclipse-temurin:21.0.8_9-jdk-jammy / 21.0.8_9-jre-jammy | `compose.yaml` 在容器内 `mvn package`；可用 `WMS_JDK_IMAGE`/`WMS_JRE_IMAGE` 换同标签镜像站。不是生产锁 |
| Maven / Wrapper | 3.9.12 / 3.3.4 only-script | Wrapper生成并用于构建；分发URL为Maven Central |
| Spring Boot | 4.1.1 | 五个后端应用采用同一版本；CI覆盖进程启动、鉴权及业务集成验证 |
| MyBatis starter | 4.1.0 | 依赖可解析；MyBatis原生会话+Mapper真实MySQL条件更新；Boot的MyBatis自动配置连接ShardingSphere通过 |
| MyBatis core | 3.5.19 | 与 starter 4.1.0 BOM 一致；同名 Mapper XML 加载与方法/结果类型校验。进程仍不启用 JDBC/Flyway 自动配置 |
| ShardingSphere JDBC及插件 | 5.5.3 | 双物理数据源、确切仓路由、缺仓拒写、本地回滚 |
| Seata client | 2.6.0 | 原生Fence/重复二阶段及真实双仓RM、TM执行与重启恢复已有证据；未覆盖所有后续业务补偿 |
| Seata Server | 2.6.0 | 真实提交/回滚、持久终态审计与恢复已隔离验证；清理后Finished仍不能单独证明成功 |
| MySQL | mysql:8.4.11 | 复用dev-infra现有镜像版本创建专属临时容器，不操作共享实例；本地 compose 三实例同标签 |
| Testcontainers | 2.0.5 | Docker29.7.2下真实启动MySQL，Docker不可用直接失败 |
| ANTLR runtime | 4.13.2 | 覆盖Seata传递的4.8，修复ShardingSphere解析器ATN版本冲突 |
| XXL-JOB core | 3.4.2 | 依赖可解析；warehouse-it 证明 handler 清理后无当前全局事务；`XxlAdminTriggerIT` 经官方 admin 3.4.2 真实触发 BEAN handler，执行器未持有 TCC。不是集群/分片验收 |
| XXL-JOB admin | xuxueli/xxl-job-admin:3.4.2 | 隔离 compose 可启动；warehouse-it 对官方镜像做 `/auth/doLogin` + `/jobinfo/trigger`。官方镜像 linux/amd64，本机 arm64 经模拟。不是集群/分片/生产调度 |
| Kafka broker | apache/kafka:3.8.0 | 与 dev-infra 同标签；隔离 compose 可启动；warehouse-it 用同标签 Testcontainers 验证生产/消费且消费不 bind XID |
| Kafka client | kafka-clients 3.9.2 | R13运行链路新增依赖；按官方修复CVE-2026-35554/33558选择3.9.2，与3.8.0 broker兼容/断连恢复隔离回归已通过，未升级broker |
| HikariCP / Caffeine / Lettuce | 7.0.2 / 3.2.4 / 7.5.2.RELEASE | Boot BOM；真实连接池耗尽/超时及缓存故障回归。HikariCP/Caffeine Apache-2.0，Lettuce MIT；2026-09-12 OSV 三项直接依赖未命中（不代表无漏洞） |
| Redis | redis:7-alpine | 与 dev-infra 同标签；主数据展示 L2 跨实例、过期、断连降级已通过专属 Redis 集成测试；最大陈旧5s，不用于业务写决策 |

## 前端及解析版本

前端声明范围见[package.json](../../wms-console/package.json)，以下为[package-lock.json](../../wms-console/package-lock.json)实际解析版本；使用 `npm ci` 复现，不把范围下限当成安装版本。

| 组件 | 锁文件版本 |
| --- | --- |
| React / React DOM | 19.3.0 / 19.3.0 |
| Ant Design | 6.6.3 |
| React Router DOM | 7.18.3 |
| oidc-client-ts | 3.5.0 |
| Vite / TypeScript / Vitest | 7.3.6 / 5.9.3 / 3.2.7 |
| Node / Nginx 容器 | `node:22-alpine` / `nginx:1.27-alpine`，可由构建参数覆盖；标签不是镜像摘要锁 |

后端聚合 SBOM 记录 MySQL Connector/J 9.7.0、Flyway 12.4.0、Spring Security 7.1.1；LZ4 在 POM 固定 1.11.1。Maven SBOM 不覆盖前端完整依赖或容器 OS 包，不能据此声明全项目无漏洞。

## 关键装配决定

- ShardingSphere 5.5.3的JDBC基础模块不自动提供全部插件；显式加入sharding-core、MySQL parser/connector、standalone memory及authority-simple。探针使用内存元数据仓库，库存数据是持久化MySQL；生产元数据治理另需验证。
- ANTLR冲突由实际SQL测试发现，不能只编译验证兼容。统一4.13.2用于本项目TCC组合；Seata AT SQL解析路径不启用也未验收，不宣称该修订适用AT。
- 首批原生Fence验证使用固定物理DataSource，后补单RM双仓ContextDataSource路由及TC在途重启通过；后续双独立RM各自单Cell经ShardingSphere/Fence组合已有探针；启动CAS与真实branchRegister重复Try所有权夹具已通过；单RM跨物理库原子性未承诺，不能把各自测试通过当作组合通过。
- 本机无环境启动时默认不接数据库、OIDC issuer 为空时拒绝业务 HTTP；存活不等于就绪。根 Compose 显式配置各应用数据库，readiness 还要求 issuer/client ID 和依赖可用。配置 `WMS_INVENTORY_JDBC_URL` 后 inventory 才 Flyway 并提供主数据读写 HTTP。CI 用 Testcontainers 覆盖迁移、种子复跑与 JWT 仓隔离，不要求现场 Casdoor。
- ordinary开发组件优先dev-infra；WMS 隔离本地栈见 `deploy/compose.local.yml`，不加入共享网络。故障探针使用Testcontainers或另起 `COMPOSE_PROJECT_NAME=wms-fault` 创建/回收自己的资源，无权重启或清理共享组件。

## 来源与限制

候选版本从Maven Central对应artifact元数据核对；源码按Apache Seata v2.6.0读取。官方资料：[Seata发布历史](https://seata.apache.org/release-history/seata-server/)、[ShardingSphere 5.5.3](https://shardingsphere.apache.org/document/5.5.3/en/overview/)、[Seata DefaultCore源码](https://github.com/apache/incubator-seata/blob/v2.6.0/server/src/main/java/org/apache/seata/server/coordinator/DefaultCore.java)。DefaultCore.getStatus在会话不存在时返回Finished，与真实TC探针结果一致；可靠终态保存已由隔离 TC DB 审计候选接入，生产安装与治理仍未签署。

2026-09-13 的仓库快照已包含候选 SBOM / 许可证清单 / OSV 结果，路径见下节。这不是生产依赖安全门禁，也没有维护窗口或例外签署。构建解析的完整版本记录在 QA 证据中。

## 历史本机镜像证据（未在本次重查）

- Seata：`apache/seata-server@sha256:cfd2e903e768ede846feb20553e4c97ddcc76f7ffd7c1f4b580104446cd4e38f`。
- MySQL本机镜像：`mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb`。
- XXL admin 本机镜像：`xuxueli/xxl-job-admin@sha256:fa6ad9343f41be414446685f00c28456444577c8209948760c8089b493c39e35`（linux/amd64；本机 Docker Desktop arm64 经模拟运行 `XxlAdminTriggerIT`）。

这是早期隔离验证保存的镜像记录；跨架构CI还需核对对应manifest，不把本机平台镜像摘要当成通用生产锁。

## 许可证 / SBOM / OSV（候选，非生产锁）

生成命令：`./scripts/generate-sbom.sh`（Maven profile `-Psbom`，不加入默认 `mvn verify`）。产物：

- CycloneDX 聚合 BOM：`docs/implementation/sbom/wms-platform.json`（173 个组件；2026-09-13 快照）
- 第三方许可证清单：`docs/implementation/sbom/THIRD-PARTY.txt`（license-maven-plugin 309 条，含测试传递依赖）
- OSV 快照：`docs/implementation/sbom/osv-findings.md`（2026-09-13，163 个查询 purl，2 个组件命中）

许可证观察（不是法务签署）：

- BOM 声明以 Apache-2.0 为主。
- `com.xuxueli:xxl-job-core:3.4.2` 在 THIRD-PARTY 中记为 GNU GPL v3。
- `org.antlr:ST4:4.3` 许可证未知；`missing-licenses.properties` 未手工覆盖，`failOnMissing=false`。

OSV 命中（未升级 Boot/Tomcat，不把空扫描当成目标）：

- `pkg:maven/org.apache.tomcat.embed/tomcat-embed-core@11.0.24` ← Spring Boot 4.1.1 传递
  - GHSA-9xv2-5v5q-p794：DIGEST 认证重放窗口绕过；建议上游 11.0.25
  - GHSA-gcx9-497g-6cp6：安全约束顺序导致访问控制绕过；建议上游 11.0.25
  - GHSA-h3x4-894j-xpx5：FORM 认证不正确授权；建议上游 11.0.25

本次只核对既有产物，没有重新联网扫描。生产版本、维护窗口与漏洞例外仍未签署。当前快照另含 fastjson 1.2.83 的既有命中；完整记录以 `sbom/osv-findings.md` 为准。

TC DB终态审计隔离候选已实测提交/回滚清理、重启与审计写入故障恢复，详见[候选说明](TC_TERMINAL_EVIDENCE.md)；不改变当前生产版本门禁未通过的结论。

## 历史兼容与依赖修复记录

以下保留当时的验证范围与计数，当前快照计数以上节为准。

2026-09-12 R16–R20：完整聚合BOM另报告既有 `com.alibaba:fastjson:1.2.83` 的 GHSA-crf3-v9rr-v7hj；Tomcat三项仍存在。此次没有升级Seata或Boot，也没有安全例外签署。新增HikariCP/Caffeine/Lettuce未命中；未命中不代表不存在漏洞。

2026-09-12 R13：Kafka客户端由仅测试进入运行范围，原3.8.0受消息误投Topic与日志信息泄露漏洞影响。依据 [Apache安全公告](https://kafka.apache.org/community/cve-list/) 使用修复版3.9.2；[官方兼容说明](https://kafka.apache.org/41/getting-started/compatibility/)支持通过API版本协商与旧broker互通，实际组合仍以本仓库隔离集成测试为证据。Kafka broker仍为既有3.8.0隔离开发标签，生产版本/ACL/复制与容量未锁定，不把客户端修复当作broker整体安全验收。

Kafka 客户端 3.9.2 与 broker 3.8.0 的实际兼容/提交后入箱/重复隔离/断连恢复已于本轮通过，日志 `/tmp/wms-kafka392-it.log`；未修改共享 broker。该结果覆盖本地单 broker 组合，不证明生产多副本丢失或整体安全门禁。

新增 Kafka 运行传递依赖首次 OSV 扫描命中 `at.yawk.lz4:lz4-java:1.10.1`，依据[维护者 GHSA-xx22-p4ch-683r 公告](https://github.com/yawkat/lz4-java/security/advisories/GHSA-xx22-p4ch-683r) 将其单独锁为1.11.1（Apache-2.0）。影响条件是JNI调用接收非法数组引用或范围，不能据此宣称任意正常Kafka消息都可触发。本轮补压缩消息真实组件回归，并重新扫描SBOM。

LZ4 1.11.1 压缩消息通过真实 Kafka 接收、持久化重投与去重测试（`/tmp/wms-lz4-it.log` BUILD SUCCESS）。随后重新生成 SBOM/许可证并扫描 OSV，172组件/162purl，仅保留既有Tomcat和fastjson两个组件命中；LZ4修复版未命中。不以未命中替代安全保证。
