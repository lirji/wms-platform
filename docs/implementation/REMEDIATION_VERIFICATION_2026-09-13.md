# 后端整改阶段组合验证（2026-09-13）

范围：任务分支 `fix/backend-review-remediation`，业务源码提交 `c5348d2`；此前11个关联切片基于远程 `main f9710ef`。只使用任务独立工作树与隔离测试组件，未部署生产，未修改共享数据库或根用户工作树。

| 检查 | 结果与测量范围 |
|---|---|
| `./mvnw -B -ntp clean verify` | 03:12:03 BUILD SUCCESS，18:30；108份测试类报告、223用例，失败/错误/跳过均0 |
| 必需默认IT | `check-required-its.py --suite default` 74项全部命中并通过 |
| 独立进程smoke | inbound/outbound/inventory/fulfillment四个实际Jar：存活UP，缺依赖就绪503，业务默认拒绝 |
| warehouse-it | 复用01:09:05同一未变test-support源码/依赖的12项通过；远程CI会重跑 |
| tc-it | 复用01:50:06同一未变test-support源码/依赖的2个大探针通过；远程CI会重跑 |
| failure-it | 03:13:24 BUILD SUCCESS，47.571秒；3项通过且必需门禁命中 |
| 前端 | 控制台33测试/typecheck/build通过；最后取消数量字段修改后定向页面测试、typecheck/build再通过 |
| 脚本、文档、契约 | Python4测试通过；文档结构/链接通过；OpenAPI87路径及兼容契约校验通过 |
| Compose | 默认与`wms.verify`自定义消息前缀静态config通过；出库前缀已统一使用环境配置，未启动栈 |
| SBOM/OSV | 03:14:25生成成功；173组件/163个purl、309条许可证清单；OSV仍命中Tomcat11.0.24和fastjson1.2.83原有记录，无新增命中。新增error_prone_annotations2.21.1为测试传递依赖，Apache2.0。原有例外继续有效，不能当作零漏洞 |
| Git/远程CI | 阶段95a1d46已推任务分支并快进远程main，两个ref已核对；main CI34713653573/分支CI34713636111运行中 |

全仓日志：`/tmp/wms-c534-default.log`；smoke：`/tmp/wms-c534-smoke.log`；故障组合：`/tmp/wms-c534-failure.log`。关键数量与范围保存在本文，临时日志不是唯一进度依据。

本轮证明分批RECEIVE/QUALITY/PUTAWAY、普通PICK/SHIP/CANCEL、可靠库存确认、UTC边界、受审计恢复等当前实现的组合回归。新增消息测试中Try/Confirm或TC前置事实明确由夹具提供；真实TC探针和真实登记进程另有独立验证，不能将这些测试拼称正式TM/RM全链。R13/R14/R15仍有序列号来源观察、逐身份盘点、可信水位、正式TM/RM及出库授权传播；R22本地代码与组合证据已完成，未核实或转换生产历史数据。完整50AC、真实WCS和生产容量/RTO/RPO仍不能标为通过。
