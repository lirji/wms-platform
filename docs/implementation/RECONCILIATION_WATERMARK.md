# 可信对账水位（实施中）

本任务对应R13/R15，唯一验收见[实施计划](../delivery/wms-v1/DELIVERY_PLAN.md)的WATERMARK。当前先实现来源证明提供端；库存采集、截止前过账核对和导出门禁仍未接通，不能把本页或来源COMPLETE响应作为库存完整快照的交付证明。

## 权威与边界

inbound/outbound分别读取自己的source_command和source_execution，不读写对方数据库。来源窗口按企业、仓、cutoffId固定原cutoff，cutoff是UTC排他上界，精度最大微秒。T1与窗口采集使用同一仓范围行锁；新命令在取得锁后取时间，不能补插在已关闭上界之前。原命令重放仍核对原业务身份。分页只读取200项和下一页探针，SQL另设3秒超时；同一仓T1因此串行，生产吞吐尚未测量，不宣称零成本。

来源每页核对实际APPLIED状态、原物理量/已回执量、原库存postingId及持久化过账上下文。缺回执时保留COLLECTING和原检查点，不能填一个字符串使其完成。检查点、事实数量和链式SHA-256同事务推进；最终写失败时整页回滚。事实清单只在来源窗口COMPLETE后提供，丢页、换仓、换窗口必须使接收方最终计数/摘要不匹配。

## 入口与启用

版本1摘要使用UTF-8紧凑JSON：初值为SHA256(`[1,sourceService,enterpriseId,warehouseId,cutoffId,规范化Instant]`)；每项取SHA256(`上次十六进制摘要 + 换行 + [commandId,action,executionId,quantity,postedQuantity,postingId,occurredAt]的紧凑JSON`)。数量为去末尾零的十进制字符串，时间使用Instant格式，事实按commandId排序；不可改为JSON对象的隐含属性顺序。跨语言固定向量在SourceWindowDigestTest。

- 来源内部入口：`POST /internal/wms/v1/warehouses/{warehouseId}/reconciliation-windows/{cutoffId}`，正文cutoff；200表示来源证明完整，202表示还需采集或等待T3。
- 事实页：同路径的`GET /facts?cutoff=...&cursor=...`，每页最大200；未完成返回409 SOURCE_INCOMPLETE，原时刻冲突返回400。
- 服务JWT须包含`recon.evidence`、对应企业/仓范围，subject必须在`WMS_RECONCILIATION_ALLOWED_SUBJECTS`中。默认空列表拒绝。
- `WMS_RECONCILIATION_WINDOW_ENABLED`默认false，入口默认不启用。必须先扩展迁移、升级全部来源写节点并退出旧节点，再由部署负责人启用。旧写节点不遵守应用屏障，不能共存签发可信窗口。生产启用尚未执行。

来源新增inbound V017/outbound V019，只有普通表和Mapper，不要求提升数据库权限。最初触发器方案在开启binlog的隔离MySQL上因SUPER权限不足失败，已移除；没有修改账号权限或数据库全局配置。迁移扩展可与旧应用共存，但启用关窗有上述额外前置，代码回退前须关闭证明入口并评估已签发窗口的可信性。

## 后续库存接收约束

库存必须通过受信服务连接取得两个来源的完整页，持久化原窗口、来源摘要/总数、游标、领取代际及核验进度。逐项匹配本库原command/action/sourceExecutionId、postingId、数量和截止前库存过账，最终检查完整计数和摘要。来源已确认但库存凭证晚于cutoff的窗口仍不完整，须使用更晚的新窗口，不回填历史快照。

库存历史流水还须防止关窗之后补写旧时间或遗漏在途事务；来源关闭不等于本库历史快照已经固定。现有closeWindow及SnapshotExportService仍需改造，不能信任外部三个非空水位字符串，也不能给调用方一个任意设置COMPLETE的替代接口。

## 当前证据

`.local/source-window-application-guard-it.log`于2026-09-13 07:33:41 BUILD SUCCESS：真实MySQL的SourceWindowIT2，覆盖201项分页、缺T3、最后写失败、重启后摘要一致、在途T1阻塞关窗及拒绝旧时刻新命令。T3状态是明确数据库夹具。

`.local/source-window-http-it.log`入库回归5项通过；新增HTTP越权断言发现500映射错误，已补显式403处理；`.local/source-window-http-fixed-it.log`出库HTTP3于07:36:18通过。补充未完成窗口409、UTC映射与减小SQL读取正文后，`.local/source-window-final-it.log`于07:38:34 BUILD SUCCESS（来源HTTP3、SourceWindowIT2、契约5），随后固定数组摘要的跨语言向量于07:39:23通过。尚无库存侧或完整跨服务水位验收。
