# W07 本机验收

实施及本机验证 PASS_WITH_LIMITATIONS。WMS产品源0f62d41的完整CI37249068115 SUCCESS；本片不改Java/UI源码，新增中央Compose、真实浏览器工具及运行文档。

四源码镜像，隔离HTTPS18项、真实旧IdP/机器Owner/SQL/旧序列版本共存23项、原目标4账号PKCE6项及5张实看图片、原目标SDK/401/403/503/恢复11项均PASS。原6WMS与5Auth目标服务最终healthy。原两MySQL镜像/数据卷相同，142表行数及摘要一致；初始化目录路径改变但源文件字节相同。原dirty树HEAD/status/55文件不变。

真实Auth管理页创建单能力角色、仓仅指定WH-A授予/严格撤权、连续projector完成回执以及同授予前Token A200→403/B403；其他8条演示源保留。最新版原目标浏览器没有mock、Token注入、业务写入或5xx。

原失败/超时/502和探针错误全部保留。当前只设置本任务64–256MiB堆预算，受控绑定同一Graph本体转发、保持客户端回环HTTP/TLS边界，并使console在显式后端更新后重启解析IP；没有权限允许缓存或放宽身份2秒期限。

实际详情/私密证据索引见[Auth W07](https://github.com/lirji/auth-platform/blob/main/docs/design/wms-auth-integration/W07_TEST_RESULT.md)及[运行说明](W07_RUNTIME.md)。机器JWT1小时、演示源24小时，不是永久权限；未实现自动续发、TCC业务、全目标实际回滚或容量/灾备。当前精确提交Git/CI由Auth私密delivery-result.json记录，不以本地PASS预报远端成功。
