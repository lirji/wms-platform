# WMS 中央权限运行配置

公开业务入口开启 `WMS_IAM_ENABLED=true`，每个服务的 `WMS_IAM_CONFIGURATION` 指向该服务独立的 0600 普通配置文件。默认关闭，开启但文件缺失、重复字段、无效租户或 OIDC 绑定冲突会启动失败。中央模式不存在 JWT scope 的允许回退。

配置文件字段：

| 字段 | 含义 |
|---|---|
| central.base-url | Auth server SDK 地址，HTTPS；仅回环隔离测试允许 HTTP |
| central.service-credential | 该服务专属凭据，不提交仓库、不放前端 |
| central.tenant-id | 已引导的 local-wms 中央租户 UUID |
| central.enterprise-id | 固定 ENT-DEMO |
| central.organization | 固定 local-wms |
| central.application / central.environment | wms / local |
| central.issuer / central.client-id | 与 WMS_OIDC_ISSUER / WMS_OIDC_CLIENT_ID 完全一致，当前 http://localhost:18090 / wms-central |
| central.connect-timeout-ms / central.timeout-ms | 默认 1000 / 5000，有界 SDK 连接及请求超时 |
| central.maximum-concurrent | 默认 8，允许 1–16，资源耗尽返回 503 |

跨容器的 JWKS 网络地址通过既有 `WMS_OIDC_JWK_SET_URI` 单独设置，签名 issuer 必须仍是正式发行地址。不得为容器联通放开 SDK HTTP 主机约束或关闭 TLS 验证。SDK 源固定为 Auth 提交 7712d2606805afb3b9a7de7a6d88fe94a28104fb，CI 和 Docker 构建均从该源构建。

全部 94 条公开操作按方法、路径和动作判权；未登记入口默认拒绝。Owner 原有企业、单据仓、目标仓检查使用后端单请求范围，不能读旧 JWT 仓集合扩大动作。调用在用例事务之前，范围仅在该请求内复用，请求结束即关闭。已受理命令仍按原业务协议完成，新的动作与重试请求重新判权。

`GET /api/wms/v1/me/access?warehouseId=WH-A` 是已认证的本人提示接口，不接受浏览器覆盖租户、应用、服务或组织。返回实际后端 mode、企业、可访问仓、当前仓能力、旧展示 scope、中央菜单、观察时间与目录版本。菜单、按钮、深链和子查询仅使用这些提示，服务端始终重新判权；请求失败立即清除旧提示。目录权限语义哈希漂移返回 503，展示名称和顺序可共存。

错误稳定为 401 UNAUTHENTICATED、403 CENTRAL_ACCESS_DENIED、503 AUTHORIZATION_UNAVAILABLE；均不缓存，不包含上游内部错误或凭据。导航多能力检查使用有界线程池/队列和 10 秒截止时间，不持久化 JWT。

W04只读、W05入出库/PDA、W06盘点调整/调拨已分别完成真实隔离验证。本人提示逐项复核完整49能力；中央菜单提示仅用于菜单显示，不能用其子集遗漏已授动作。调拨命令在实际源仓或目的仓显示，审批与应用独立。实际本机Docker与旧机器发行证明由W07单独核对，生产不在范围。

## 内部机器协议兼容

中央模式下，`/internal/wms/**` 使用单独的 `wms.internal-oidc` 过滤链。`WMS_INTERNAL_OIDC_ISSUER`、`WMS_INTERNAL_OIDC_CLIENT_ID`、`WMS_INTERNAL_OIDC_JWK_SET_URI`、`WMS_INTERNAL_OIDC_ALLOWED_SUBJECTS` 四项必须完整；全空拒绝内部请求，部分配置或无效主体启动失败。白名单最多16个明确机器主体，不接受通配符。旧本机发行方是 localhost:8000，audience 为 wms-platform；serial-registry 的原Owner白名单仍为 inventory-worker，库存TCC的原主体仍为 wms-fulfillment，来源对账另沿用原Owner白名单。

内部请求仍校验签名、issuer、audience、期限及白名单；明确的ID Token拒绝。旧机器令牌没有purpose声明时不改变原协议。Controller继续校验scope、企业、仓和事务标识。公开入口只信任中央发行方和客户端，机器链路不构成旧JWT业务权限回退。开关关闭时继续沿用原OIDC链。W05验证记录独立列出真实RSA/Spring验证与实际IdP/MySQL动作，不把协议夹具声称为旧IdP机器发行证明。
