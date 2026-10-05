# WMS 与 Auth 本机 Docker 部署结果（2026-10-05）

WMS 模块重构版本 `562f90f933edd7e5144aed058179294cab95d892` 已部署到原 `wms-local`，Auth 为 `e4d14eb1b764f53419bbda9ee42920d9b0d3920b`。本机两个项目的 **11 个应用容器全部 healthy、重启计数为 0**。本记录覆盖实际部署，9 月架构/运行文档及 W07 验收保留为各自历史证据。

完整的六镜像绑定、Auth 五个应用、资源排查、命令和回退边界见 [Auth 权威部署报告](https://github.com/lirji/auth-platform/blob/main/docs/deployment/auth-wms-refactor-docker-20261005.md)。本次只交付部署文档；后续文档提交的 SHA 不替代实际制品源码。

## WMS 实际服务

| 容器 | 制品 / 入口 |
|---|---|
| wms-local-inbound-1 | Java 源码 562f90f；18181 |
| wms-local-outbound-1 | 同一 Java 镜像；18182 |
| wms-local-inventory-1 | 同一 Java 镜像；18183 |
| wms-local-serial-registry-1 | 同一 Java 镜像；18184 |
| wms-local-fulfillment-1 | 同一 Java 镜像；18185 |
| wms-local-console-1 | 控制台源码 562f90f；[http://127.0.0.1:18180](http://127.0.0.1:18180) |

Java 镜像 `wms-platform/wms-java:rev-562f90f933ed` 的本机不可变 ID 为 `sha256:a8d0cffbabbf282ca2ab010cd4a901edbc6c054f3454fb0d9e8bce6a68394188`；控制台 `wms-platform/wms-console:rev-562f90f933ed` 为 `sha256:5a2b724ac644895d55f0bb7ef3ed19c3c12008e6217d6a67b074e7a5dbd0981e`。这两个值是本机 image ID，不是 registry manifest digest。

两个镜像均从精确 `git archive` 构建；运行 JAR 类及 SQL/XML 资源、OCI revision 已核对。SDK 继续使用 Dockerfile 校验的 `7712d2606805afb3b9a7de7a6d88fe94a28104fb`。部署前精确源码 [verify CI 37278890345](https://github.com/lirji/wms-platform/actions/runs/37278890345) completed / success；模块重构完整测试范围见[重构报告](../refactoring/module-packages/PROJECT_REFACTORING_REPORT.md)。

## 权限、数据和运行预算

- 组织仍为 `local-wms`，明确绑定 `ENT-DEMO`；人类登录 issuer 为 `http://localhost:18090`、客户端 `wms-central`，SDK 走可信 HTTPS 18545。原 8000 / wms-platform 仅保留内部机器链。
- 12 项后端检查通过：五服务中央模式和能力集、四项真实业务读取、匿名 401、跨仓及无 Grant 403。六项真实 PKCE 浏览器验收通过，含 A/B 仓和企业能力独立边界、只读、跨仓深链、390px PDA 拒绝及无授权场景；0 个 5xx、页面错误、Mock API、Token 注入或业务写入。
- 原 142 个业务表、191 行摘要相同；原数据库容器、镜像和卷未改变。五后端非 root，各自凭据 0600，信任链和挂载保留。
- 最终五后端均为 `-Xms32m -Xmx128m -XX:ActiveProcessorCount=2`、`MALLOC_ARENA_MAX=2`。Auth 三个后端使用同一预算；共享 VM 仍受限，当前结果不代表正式容量。
- MySQL 8.4.11、Kafka 3.8、Redis 7、Seata 2.6 沿用原实例；没有升级中间件、启用额外 Cell 或自动执行 worker。

## 本机维护与恢复

使用本次既有 central-authorization 工作树和 Auth 私密 `.local/wms-auth-integration/docker/wms-runtime.env`。该 env 绑定两个不可变镜像及 JVM 参数。Compose 必须同时使用：

1. WMS `compose.yaml`。
2. WMS `deploy/compose.console-release.yml`。
3. WMS `deploy/compose.central-auth.yml`。
4. Auth `.local/wms-auth-integration/projection-ready-repair/compose.wms-native-budget.yml`。

完整维护命令见 Auth 报告。只带前三份配置会丢失本机原生内存预算。使用 `--no-deps --no-build --pull never` 保留既有依赖；后端替换后刷新 console，以免 nginx 保留旧地址。已初始化实例不重跑 provision/init、不新建 Grant、不自动续发机器令牌。

旧镜像、配置、SQL 备份及失败证据保留；完整回滚本次没有执行。原机器 JWT 在 2026-10-05 04:35:46 UTC 已过期，演示 Grant 于 2026-10-06 01:17:59 UTC 到期，本次均未续期。机器业务写入、对账、TCC、现场设备、正式容量及生产不在本次验收内。

实际证据位于 Auth 私密 `.local/docker-auth-wms-refactor-20261005/`，含部署、数据、后端和两套浏览器结果及 `DELIVERY_RESULT.json`；真实账号/密钥保留在既有 0600 ACCESS，不进入 Git。原 WMS dirty main 的 55 个文件、Driver/旧试点、11 个工作树、持久卷和忽略资料全部保留。没有将这些用户改动混入部署文档提交。
