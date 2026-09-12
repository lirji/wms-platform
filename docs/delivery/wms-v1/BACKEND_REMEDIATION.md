# 后端评审整改证据

执行清单仍以 [DELIVERY_PLAN.md](DELIVERY_PLAN.md) 的 R 编号为准。首批基线为 `db02821`。本文记录实现边界与验证，不替代原 50 AC、生产容量签署或设备验收。

## R16–R20 实现与边界

| 编号 | 实现 | 验证 |
| --- | --- | --- |
| R16 | 入出库/履约/调拨/盘点/任务列表按 `created_at DESC,id DESC`；主数据、投影、差异单按主键分页。limit 1–200，查询多取一条生成 nextCursor；范围和投影世代绑定游标。单次库存/对账/快照查询明确限制一个仓库。 | `BoundedPaginationIT`：205 条同时间戳跨页不重不漏、先权限过滤后分页；`RuntimeBudgetTest`：非法大小、错误范围、尾页游标。 |
| R17 | 四服务 HikariCP；每实例默认 max16/min2、获取连接 1s、语句 5s、网络连接 3s/读取10s。验签后按企业限流：全局64/租户16并发、全局200/租户40请求每秒；拒绝返回429及 Retry-After。请求体1MiB，批量请求最多200项。 | `DatabaseBudgetIT`：真实MySQL池耗尽、恢复、语句超时；`RuntimeBudgetTest`：租户公平与释放。阈值是初始预算，不是实测容量结论。 |
| R18 | 355 处原注解 SQL 归入同名 Mapper XML；迁移 SQL 进入基础设施 Mapper。只允许固定迁移表和经元数据验证的列名，值全部绑定；每批200行，目标库分批提交。 | `MapperXmlBindingTest` 检查资源、语句标识和返回类型；`WarehouseMigrationIT` / `IsolatedRestoreIT` 真实两库回归。 |
| R19 | 全部现有 HTTP 请求体使用 DTO，Bean Validation 校验必填/精度/长度/批量上限；协议行显式映射现有应用模型。真正的 MySQL1062 才是重复键；解析错误400，唯一冲突409，数据库故障503且不暴露原始消息。 | `InboundRequestBoundaryTest`：非法数量/缺字段不访问数据库、故障503、重复409。已有领域校验仍执行。 |
| R20 | 主数据展示列表使用 Caffeine L1 + 可选 Redis L2。Compose 接现有隔离Redis；没配置 Redis 的直启环境保留有界L1与回源预算。Outbox 批量/次数/租约/退避类型化，指数退避增加抖动。 | `QueryCacheIT`：跨实例L2、租户key隔离、过期、Redis断连、有界回源；运行配置非法时启动失败。 |

主数据缓存是允许最多5秒陈旧的展示投影，不用于鉴权、门禁、库存扣减、单位换算或其他写入决策。每次读缓存前仍验证JWT与仓范围；key包含资源、企业、仓、身份、仓权限集合、分页条件，采用结构化编码后摘要。撤销权限遵循既有JWT有效期治理；缓存不延长令牌有效期。

L1默认500ms，L2最多5s并随机缩短0–20%；两层携带同一绝对截止时间，从数据库查询开始计时，旧查询回填不能获得新的完整TTL。更旧的并发回填可能暂时覆盖新值，但只能在其原截止时间内展示；不承诺强一致或依赖失效广播。空列表同样有界，不缓存异常。默认L1最多1000项、按每项最低16KiB计重（总权重约16MiB）、单值超过256KiB不缓存。Redis命令100ms，断连拒绝排队并短暂熔断；共享Redis256MiB、volatile-lru。多实例时钟需同步。

回源最多4个并发，超限503，不无限排队，不把过期数据作为成功响应。`wms.query.cache.*`指标记录L1/L2命中、回源/拒绝、Redis错误、驱逐、条数和活跃回源；指标不带租户或业务ID。Redis退化时监控回源、数据库连接使用与429/503，先限制入口或恢复缓存，不能靠盲目放大连接池处理。

运行参数见 `.env.example`。首期继续采用环境变量为唯一配置权威；更改池、缓存和Outbox策略需要重启并审查环境差异，不实现未经需要的动态刷新。任何副本扩容都应汇总连接和请求预算，并重新实测TP99；本轮默认值没有容量达标签署。

新增依赖复用 Spring Boot 4.1.1 BOM：HikariCP7.0.2、Caffeine3.2.4、Lettuce7.5.2.RELEASE；不升级既有中间件。连接参数依据 [HikariCP 官方说明](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)，缓存行为依据 [Caffeine 官方文档](https://github.com/ben-manes/caffeine/wiki)；Redis写入同时设置有效期，参见 [SET 官方文档](https://redis.io/docs/latest/commands/set/)。2026-09-12已重新生成SBOM/许可证/OSV：168组件、158 purl，既有Tomcat11.0.24及fastjson1.2.83命中；新增三项未命中。详见 [OSV快照](../../implementation/sbom/osv-findings.md)。既有依赖风险未因本轮整改消失，生产安全门禁仍未通过。

## 验证状态

2026-09-12 首批本地验证：

- 全模块默认回归运行至履约模块，仅 `FulfillmentHttpIT` 的无目的批次旧请求因 DTO 误设必填失败。恢复可选语义后，履约 HTTP 定向重跑通过（包含正常接收与新增仓隔离）；其余已执行模块均通过，不将最初失败日志写成 BUILD SUCCESS。
- 全模块单元测试通过；后续 `WarehouseMigrationIT` 2项、`ProductionTransactionsIT` 1项通过。较早的 `DatabaseBudgetIT`、`QueryCacheIT`、`BoundedPaginationIT`、`IsolatedRestoreIT` 均通过。迁移 JSON 类型从数据库元数据获取，不按字段名称猜测。
- `check-required-its.py --suite default` 的24项必需测试通过；四服务独立进程 smoke 通过；控制台 build 与17文件33项测试通过。首轮控制台构建因本地尚未安装 Ant Design 失败，执行锁文件 `npm ci` 后重跑通过，没有改依赖版本。
- 首批暂存版本 OpenAPI 可重复生成，ActionEffectRequest 的旧必填集及可选 digestVersion 保留；文档结构检查通过。首批 R16–R20 为独立提交单元，后续安全修复在同任务分支继续。
- warehouse-it/tc-it/failure-it 及远程 CI 待整个整改集成后执行；上述记录不替代这些检查或50 AC验收。

## 后续批次进度

R01 生产装配回滚测试已通过，R02/R03 操作 scope 与调拨仓范围实现和定向测试通过；R04 出库授权正在修改，尚未验收。这些后续改动不属于首批 R16–R20 提交。


## R01–R04 实现与验证

- R01：普通 HTTP/任务 SqlSession 使用 JdbcTransactionFactory，TCC单独用同数据源的 SpringManagedTransactionFactory/SqlSessionTemplate。生产Bean装配测试验证盘点建到一半异常不留计划/范围，Try成功后异常不留Fence/预占/余额变化；已通过。
- R02：OpenAPI与运行时同源生成79条公开路由权限，验签后按HTTP方法和解析路径逐一检查，未登记入口拒绝。scope支持字符串/数组；permissions支持显式作业授权；groups和仓声明不能冒充作业scope。补齐14个已有入口的契约，单测遍历每条路由的缺权、错误scope、组名碰撞和正确scope；契约测试检查实际Controller路径覆盖。已通过；入库、主数据、效果、库存领域、快照HTTP定向回归全部通过。
- R03：调拨列表在SQL内限制至少一个参与仓获授权，再进行分页；游标绑定权限集合；详情省略warehouseId仍检查参与仓。准备分配检查全部参与仓权限。真实HTTP测试证明授权仓作为源/目的均可见，无关仓详情403，不可见行不挤掉分页结果；已通过。
- R04：建单时携带authorizationId不授予执行能力，初始保持PENDING_AUTHORIZATION；人工作业及设备派工必须核验同企业/仓/单/attempt的AUTHORIZED记录，并联结匹配的Committed证据。重放检查XID/证据引用/参与者摘要，绑定仅允许待授权状态；不同键重放不回退PICKING。OutboundHttpIT、OutboundPickIT、OutboundDispatchIT、OutboundExecutionBlackBoxIT与ClosedLoopBlackBoxIT定向通过；设备入口共享检查与生产装配的最后回归也已通过。

上述业务回归中的本地终态证据是明确的测试夹具，不是R14的真实TC到出库同步，也不证明完整MQ运行链路。R13/R14仍待实施。

2026-09-12 21:31：R01–R04 最后 HTTP/生产装配回归 BUILD SUCCESS（`/tmp/wms-security-http-it.log`，19项集成测试及全模块单元测试）。本地检查通过后单独提交；远程组合CI仍待全部整改集成。
