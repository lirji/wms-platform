# 消息运行与恢复

当前实现范围：入库HTTP收货RECEIVE → 来源T1/Outbox → Kafka → 库存Inbox/T2/结果Outbox → Kafka → 来源Inbox/T3，以及库存事件查询投影。质检、上架和出库消息链仍在实施；不得据此关闭整个 R13。只操作本项目隔离组件，未部署生产。

## 配置和启动

Compose 默认 `WMS_INBOUND_MESSAGING_ENABLED=false` 和 `WMS_INVENTORY_MESSAGING_ENABLED=false`，验证收货闭环时完成数据库/OIDC配置后同时设为 true；本机进程使用 `.env.example` 的 `WMS_MESSAGING_*`。开启而没有 broker 或数据库时启动失败。先执行本项目 kafka-init 创建 `<prefix>.inventory.events`、`<prefix>.inbound.commands`、`<prefix>.inbound.results`；不开启自动建 Topic。隔离开发 Topic 一分区、一副本，最多保留七天或每分区256MiB，这是测试容量上限，不是生产保留依据或高可用承诺。旧 outbox Topic 保留供旧夹具使用，不自动迁移位点。

生产凭据按来源服务独立分配：inbound 写本环境 inbound.commands、读 inbound.results；inventory 读 inbound.commands，写 inbound.results 和 inventory.events，投影消费者只读同环境 inventory.events 和自己的消费者组。企业/仓在受信服务信封中验证；Topic 来源校验依赖 broker ACL，开发 PLAINTEXT 无法提供身份认证。TLS/SASL 凭据从受控环境注入，不记录 JAAS/令牌。生产副本、ISR、保留、ACL、容量、RTO/RPO 需要真实环境确认。

## 投递和失败语义

发布确认仅证明 broker 接受，业务完成以 Inbox DONE 和权威流水/投影版本为准。发送失败或超时后保留相同事件ID重试，未知结果不能创建新事件。Kafka auto-commit 关闭，先提交本库 Inbox 再提交单分区位点；重平衡或崩溃窗口可能重复，由事件身份与内容摘要吸收。相同事件不同内容、错误来源、畸形消息隔离。后台本库业务写入与 DONE 同事务，失败显式回滚。

领取代际单调递增，旧执行器写入失败不记成功；租约超时不等于撤销已发生的效果。Inbox 最多八次自动尝试，指数退避和抖动。消费者/后台线程、每次拉取、消息体、批次和网络等待有界；退出时停止新领取并让未完成记录由租约恢复。消息顺序由聚合键分区，库存投影另外用 aggregateVersion 拒绝旧版本，不能宣称跨分区全局有序。

## 排查与恢复边界

1. readiness DOWN 时检查本实例数据库、OIDC 配置、broker 可达性和消费者组状态；liveness UP 不能当作可接业务流量。
2. broker 短暂中断后自动恢复，核对 Outbox 的 PUBLISHED、Inbox 的 DONE 和投影版本。不要删除 Outbox、改消息正文或重置消费者位点来消除积压。
3. ISOLATED 保存事件与脱敏失败类别；人工受审计重放入口及积压告警尚待完成，当前不要直接改库冒充正常恢复。历史数据保留策略未批准，不自动删除 Outbox/Inbox。

## 已有证据

`KafkaMessagingIT` 使用真实 Kafka/MySQL 验证持久化失败后重投和重复去重；`RuntimeInboxIT` 验证同事务回滚/恢复及篡改隔离；`InventoryMessagingIT` 使用正式 Spring Bean 装配验证自动追平、原始 asOf、暂停专属 broker 后 DOWN、恢复后只产生原有三条流水。客户端3.9.2与broker3.8.0组合日志为 `/tmp/wms-kafka392-it.log`。测试未使用共享 dev_infra 故障注入。

## 来源T1上下文（正在接入）

收货请求新增可选 `locationId` / `lotId`，两者成组；来源消息开关启用时必须提供。旧客户端的两者均省略模式只适用于尚未启用消息的兼容窗口。调用方不提交owner/SKU/单位/质量：来源从自己的订单行读取owner、SKU和基础单位，并将RECEIVE质量固定为HOLD；库存服务仍须再次依据权威SKU/批次/库位进行校验。无批次标识也必须显式提交，不能为旧命令回填猜测值。

`StockPostingContext`位于公开契约模块；`SourceCommandContextStore`只操作当前服务自己的来源协议表。原始上下文、摘要和requestId在同一个T1写入source_command与source_outbox。重放只比较业务维度，保留首次执行人、时刻和关联ID；历史命令没有上下文时返回明确冲突，需要有证据的核对恢复。此步骤已由收货运行链路使用，其他动作仍需各自的库存适配与故障验证。

来源发布器和追加迁移已用SourceOutboxIT通过真实Kafka/MySQL验证。每条领取单独提交，元数据查询后释放连接，收到broker确认后按领取代际置PUBLISHED；旧上下文和过期尝试隔离。来源运行Bean、库存RECEIVE T2消费和结果回传已接通；其他动作仍在实施。

## 收货独立进程验证

`ReceiveMessagingProcessesIT` 启动本次构建的 inbound/inventory 可执行Jar、专属两库/Kafka和测试JWKS，经真实JWT HTTP建单收货。broker暂停时202且来源PENDING，恢复后权威HOLD桶/流水/凭证和来源posted收敛；换键重试收货、不同eventId的同一回执都不重复累计。现场actor沿T1消息进入库存流水。该结果不替代质检/上架/出库链路，也不证明真实WCS或容量。

库存消费按本库权威仓/SKU/库位状态、基础单位、精度、批次开关和owner/SKU所属关系校验；序列号商品缺少观察集合时隔离，不静默当普通库存。契约错误隔离、系统失败有界重试；结果Outbox与过账和Inbox DONE同事务。Kafka单次发布最多3次客户端重试且总截止5秒，来源Outbox再最多8次自动发送，避免多层无限重试。


## 积压指标与告警检查

启用消息的 inbound/inventory 后台每5秒采样本库Inbox和Outbox；只统计PENDING、CLAIMED、ISOLATED，指标标签仅queue/state。`wms.messaging.backlog` 最多计到1001（表示至少1001）；`backlog.capped=1`表示达到计数上限。`oldest.age`是该状态最早创建消息的年龄秒数，SQL用状态/创建时间索引直接定位。每个查询超时1秒，指标HTTP不查询数据库。这些是近似运行快照，不能用来证明库存业务不变量。

`sample.available=0`表示还没有成功采样，`sample.age`表示距最近完整成功采样的秒数。任意查询失败保留旧快照，不能把它当作积压消失；同时检查采样新鲜度。尚未开启消息的服务没有这些指标，不能视为零积压。

已有监控/调度可以调用 `scripts/check-message-backlog.py`：显式提供 `--base-url`、`--outbox INVENTORY_OUTBOX|SOURCE_OUTBOX`、`--max-depth`、`--max-age-seconds`、`--max-sample-age-seconds`，通过受控环境注入有 `observability.read` 权限的 `WMS_MONITOR_TOKEN`。阈值必须由运行方配置，脚本不内置生产SLO。退出0=当前阈值内，1=积压/隔离告警，2=采样失效/鉴权或连接异常。输出JSON不含令牌/地址/消息正文；不自动向他人发通知，尚未安装生产告警接收渠道。

- `MESSAGE_ISOLATED`：核对来源权限、schema与失败类别，先修复原因，再使用受审计恢复入口；禁止改消息正文或删库消警。
- `MESSAGE_BACKLOG`：核对broker/数据库、消息worker状态、消费组、最旧消息与处理预算；确保幂等后再调整并发，禁止无限扩池。
- `SAMPLE_STALE` / `SAMPLE_UNAVAILABLE` / `METRICS_UNAVAILABLE`：先恢复采样、鉴权或连接；保持未知状态，不能宣称业务正常。

Python HTTP夹具2项已通过，覆盖健康/隔离/超龄/旧快照/鉴权失败。真实MySQL有界计数与失败恢复、两个业务进程装配回归已通过，见整改证据。
