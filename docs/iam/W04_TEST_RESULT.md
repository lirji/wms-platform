# W04 验证记录

2026-10-04，当前状态 PASS（Git/精确CI待交付）。总计划见 Auth `docs/design/wms-auth-integration/IMPLEMENTATION_SLICES.md`。W05–W07 尚未完成，本机 Docker 未接管。

- 10 个新增安全测试通过：真实 SDK HTTP、全部 94 条绑定逐动作允许/拒绝、旧 JWT 广权限不得绕过、窄写不借读范围、企业动作不变全仓、请求上下文关闭、403/401/503、目录哈希与配置边界。
- 前端39文件82项全套通过；后续错误/首页修改的窄检查和build通过。最后企业共享入口修改后14项身份/API与3项首页/子查询检查通过，TypeScript/build通过。这里不把分批检查声称为最新全套重跑。
- 最新 runtime-w04-71a5c4d2296a：19项真实Casdoor PKCE、Auth PostgreSQL/授权图与专属MySQL Owner SQL检查通过；5组真实登录、菜单、跨仓/PDA深链检查通过，无Token注入、API替换或pageerror。
- 企业-only追加验收：7项真实后端检查通过，只有企业SKU读权时能读取商品，仓枚举/库位查询403；1组真实浏览器证明无仓目录可显示数据库SKU且只发SKU查询，未授仓/库位深链拒绝；2项仅撤销自己记录的企业授权来源及同一Token403检查通过。企业目录截图已查看。首轮浏览器采集漏掉既有代理前缀，工具修正后重跑通过，失败证据保留。
- 前两fixture的旧Token故障/恢复/撤权分别13项、9项通过：只暂停工具自己的Auth进程，503后恢复200；撤销工具记录的reader-a来源，投影ready后同一Token403、本人提示清空，其他成员不受影响。
- 完整Java reactor实测303项，1失败、0错误、0跳过：AllocationExecutionProcessesIT履约/出库启动探测失败。未执行的test-support模块随后verify成功。专项复验同一案例仍在库存启动探测失败；required IT gate仅此一项失败。未提高超时或改业务断言，未发布失败版本。
- 已保留两轮进程日志、JUnit报告、线程栈与Docker状态证据。专项现场库存线程等待Flyway元数据SQL；Docker引擎_ping超时及inspect/stats长时间等待，稍后恢复。没有将其推测为单一根因。共享Docker重启等待用户授权，尚未执行。
- 9项生成工具检查、目录漂移、文档结构、diff检查及原项目55文件/HEAD/status保护核对通过。结构检查不替代业务验收。

私密证据仅保存在任务 `.local/wms-auth-integration/` 和 Auth `.local/wms-auth-integration/w03-state/`，不提交Token、账号密码、数据库配置或身份截图。测试工具仅停止自己的进程与专属测试容器，保留证据和数据库数据；未停止共享服务或改原WMS用户工作树。验证门禁满足后再执行W04 Git/精确CI，然后连续W05–W07。

本轮恢复：用户要求继续后，正常Docker stop/start；135原容器ID/镜像/按目标归一的挂载/运行状态与36原运行服务健康保持。5服务补恢复，旧Casdoor因数据库冷启动拒绝连接，数据库ready后正常重启成功。未删除数据卷/镜像。

同版AllocationExecutionProcessesIT专项150.2秒PASS，144 default必需检查PASS；前两次失败保留，不称整条reactor一次通过。最新前端39文件84项全套、类型/build PASS。可见UI源码与此前71a5c4d2296a真实浏览器/截图一致，无新增界面变化。

Code hygiene：工具已执行，原自动结果因宽泛正则标记测试脚本输出/切片编号、HTTP状态、PromiseSettledResult标准判别值、有限资源字面量类型与固定Owner标识为magic值而FAIL；逐条有界审查均不构成业务模型缺陷，按用户“不要机械常量化”规范保留表达，未修改共享规则或伪称工具通过。3个异常提示有配置边界/秘密不回显理由。人工审查PASS_WITH_LIMITATIONS；无仓库格式化器，FORMAT_TOOL_NOT_AVAILABLE，沿用周边格式、diff/type/编译检查。原始和逐项处理证据保留。
