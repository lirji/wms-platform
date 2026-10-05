# W04 有界代码审查

范围：公开操作中央消费、本人接口、桌面/PDA权限提示、登录恢复与构建来源。业务状态机和内部机器发行方兼容属于后续W05/W06，不在本片宣称完成。当前审查PASS：正常Docker恢复后同版失败案例通过，144必需检查通过；保留前两轮失败。自动hygiene误分类和格式化器缺失的限制见验证记录。

| 失败场景 | 源码依据与结果 |
|---|---|
| JWT声称旧仓或旧scope，中央已撤权 | OperationScopeFilter先requireScope；WmsCentralJwt只接受当前动作范围，Owner全部经WmsJwtAuthorities读取；搜索五Owner模块未发现直接读取JWT仓/scope的旁路。旧Token撤权真实403，本人菜单清空。 |
| 读A/B、写A时误使用B写权 | 每个method/path绑定独立动作；warehouses来自当前plan，企业与仓能力分开；94绑定HTTP测试及实际SQL/浏览器越仓拒绝通过。 |
| Auth响应坏格式、超时或资源耗尽 | SDK校验响应，远程异常转401/403/503；Semaphore、导航线程/队列及截止时间有界，没有跨请求ALLOW缓存或旧JWT回退。实际Auth暂停503及恢复200通过。 |
| 跨请求复用后端身份或在异步消息传播JWT | 专用Jwt只在当前filter上下文有效，finally恢复原认证，请求close清空范围；Owner既有消息协议不加入请求JWT。 |
| 菜单/按钮相同旧scope造成企业/仓串权 | 源目录生成能力与资源映射，按钮明确resourceType；本人按当前仓复核能力，深链拒绝先于Outlet。子查询由真实GET绑定筛选，保留响应索引，不构造业务数据。 |
| React重复effect两次兑换一次性登录code | 同一次回调共享Promise，忽略卸载后的响应；真实PKCE浏览器pageerror=0，保留安全站内returnTo，401可重新登录。 |
| 构建使用开发机漂移SDK或暴露服务凭据 | CI/Docker固定源码7712d26及归档摘要；私密文件要求0600/普通文件，内容/凭据toString脱敏，无秘密进入前端或镜像。 |

hygiene：diff检查、TypeScript检查、生成器漂移、文档结构、配置边界测试通过；沿用项目既有格式，无新增持久化表、依赖升级或额外中间件。实际Docker制品/回退证据属于W07，当前不得把宿主隔离验收称为已部署。

完整回归失败记录：AllocationExecutionProcessesIT的履约/出库启动探测失败；现场保留任务`.local/wms-auth-integration/w04-allocation-first-failure/`与线程栈。线程栈曾卡在Docker容器回收的inspect读取；同时有测试数据库/Kafka/TC连接异常，原因尚未最终归类，不以环境推测替代复验。

企业-only审查：仓ID为空时只允许已授企业GET绑定对应的共享页面，不把任意仓或库位入口当共享页面；前端生成绑定限定查询，真实7后端/1浏览器/2撤权检查通过。专项复验失败与最新通过证据均见[W04验证](W04_TEST_RESULT.md)。
