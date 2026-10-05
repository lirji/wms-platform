# WMS 模块包结构与代码质量重构

用户目标：权限部分完成后，使用 Claude SKILL 细化项目每个模块的包结构，格式化代码，并按技能代码要求优化。连续实施，不在每批后等待“继续”。当前根据最近 WMS 接入任务默认处理 WMS；项目范围异步问题未回复时不改 Auth 产品源码。

规范来自 `~/.claude/skills/project-refactoring/SKILL.md`、`backend-implementation/SKILL.md` 与 `~/.claude/dev-standards.md`；前三端为同一 Cursor 源，已核对摘要。完整目标保留；仅格式化不等于任务完成。

## 基线与保护

起点 origin/main `2efa151b77c2620d39e810a3780710532fe0cdfc`；完整 verify CI37257041197 SUCCESS（默认真实IT、warehouse、TC、failure及console）。当前本机全reactor package成功，93个本机单测通过。基线门禁和测试保护门禁PASS。

复用干净工作树 `~/.local/share/git-worktrees/wms-platform/central-authorization`，分支 `refactor/module-packages-code-quality`。原WMS工作区/main17048d5及55个用户文件仍保护；未提交Driver独立开发内容需保持，不纳入提交。其已有包组织另外只读核对，若需要改动且会混入未发布功能，先取得明确范围决定。

基线518个Java源/测试文件、120个SQL文件的摘要已保存于忽略的 `.local/refactoring-module-packages/baseline-source.json`。应用启动类与种子CLI全名、HTTP路径/字段/错误语义、数据库结构/迁移/SQL、事件/消息/幂等/事务/权限/TTL/预算保持。内部Java全名在必要拆包时变化，全部已知消费者、MyBatis namespace/resultType、自动配置注册、脚本/测试/当前文档必须同步；外部协议不变化。

## 问题与目标

- P2：履约根包46类混合Controller、Mapper、应用编排、TC/远程适配、恢复任务及配置。按订单/分配/调拨/TC能力，再按实际职责组织；保留私有协作簇，不将包内实现一律暴露为public。
- P2：安全18类处于根包，身份、条件配置、中央授权和恢复入口混合。按身份/授权/恢复分组，保持服务端仓与企业范围校验和失败拒绝。
- P2：runtime.messaging17类混合Kafka、inbox、outbox、恢复、消息契约和指标。按真实变化点组织，不新增中间件或第二套配置/错误框架。
- P2：库存已有业务包，但serial29、recon17、inventory13内仍混合协议、规则、应用/持久化。先按现有业务能力，再细分实际责任；领域规则不能依赖HTTP/JDBC实现。
- P2：全仓大量一行方法/声明，缺少稳定格式入口。格式化保持字面量/协议，重点补非显然分支的中文原因说明、拆长用例及复用既有规则。

目标优先业务能力；在需要时使用web/application/domain/persistence/integration/configuration等职责包。简单CRUD和很小的协作包不机械增加层。每个正式模块都需包结构与代码规范逐项审查，已有正确部分保留并给证据。

## 实施切片

| ID | 范围与结果 | 依赖 | 状态 |
|---|---|---|---|
| R00 | Claude规范、原工作保护、架构/API/DB/消息/测试基线与整体路线 | — | DONE |
| R01 | 518 Java源/测试、console源码及相关XML格式化；固定工具/检查入口与风格 | R00 | DONE |
| R02 | contract按库存/序列/分配/取消/调拨/TCC契约拆包，integration按模型/端口/适配拆包；同步全部已知消费者 | R01 | DONE |
| R03 | runtime消息/inbox/outbox/Kafka/恢复等职责包及真实重复逻辑优化 | R02 | DONE |
| R04 | security身份/中央授权/恢复包；保持包内封装与所有边界测试 | R03 | DONE |
| R05 | inbound收货/质检/上架/任务/源协议的协议、应用和持久化责任 | R04 | DONE |
| R06 | outbound订单/授权/拣发/取消/设备/源协议的责任及状态约束 | R05 | DONE |
| R07 | serial-registry入口/应用/持久化/配置，保持全局身份与转移不变量 | R06 | DONE |
| R08 | fulfillment订单/分配/调拨/TC/恢复的能力与层次，保持原事务和幂等 | R07 | DONE |
| R09 | inventory主数据/库存/移动/盘点/serial/recon/TCC/jobs等每个能力的包与代码规范 | R08 | DONE |
| R10 | test-support场景/基础设施/契约分组、所有FQCN和进程入口同步；console规范/格式/结构审查 | R09 | DONE |
| R11 | 全模块中文注释、类型/常量/配置、错误处理/规则/事务/SQL/测试的逐条审查及有证据优化 | R10 | DONE |
| R12 | 全量必要IT/全部CI、接口/SQL/事件兼容、架构与卫生终审、文档进度和正常Git交付 | R11 | IN_PROGRESS |

单批迁移映射可审查，编译、相关测试、配置/namespace、diff通过才继续。各批逻辑完整后本地提交在同一任务分支；最终必要完整CI成功后正常合入并推main，保护原main与用户改动。没有新部署授权，不改正在运行的W07制品/数据。

## 格式工具决定

仓库没有既有格式器，用户明确要求项目格式化，因此本次增加确定的开发检查入口；不是为卫生规则单独增加工具。Java独立CLI google-java-format1.37.0/AOSP四空格，官方制品SHA256 `834b2a0c38cb774953322a84b5ca3f2f40dd3156650b3cd44d3b744345962f7a`，仅存.local/tools，不加入后端运行依赖。跳过长字符串和中文Javadoc重写。前端Prettier3.9.9为精确锁定的开发依赖，MIT/Node>=14已核对；遵循现有两空格/单引号。XML沿用两空格，已执行SQL迁移不格式化。

正式工具依据：[Google CLI/参数](https://github.com/google/google-java-format)、[固定release](https://github.com/google/google-java-format/releases/tag/v1.37.0)、[Prettier固定版本与check](https://prettier.io/docs/install)。技能卫生引擎不改；R01元数据明确允许用户要求的全范围格式变化并批准上述开发工具，后续代码批次禁止无关格式变化。

## 兼容和验收

HTTP/API、DB、消息/事件、正式业务规则：UNCHANGED。内部Java包名及效期转换方法归属：CHANGED且消费者同步。R11另修正隔离WCS的无效回执/并发重放及页面清空上下文后的旧错误，具体CHANGED见[规范审查](CODE_QUALITY_REVIEW.md)。现有main应用/种子入口：UNCHANGED。保持SKU数量/精度、状态迁移、Owner仓、跨仓/成员代际/严格撤权、机器固定主体与scope、无ALLOW缓存、幂等/lease/乱序/CAS失败处理。

完成必须有：每模块包映射和责任/依赖证据；格式check；必要编译、单测/真实数据库IT/已有必需profile；权限目录/操作摘要、SQL迁移、运行配置兼容；技能Code Hygiene Gate与终审；正常Git及精确CI。未运行/未覆盖的项不称PASS，不能仅用格式或子模块编译宣称全部目标完成。

## 当前进度与证据

R00–R11完成本地验证；R12正在执行完整集成测试、最终兼容和Git/CI交付。测试与构建原始日志、原文件摘要、后续每批回执在 `.local/refactoring-module-packages/`。全目标未完成，所有TODO切片持续推进；最终报告补齐已实施映射、验证、技术债、风险、回退和证据索引。

### R01 验证（f6ceb72）

518个Java文件、75个XML及人工console源码已格式化；93个Java单测、86个前端测试（39文件）、10个脚本测试通过；前端类型/build与全部格式check通过。120个SQL迁移、中央目录/运行绑定不变。菜单解析支持单/双引号与多行静态声明，仍拒绝动态表达式、重复字段、未知路由；生成的centralBindings.ts按导出器字节核对，不由Prettier重写。锁文件只新增Prettier，后端依赖无变化。

卫生CLI原始结果保留：换行被误判为新增原有依赖/状态。用同一固定格式器独立处理不可变Git基线后，由未修改的技能引擎对真实当前源码和规范化基线扫描，显式应用R01格式变更元数据，得到IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无阻断项。唯一限制为技能命令发现器未识别独立Java CLI（FORMAT_TOOL_NOT_AVAILABLE）；实际Java/XML/console三项格式check均已执行通过。下载超时60秒的开发工具固定值另有一条非阻断建议，未改变业务预算。基线规范化摘要、原始CLI与完整扫描结果保存于.local/refactoring-module-packages/；没有删除规则或豁免真实代码新增。此结果仅证明R01，整个重构尚未完成。

### R02 本地包迁移验证

按R02_PACKAGE_MAPPING迁移22个类，未扩大原访问可见性。全reactor清除旧编译结果后构建/单测通过（93项）；120个SQL迁移、64份Mapper SQL文本、权限目录摘要及格式/文档/差异检查通过。完整真实IT、CI与最终卫生审查仍由R12完成。原始结果：.local/refactoring-module-packages/r02-unit-fixed.log，R02_TEST_RESULT.json。

R03还修正协议到Kafka适配器的反向依赖：262144字节预算由RuntimeMessage定义，Kafka发布器保留原常量入口作为同值别名。新增信封边界与UTF-8多字节超限测试，预算数值、错误码和消息结构不变。R02初次compile遗漏一个跨行FQCN，已在r02-unit-fixed.log重测通过；原失败日志保留。

### R03 本地包迁移验证

按R03_PACKAGE_MAPPING迁移31个类，未扩大原访问可见性。全reactor清除旧编译结果后构建/单测通过（94项）；120个SQL迁移、64份Mapper SQL文本、权限目录摘要及格式/文档/差异检查通过。完整真实IT、CI与最终卫生审查仍由R12完成。原始结果：.local/refactoring-module-packages/r03-unit-limit.log，R03_TEST_RESULT.json。

### R04 本地包迁移验证

按R04_PACKAGE_MAPPING迁移24个类，未扩大原访问可见性。全reactor清除旧编译结果后构建/单测通过（94项）；120个SQL迁移、64份Mapper SQL文本、权限目录摘要及格式/文档/差异检查通过。完整真实IT、CI与最终卫生审查仍由R12完成。原始结果：.local/refactoring-module-packages/r04-unit-fixed.log，R04_TEST_RESULT.json。

### R05 本地包迁移验证

按R05_PACKAGE_MAPPING迁移24个类，未扩大原访问可见性。全reactor清除旧编译结果后构建/单测通过（94项）；120个SQL迁移、64份Mapper SQL文本、权限目录摘要及格式/文档/差异检查通过。完整真实IT、CI与最终卫生审查仍由R12完成。原始结果：.local/refactoring-module-packages/r05-unit.log，R05_TEST_RESULT.json。

### R06 本地包迁移验证

按R06_PACKAGE_MAPPING迁移29个类，未扩大原访问可见性。全reactor清除旧编译结果后构建/单测通过（94项）；120个SQL迁移、64份Mapper SQL文本、权限目录摘要及格式/文档/差异检查通过。完整真实IT、CI与最终卫生审查仍由R12完成。原始结果：.local/refactoring-module-packages/r06-unit-fixed.log，R06_TEST_RESULT.json。

### R07 本地包迁移验证

按R07_PACKAGE_MAPPING迁移16个类，未扩大原访问可见性。全reactor清除旧编译结果后构建/单测通过（94项）；120个SQL迁移、64份Mapper SQL文本、权限目录摘要及格式/文档/差异检查通过。完整真实IT、CI与最终卫生审查仍由R12完成。原始结果：.local/refactoring-module-packages/r07-unit.log，R07_TEST_RESULT.json。

### R08 本地包迁移验证

按R08_PACKAGE_MAPPING迁移57个类，未扩大原访问可见性。全reactor清除旧编译结果后构建/单测通过（94项）；120个SQL迁移、64份Mapper SQL文本、权限目录摘要及格式/文档/差异检查通过。完整真实IT、CI与最终卫生审查仍由R12完成。原始结果：.local/refactoring-module-packages/r08-unit.log，R08_TEST_RESULT.json。

### R09 本地包迁移验证

按R09_PACKAGE_MAPPING迁移190个类，未扩大原访问可见性。全reactor清除旧编译结果后构建/单测通过（96项）；120个SQL迁移、64份Mapper SQL文本、权限目录摘要及格式/文档/差异检查通过。完整真实IT、CI与最终卫生审查仍由R12完成。原始结果：.local/refactoring-module-packages/r09-unit-cohort.log，R09_TEST_RESULT.json。

### R10 本地包迁移验证

按R10_PACKAGE_MAPPING迁移26个类，未扩大原访问可见性。全reactor清除旧编译结果后构建/单测通过（96项）；120个SQL迁移、64份Mapper SQL文本、权限目录摘要及格式/文档/差异检查通过。完整真实IT、CI与最终卫生审查仍由R12完成。原始结果：.local/refactoring-module-packages/r10-unit.log，R10_TEST_RESULT.json。

### R11 规范审查与有证据优化

十个正式模块和console的结构/规则审查见[代码规范审查](CODE_QUALITY_REVIEW.md)。本批补齐765个显式公开方法、接口方法及构造器的中文说明，AST复核未发现缺项；没有机械包装record访问器。新增Mapper/资源/自动配置/领域依赖检查及3个实际失败场景的脚本回归用例，已接入CI。

100个Java单测、87个console测试、13个脚本测试，前端类型/build，Java/XML/console格式、包边界、权限目录、文档与diff检查全部PASS。SQL120份、Mapper SQL文本64份及中央目录字节保持。WCS先新增测试复现无效回执占用事件身份；修复后重复无效请求仍拒绝，合法相同身份可首次成功，并发重放只有一次首次接受。效期投影转换迁移前后同组特征测试通过，类型与错误保持。页面清空上下文的旧错误回归先失败、修复后通过。

原始卫生CLI扫描R11真实差异通过：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS、零finding。唯一限制FORMAT_TOOL_NOT_AVAILABLE是命令发现器没有识别已执行的独立Java CLI；实际三类格式check已PASS。初次检查发现源码集合名称直接比较及重复回执错误码，已复用命名常量修正并重新验证；原结果保留，没有改变技能规则或规范化R11差异。最终日志/回执在.local/refactoring-module-packages/R11_TEST_RESULT.json。完整真实IT/profile/精确CI由R12执行，不能以本批单测替代。

R12 首轮候选 e7c3294 的完整 CI 37269556033 在跨进程迁移后的 RM 就绪检查失败；135 个库存 IT 中 1 项失败，后续 profile 未执行。单测与控制台检查成功。失败发生于子进程退出，当前回执未归档子进程日志，因此补充原测试目录日志上传后重新定位，不放宽断言或跳过测试。R12 保持 IN_PROGRESS。
