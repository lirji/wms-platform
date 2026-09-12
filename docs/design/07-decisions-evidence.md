# 决策、待确认项与证据

本文件保留设计阶段的决定、候选与来源。当前实现核对见[总体架构](01-architecture.md)及[版本记录](../implementation/VERSION_LOCK.md)；后续已有正式 TM/RM 与运行消息证据，不能把早期候选状态当成当前未实施，也不能推断生产环境已验收。

## 1. 需求与授权记录

来源为本次会话：用户先请求分布式仓储方案，后确认“自营仓、商品需要批次/序列号/效期、库存先不为负、支持跨仓分配、峰值规模按照京东订单量走”，随后讨论 XXL-JOB/ShardingSphere-JDBC，最后要求详细设计和具体实施文档；后续明确第一版即独立入库/出库/库存服务，本轮据此修订方案；最新批准跨仓预占采用Seata TCC，其余本地事务与可靠消息。

历史阶段仅授权文档；当前用户已批准补执行门禁并推进wms-platform业务开发。recon/dev-infra修改与生产部署仍不在本轮范围。技术选型按前述讨论形成 v0.3 基线；业务默认值单独列出。

## 2. ADR 摘要

| ADR | 决策 | 理由与代价 | 状态 |
| --- | --- | --- | --- |
| ADR-01 | 分区 Cell + 全局履约 + 集成 + 查询 | 按仓隔离写入与故障；承担路由/迁移治理成本 | 方案基线 |
| ADR-02 | 首版独立inbound/outbound/inventory | 三库独立；inventory本地事务+来源命令/回执+执行授权/取消补偿恢复 | 用户已确认，替代合并核心方案 |
| ADR-03 | MySQL + ShardingSphere-JDBC | Java 体系与 dev-infra 复用；需真实 SQL/连接/路由验证 | 产品选型基线，版本候选 |
| ADR-04 | XXL-JOB 调度，业务表持久化进度 | 复用运维能力，幂等与恢复由业务负责 | 产品选型基线，版本候选 |
| ADR-05 | Kafka 作为唯一首期消息组件 | 多订阅、重放与库存投影；无需同时建设两套 MQ | 本版建议 |
| ADR-06 | 跨仓预占采用Seata TCC | fulfillment为TM、inventory为RM、TC全局权威；全部确认后执行；保留故障时占用 | 用户已批准，替代自研协调协议 |
| ADR-07 | 独立序列号登记权威 | 解决跨仓唯一和归属移交；登记故障影响新收货/转移 | 本版建议 |
| ADR-08 | quantity 对账扩展，保留 Money 路径 | 现有 recon 金额模型不能冒充库存单位 | 证据支持，需要跨仓库实施 |
| ADR-09 | 首期局部冻结盘点、短暂停写迁移 | 降低现场/数据并发复杂性；影响局部作业可用性 | 业务窗口待确认 |
| ADR-10 | 无 XA 全流程、无全国核心库广播查询 | 避免长时间占锁与跨仓协调耦合 | 方案基线 |

## 3. 待确认清单与阻塞范围

| ID | 状态 | 决定或剩余问题 | 阻塞范围 |
| --- | --- | --- | --- |
| OQ-01 | 已关闭 | 企业内序列号唯一范围为 enterprise+SKU+normalized_serial；正规化规则随商品版本，首期保留大小写、不随意去内部空格 | 不再阻塞 S3 唯一键；正规化细则随 SKU 版本 |
| OQ-02 | 已关闭 | 已批准Seata TCC；TC成功且全部仓确认后执行，其余本地事务+可靠消息 | 不再阻塞选型；具体版本/适配仍需S0 |
| OQ-03 | 待确认 | 自营货权主体数、商品单位及效期截止规则；提案为保留 owner、精确基础单位、日期转 UTC 留规则版本 | 业务种子/生产接入 |
| OQ-04 | 已关闭 | 无现有 OMS 履约协调器；唯一 TM 为 `wms-fulfillment`。将来若 OMS 合格，经同一 TCC 契约交接，不得并行两个权威 | 正式履约服务按 S4 创建，S0 探针不得冒充该服务已交付 |
| OQ-05 | 待确认 | 仓数、订单/扫描峰值、热点分布、SLO、RTO/RPO | 容量签署/生产拓扑 |
| OQ-06 | 待确认 | 冻结盘点及迁移可接受停写窗口 | 现场验收/数据迁移 |
| OQ-07 | 待确认 | 对账系统 quantity 扩展和数据提供方 | 真实跨系统对账验收 |
| OQ-08 | 部分关闭 | origin 为 GitHub，CI 为 GitHub Actions；远程 main 已存在 | 实施分支按持续授权发布 |
| OQ-09 | 待确认 | WCS/设备协议及隔离环境 | 真实设备验收 |
| OQ-10 | 部分关闭 | 认证接入采用 OIDC 资源服务器；本地测试身份使用 auth-platform Casdoor（issuer/client 仍只来自环境，仓库不写密钥）。失败不得回退免认证。生产 IdP 产品与保留期/审批分权仍待确认 | S1 权限集成；生产生命周期仍待 |

未关闭项实施时只暂停依赖该决定的切片。已关闭项按上表落库与实现，不再当作提案。

## 3.1 本轮业务决定批准

用户确认：“TM 归属：wms-fulfillment；认证：OIDC 提供方；序列号唯一范围：企业+SKU+serial”。据此关闭 OQ-01/OQ-04，并关闭 OQ-10 的身份提供方式。S1-02 本地测试接入 auth-platform Casdoor，不把该选择写成已批准的生产 IdP；OQ-03 单位/效期规则仍未批准。

## 4. 本地证据（2026-09-10 只读核查）

| 文件 | 观察 | 边界 |
| --- | --- | --- |
| [dev-infra README](../../../dev-infra/README.md) | MySQL8.4、Redis7、Kafka3.8.0、MinIO等开发实例和资源隔离约定 | 不证明服务当时运行或生产兼容 |
| [dev-infra Compose](../../../dev-infra/compose.yaml) | 镜像声明与 README 对应 | 本次未启动/修改 |
| [recon README](../../../recon-platform/README.md) | 可插拔源、匹配、判差、差异处理；现有首要金额场景 | README 宣称能力再以源码核对 |
| [SourceAdapter](../../../recon-platform/recon-core/src/main/java/com/lrj/recon/core/spi/SourceAdapter.java) | sourceId/supports/open 惰性游标 SPI | 可复用源接口思路 |
| [ReconRecord](../../../recon-platform/recon-core/src/main/java/com/lrj/recon/core/domain/model/ReconRecord.java) | 字段 money 且构造强制非空，signedAmountMinor | 数量事实不是现有一等模型 |
| [Money](../../../recon-platform/recon-core/src/main/java/com/lrj/recon/core/domain/model/Money.java) | currency+long amountMinor，三字符币种校验 | 不可填 EA 或编码数量冒充金额 |
| [ReconConsoleController](../../../recon-platform/recon-batch/src/main/java/com/lrj/recon/batch/web/ReconConsoleController.java) | runs/discrepancies 查询 | 不代表仓储 ingest API 已存在 |
| [drools pom](../../../drools-demo/pom.xml) | Java21，xxl-job.version=3.4.2 | 版本复用线索，非新项目兼容证明 |
| [drools Compose](../../../drools-demo/deploy/docker-compose.yml) | 声明 XXL admin 3.4.2 | 不复制凭据，不擅自共用实例 |

recon 检查时 HEAD 为 ee3b070；证据是读取的工作树文件。父目录和 dev-infra 没有 Git 元数据，本次不初始化仓库。

## 5. 官方依据与版本门禁

- XXL-JOB 发布页列出 3.4.2，并记录权限修复；作为 admin/core 对齐验证候选，不直接复用旧默认 URL/凭据。[发布页](https://github.com/xuxueli/xxl-job/releases)
- ShardingSphere 官方下载页列出 5.5.3（2026-03-01），作为 JDBC 验证候选；其分片和事务能力有明确使用边界。[下载页](https://shardingsphere.apache.org/document/current/en/downloads/)、[分片限制](https://shardingsphere.apache.org/document/current/en/features/sharding/limitation/)、[事务](https://shardingsphere.apache.org/document/current/en/features/transaction/)
- Spring Boot 官方系统要求页面核查到 4.1.1，支持 Java21 所在范围；这只证明运行时要求，不证明 ShardingSphere/MyBatis/XXL 全组合兼容。[系统要求](https://docs.spring.io/spring-boot/system-requirements.html)
- Kafka 对外部系统的 exactly-once 需要目标系统配合，本版使用业务幂等与 Outbox。[官方设计](https://kafka.apache.org/design/)

S0 必须产出实际 VERSION_LOCK：JDK发行版/补丁、Boot/BOM、MyBatis、ShardingSphere、MySQL驱动、Flyway、XXL admin/core、Seata Server/Client及Fence schema、Kafka client/broker、Redis client/server、OpenTelemetry 的精确版本；记录许可证、依赖扫描、镜像摘要、维护窗口及例外负责人。验证候选不是已批准生产版本，也不把旧本地版本自动升级为“最新”。

兼容 POC：Java21 启动 → 直接数据源迁移 → ShardingSphere 装配 → Mapper 条件更新与回滚 → 锁/批量/唯一约束 → XXL 触发及同键重试 → Kafka 提交/重投 → trace。任何关键失败先定位/调整最小组合，再锁定工程依赖；不得绕过测试发布。

## 6. Seata TCC选型批准

用户原文：“跨仓预占明确采用 Seata TCC；其余链路继续使用本地事务和可靠消息。可以采用这个，写入当前文档”。这是已确认方案，不再作为待选技术路线。详细设计与官方依据见[Seata TCC](09-seata-tcc.md)。未指定生产版本；S0验证TC持久化/高可用、RM回调与XID上下文、Fence同分片事务及禁用AT代理。

## v0.4幂等协议批准记录

用户批准“先补上述四项协议及故障验收”，详见[10专项](10-idempotency-protocols.md)。保留Seata TCC和其余本地事务/可靠消息；增加业务效果与执行尝试分离、TCC资源所有者匹配和XID启动CAS。新增约束及验收属于文档设计，尚无代码、迁移或真实组件验证结论。
