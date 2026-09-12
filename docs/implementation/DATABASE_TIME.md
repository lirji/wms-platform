# 数据库存储时间与 UTC 接口

数据库的 `DATETIME` 本身不携带时区。本项目的瞬时字段由每个物理库的 `database_time_policy.storage_offset` 定义存储偏移；接口及消息使用 `Instant` 的 UTC ISO 文本，业务 `DATE` 与仓库业务时区保持独立。禁止根据 JVM 默认时区解读数据库时间。

## 新库和已有库

- 新建空库默认 `wms.runtime.db.time.storage-zone=UTC`，迁移后登记 `fresh-schema` 来源。
- 已有业务表但没有规则记录时，在迁移前拒绝启动。核实旧写入端的 JDBC 配置、JVM 时区和已知业务时刻，再设置 `storage-zone` 与 `legacy-evidence` 审计引用。代码不推断历史偏移、不更新旧行。
- 固定偏移的旧库可沿用例如 `+08:00`：旧值原样保存，新写入使用相同偏移，读取转换为 UTC。规则持久化后，后续启动必须匹配；更换环境变量不能覆盖数据库记录。
- 含夏令时、混合写入时区或来源不明的历史不能用单个固定偏移解释。本实现拒绝区域时区作为存储策略，须先制定独立的数据转换与歧义处理方案，不能把这类数据标为已兼容。

本机进程通过 `WMS_RUNTIME_DB_TIME_STORAGE_ZONE` / `WMS_RUNTIME_DB_TIME_LEGACY_EVIDENCE` 绑定。Compose 为五个物理库分别使用 `WMS_<INBOUND|OUTBOUND|INVENTORY|SERIAL|FULFILLMENT>_STORAGE_ZONE` 与 `WMS_<...>_LEGACY_TIME_EVIDENCE`。依据字段默认空，不能为了绕过检查而随意填写。以上配置在启动时固定，不热刷新，不等于生产升级授权。

## JDBC 和 Mapper 边界

`RuntimeDataSources` 固定 `connectionTimeZone`、`forceConnectionTimeZoneToSession=true`、`preserveInstants=true`、`treatMysqlDatetimeAsTimestamp=true`；数字偏移不依赖 MySQL 时区表。URL 的同义时区参数只能与统一配置一致，矛盾时启动失败。MyBatis Map 自动映射另外注册 Object/TIMESTAMP handler，明确调用 `getTimestamp`，避免驱动的 `getObject` 开关被框架元数据映射绕过。无来源的 `LocalDateTime` 在瞬时转换边界拒绝，不能再次按 JVM 时区猜测。

这些选项的区别依据 [Connector/J 时间处理选项](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html) 和 [保留瞬时语义说明](https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html)。仅设置旧别名 `serverTimezone` 不会自动配置 MySQL 会话时区。

## 分页与滚动升级

时间排序游标 v2 存储 UTC Instant，回传 SQL 时使用 Timestamp；微秒精度不依赖生成游标或读取游标的 JVM。纯 ID 的 v1 游标继续使用。旧时间 v1 游标没有时区来源，升级后明确返回无效游标，客户端需从第一页重新查询；不静默改变游标含义或跳过数据。

上线需先核实每个库的历史偏移，并在隔离副本验证，再安排读节点升级/排空旧节点后开始 v2 分页会话；旧版本不能读取 v2 时间游标。数据语义不转换时可沿用已核实的固定偏移，让新旧写入保持相同存储值，但必须验证旧写入端确实遵守该偏移。代码回退不能撤销数据转换；转换不属于本次代码变更，也没有自动执行。

## 验证范围

`TimeSemanticsIT` 启动真实 MySQL，并在上海与美西独立 JVM 中验证写入/读取、微秒精度、跨 JVM 游标与 DATE 不漂移；另一个隔离库验证未知历史被拒绝、显式 +08 旧值不改写、不同偏移不能覆盖规则。业务 HTTP、分批消息、对账及登记 HTTP 保留集成回归。当前未检查或迁移任何共享/生产库；这些库的实际历史时区仍需要各自的来源证据。
