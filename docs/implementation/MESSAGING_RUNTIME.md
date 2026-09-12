# 消息运行与恢复

当前实现范围：inventory 业务事务 → 本库 Outbox → Kafka → 本库 Inbox → 查询投影。来源命令与业务结果链路仍在实施；不得据此关闭 R13。只操作本项目隔离组件，未部署生产。

## 配置和启动

Compose 默认 `WMS_INVENTORY_MESSAGING_ENABLED=false`，完成数据库/OIDC配置后可设置为 true；本机进程使用 `.env.example` 的 `WMS_MESSAGING_*`。开启而没有 broker 或数据库时启动失败。先执行本项目 kafka-init 创建 `<prefix>.inventory.events`；不开启自动建 Topic。隔离开发 Topic 一分区、一副本，最多保留七天或每分区256MiB，这是测试容量上限，不是生产保留依据或高可用承诺。旧 outbox Topic 保留供旧夹具使用，不自动迁移位点。

生产凭据按来源服务独立分配：inventory 只能写本环境 inventory.events，投影消费者只读同环境 Topic 和自己的消费者组。企业/仓在受信服务信封中验证；Topic 来源校验依赖 broker ACL，开发 PLAINTEXT 无法提供身份认证。TLS/SASL 凭据从受控环境注入，不记录 JAAS/令牌。生产副本、ISR、保留、ACL、容量、RTO/RPO 需要真实环境确认。

## 投递和失败语义

发布确认仅证明 broker 接受，业务完成以 Inbox DONE 和权威流水/投影版本为准。发送失败或超时后保留相同事件ID重试，未知结果不能创建新事件。Kafka auto-commit 关闭，先提交本库 Inbox 再提交单分区位点；重平衡或崩溃窗口可能重复，由事件身份与内容摘要吸收。相同事件不同内容、错误来源、畸形消息隔离。后台本库业务写入与 DONE 同事务，失败显式回滚。

领取代际单调递增，旧执行器写入失败不记成功；租约超时不等于撤销已发生的效果。Inbox 最多八次自动尝试，指数退避和抖动。消费者/后台线程、每次拉取、消息体、批次和网络等待有界；退出时停止新领取并让未完成记录由租约恢复。消息顺序由聚合键分区，库存投影另外用 aggregateVersion 拒绝旧版本，不能宣称跨分区全局有序。

## 排查与恢复边界

1. readiness DOWN 时检查本实例数据库、OIDC 配置、broker 可达性和消费者组状态；liveness UP 不能当作可接业务流量。
2. broker 短暂中断后自动恢复，核对 Outbox 的 PUBLISHED、Inbox 的 DONE 和投影版本。不要删除 Outbox、改消息正文或重置消费者位点来消除积压。
3. ISOLATED 保存事件与脱敏失败类别；人工受审计重放入口及积压告警尚待完成，当前不要直接改库冒充正常恢复。历史数据保留策略未批准，不自动删除 Outbox/Inbox。

## 已有证据

`KafkaMessagingIT` 使用真实 Kafka/MySQL 验证持久化失败后重投和重复去重；`RuntimeInboxIT` 验证同事务回滚/恢复及篡改隔离；`InventoryMessagingIT` 使用正式 Spring Bean 装配验证自动追平、原始 asOf、暂停专属 broker 后 DOWN、恢复后只产生原有三条流水。客户端3.9.2与broker3.8.0组合日志为 `/tmp/wms-kafka392-it.log`。测试未使用共享 dev_infra 故障注入。
