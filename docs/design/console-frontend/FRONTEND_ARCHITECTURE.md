# 控制台前端架构

依据 [BRIEF.md](BRIEF.md) 与已批准交接/契约。仓库只提供约束，不提供信息架构抄本。F0–F6 已落地；本文是同一份架构的补全，不另起 IA。

新建或改管理后台页面时，先套 [ADMIN_UI_PROMPT.md](ADMIN_UI_PROMPT.md)（两份 UI 强约束的融合稿）。视觉与模板以该提示词为准；契约没有的能力不要按模板发明。

## 1. 目标与非目标

目标：一个可部署的作业台，按仓作业、按契约读数、按命令写，覆盖交接中的用户路径与页面状态。

非目标：微前端、独立 PDA 工程、页面 Mock 库存、客户端浮点决定发运量、把健康检查当业务验收、浏览器打印整壳、本地写库存队列。

## 2. 角色与路由树

| 角色 | 主作业 | 主表面 |
| --- | --- | --- |
| 仓主管 / 内勤 | 选仓、看活队列与陈旧查询 | `/w/:warehouseId` |
| 收货员 | 建单、收货、质检、上架 | 入库队列 / 单据；PDA 收货 |
| 库存 / 资料员 | 主数据读写、余额与 asOf、桶流水 | catalog / stock |
| 履约 / 出库员 | 全局单、仓子单、拣包装发 | fulfillment / outbound |
| 调拨 / 盘点员 | 发出接收、冻结点数调整 | transfers / counts |
| PDA 操作员 | 扫码收货 / 拣货 / 发运，同应用第二壳 | `/pda/:warehouseId/{receive,pick,ship}` |
| 对账 / 运维 | cutoff 差异与任务异常 | recon / jobs |

```text
/login                         登录（一列一主按钮）
/callback                      OIDC 回调
/                              已登录 → 跳到 /w/:warehouseId
/w/:warehouseId                工作台首页（KPI + 活队列）
/w/:warehouseId/catalog        商品 / 库位 / 批次（有 masterdata.write 才建档）
/w/:warehouseId/catalog/skus/:skuId          商品策略、单位换算
/w/:warehouseId/catalog/locations/:locationId 库位容量、只读门禁
/w/:warehouseId/catalog/lots/:lotId          批次货主与效期
/w/:warehouseId/inbound                      入库列表 + 弹层建单
/w/:warehouseId/inbound/:inboundOrderId      收货 / 质检 / 上架（命令弹层）
/w/:warehouseId/stock                        库存台账
/w/:warehouseId/stock/:balanceId             库存流水
/w/:warehouseId/fulfillment                  履约列表 + 本仓出库列表
/w/:warehouseId/fulfillment/:fulfillmentId   准备分配 / 生成本仓出库单
/w/:warehouseId/outbound/:outboundOrderId    规划拣货 / 拣 / 包 / 部分发 / 取消回库
/w/:warehouseId/transfers                    调拨列表 + 弹层建单
/w/:warehouseId/transfers/:transferId        发出 / 接收授权 / 接收 / 损耗
/w/:warehouseId/counts                       盘点列表 + 弹层建计划
/w/:warehouseId/counts/:countPlanId          排空冻结 / 点数 / 复盘 / 审批 / 调整
/w/:warehouseId/jobs                         任务运行 + 仓执行任务（?taskType=）
/w/:warehouseId/jobs/:jobId                  回收租约 / 领取分片
/w/:warehouseId/tasks/:taskId                领取仓任务（?taskType= 分域）
/w/:warehouseId/effects/:effectId            仓级动作效果 / 安全重授权
/w/:warehouseId/recon                        对账窗口 + 差异 + 快照分段 + 弹层审批
/pda/:warehouseId/receive                    PDA 收货
/pda/:warehouseId/pick                       PDA 拣货（serialExecution，epoch 不默认）
/pda/:warehouseId/ship                       PDA 发运
```

范围外：OMS/ERP 门户、设备固件 UI、对账导出桌面工具、SKU 标签套打。

旧路径 `/inbound` 等重定向到带仓的新路径，避免书签断裂。

## 3. 形态

**选择：单应用。**

| 方案 | 结论 |
| --- | --- |
| 单应用 | 采用。一个构建、一套 OIDC、一套反代 |
| workspace 多包 | 拒绝。当前只有一个可部署 UI |
| 微前端 | 拒绝。没有多团队独立发布门禁 |

## 4. 前端栈

仓库已锁定 React 19 + TypeScript + Vite 7 + `oidc-client-ts` + Vitest。组件库以 Ant Design **6** 为唯一 owner。

| 决策 | 选项 | 选择 | 拒绝原因 |
| --- | --- | --- | --- |
| 框架 | React / Vue | React | 现有锁文件、测试、Docker、OIDC 适配 |
| 语言 | TypeScript / 无类型 | TypeScript | OpenAPI 与数量字符串契约 |
| Owner kit | Ant Design / Material / Tailwind | Ant Design 6 | Material 会第二套 Table/Form；Tailwind 不当 Table/Form owner。布局可用少量 CSS，颜色必须读 Ant token |
| 服务端数据 | fetch 包装 / 查询库 | fetch 包装 | 列表短、202 轮询有界，不需要第二缓存 |
| 路由 | react-router | 框架默认 | 已用于登录与回调 |
| 表格/表单 | 轻量 / 管理套件 | Ant `Table` / `Form` / `Modal` / `Alert` | 列来自契约字段，不预置业务行 |

BRIEF 曾假设「深青石板 + 琥珀」。现按后台强约束：浅色侧栏、作业按钮主色 `#1677FF`，状态五类 Tag。灰只给正文和分割线。命令层是居中 `Modal`。两字按钮关闭 Ant 自动插空。

## 5. 模块与目录（提议）

```text
wms-console/src/
  app/           路由与会话装配；作业页 `lazy()` + `Suspense`
  auth/          OIDC、returnTo、令牌 claims
  api/           前缀路由、信封解析、幂等键
  design/        只服务 Ant ConfigProvider，不另养一套页面色
  shell/         桌面壳、PDA 壳、仓选择（写 URL）
  shared/ui      状态条、表、游标翻页、数量文本、复制 id、错误码
  features/*     按作业：home catalog inbound stock fulfillment transfer count jobs recon pda
  pages/         仅登录/配置等无仓页
```

壳与作业分离。作业页不得 import 另一作业的领域组件。

## 6. 状态与数据流

| 种类 | 放哪里 |
| --- | --- |
| 当前仓 | URL `:warehouseId` |
| 会话 | oidc-client-ts user |
| 列表筛选 `q` / 对账 `cutoffId` | URL search，刷新可恢复 |
| 列表游标 | URL `cursor`；没有下一页则不画假页码 |
| 列表 / asOf | 每次进入页面向活 API 拉取，不进全局 store |
| 幂等键 | sessionStorage，按「一次有意操作」复用 |
| 扫码框焦点 | 组件局部 |
| 列宽 | 不按用户身份持久化；本阶段不开放拖拽列宽 |

客户端：`GET/POST /api/wms/v1/...` → `routeFor` 到四服务前缀。解析 `CursorPage.items` 与错误体。数量字段只当字符串渲染。演示行必须来自 seed 后的 API。

类型：新字段从 OpenAPI 生成或手写与契约同名的类型；禁止继续用 `Record<string, unknown>` 发明仪表盘 KPI 接口。

## 7. 屏幕状态矩阵

每个作业页至少：

| 状态 | 行为 |
| --- | --- |
| loading | 保留过滤条件；表用 skeleton，不是整页转圈；提交按钮禁用 |
| empty | `当前仓 {id} 没有{对象}。可新建或检查筛选。` |
| 筛选空 | `当前筛选没有匹配。清除筛选后重试。` |
| error / 5xx / 网络 | 服务不可用，不伪装无权限或空表 |
| 401 | 登录过期；主动作重新登录；禁止写成「权限不足」 |
| 403 | 仓范围或作业权限不足；展示 `code` + 仓 + scope；深链给拒绝页，不装空表 |
| success | 仅契约终态；202 不是成功 |
| 202 accepted | 处理中 + operationId；库存看 `stockSyncStatus` |
| 409 | 最新记录 + 需重确认；不换幂等键 |
| 422 | 字段错优先，停在表单 |
| 429 | 过于频繁，主按钮暂时禁用 |
| stale | asOf / lagSeconds + 刷新 |
| TCC | 「库存已预留，等待全局完成」，无强制释放 |
| PDA | 文字 + tone；BRIEF 声音反馈待接，不能只靠颜色 |
| recovery | 弹层脏表单用 Ant Modal 确认离开，不用 `window.confirm` |

## 8. 视觉与页面配方

企业作业台：浅色侧栏、`#F5F7FA` 画布、密表。主色 `#1677FF`。状态只用五类 Tag（成功/处理中/等待/异常/终止）。命令仍居中弹层。对照用户强约束稿，不另起视觉语言。

| 技能 token | Ant / CSS |
| --- | --- |
| canvas / paper | `colorBgLayout=#F5F7FA` |
| surface / card | `colorBgContainer=#FFFFFF` |
| ink / muted | `colorText=#1D2129` / `#4E5969` / `#86909C` |
| line | `colorBorder=#E5E6EB` |
| accent | `colorPrimary=#1677FF` |
| ok / warn / err | `#52C41A` / `#FAAD14` / `#FF4D4F`；状态 Tag 用浅底+描边 |
| radius | 控件 6，卡片/弹层 8，Tag 4 |
| type-12/13/16/20 | `fontSize=14`，Title 20/600 |
| control-height-desktop | `controlHeight=32` |
| control-height-touch | PDA `size=large` ≥44px |
| focus-ring | Ant 默认 2px；禁止无替代 `outline: none` |
| shadow-1 | 无按钮/卡片投影；登录不做第二套营销渐变墙 |

### 层配方（Ant 原语）

| Layer | 选择 | 用 |
| --- | --- | --- |
| Canvas | 平面浅灰，不加每页第二渐变 | `Layout` |
| Chrome | 浅色 220 侧栏 + 56 顶栏；选仓重于用户菜单 | `Layout.Header` `Sider` `Menu` `Select` |
| Work | 表格吃满剩余宽 | `Layout.Content` |
| Page head | 20px 标题 + 状态 Tag + 工具条最多一颗 Primary | `PageHead` + `.list-toolbar` |
| Search | 列表白卡片：已有筛选项 + 查询 Primary / 重置 Default | `WmsSearchForm` |
| Status | 头下一条 `Alert`；业务状态五色 Tag | `StatusBanner` `StatusChip` |
| Table | sticky header、行高 46、数量右齐、状态 Tag | `Table` `WmsToolbar` |
| Pager | 表底右侧游标「首页 Default / 下一页 Primary」；不编页码 | `ListPager` |
| Form | 标签在上，主提交在最后，危险动作分开 | `Form` `size=small` |
| Scan | 全宽 ≥48px，结果一个面板 | PDA `Input` `size=large` |
| Empty | 与表同表面，无插画 | `Empty` simple |

| 模板 | 构成 | 禁止 |
| --- | --- | --- |
| 登录 / 配置 | 与作业台同色、**一列**、一句话、一主按钮。能力说明折到主按钮下方 | 两列功能清单、登录页第二渐变当主路径 |
| 首页 | KPI 行（接口行数）+ 入库/出库/任务活队列 | 再印一遍侧栏卡片墙 |
| 队列 | 头 + 一行筛选 + 表。建单进居中 `Modal` | 列表内嵌长表单；履约页两张主表要分主次，出库表是次表面 |
| 单据 | 头 + `Descriptions` 事实 + 明细表；命令进居中 `Modal`+`Tabs` | 五张卡叠在表下；弹层里再叠五张大卡 |
| 扫描 | 输入 → 结果 → 历史；无侧栏 | 缩桌面壳当 PDA |
| 状态 | 一条 `Alert`：401 会话，403 仓/权限 | 401/5xx 写成权限不足 |

Casdoor 令牌必须带作业 `scope` 以及 `warehouses` / `enterprise_id`。顶栏展示仓与权限名，不展示 access_token。F6 已开通 42 项 scope，已登录会话须重新登录。

## 9. 视口策略

交接已要求同应用 PDA 页，因此不单做桌面、也不另开移动产品。不问出第三套移动 App。

| 表面 | 宽度 | 行为 |
| --- | --- | --- |
| 桌面工作台 | ≥1280 | 左侧作业导航 + 顶栏选仓 + 密表 |
| 窄桌面 | 768–1279 | Sider 折叠，表 `scroll.x` |
| PDA 路由 | ≥390 | 独立壳、大触控、扫码框 autofocus；无字母快捷键 |

不发明深色主题、第二字体、插画空态。

## 10. API / 认证 / 错误假设

- 认证：OIDC 授权码 + PKCE；空 issuer 不回退免认证
- 授权：令牌仓范围 + scope；**令牌不能尝试的命令隐藏**；深链仍由服务端 403
- 错误体：`code` / `message` / `retryable`；对照见 §11，禁止堆栈或整段 JSON 当标题
- 写操作：`Idempotency-Key`；`scanSequence` 仅在契约字段出现时递增，不本地伪造
- 时区：展示可按仓，请求 UTC
- TP99：前端不宣称达标
- 打印/导出：不对整壳 `window.print`。对账文件只走已发布 `recon.export`，按钮次要，文案是「导出快照」不是「导出全部」
- 离线：线上写。BRIEF「待同步意图」只能显示服务端 202/`stockSyncStatus`，禁止 `localStorage` 库存队列

作业详情提交已落地命令：入库收货/质检/上架（可选序列号观察/选择），出库核验授权/拣包发与未拣取消（可选 `serialExecution`，身份来自 `serial-stock` / `shippable-serials`），调拨数量发出/授权/接收/损耗以及公开序列签发/签收/命令查询，盘点冻结点数审批调整（可选身份集合，空集合=全部未见），独立调整单审批应用，同仓移库与库存限制/释放，履约取消请求与 attempt 执行，任务回收/领取，仓任务领取，仓级 action-effects 列表/详情/安全重授权，序列号恢复/消息重排，主数据单位与门禁查询，对账窗口请求/重试/取消、快照分段读取、APPROVE/REJECT 与快照导出。跨仓 ALLOCATED 仍要求 TC Committed 证据。出库单状态 ALLOCATED 不是履约 ALLOCATED。`GET /operations/{id}` 只有库存实现；入出库/履约 202 只展示受理，不拿库存作业去猜。PDA 收/拣/发共用该壳。已提交取消补偿尚无公开查询入口，页面不发明。内部 `serial-registry` / `recon.evidence` 不接线到作业台。

## 11. 落地细节

| Topic | 本作业台 | F7 落地 |
| --- | --- | --- |
| Density | Table/Form/Button `small`；桌面 `controlHeight=32`；PDA `large` | `theme.ts` `controlHeight=32` |
| Scroll | 壳 sticky；**表体**滚；`scroll.x`；表头 sticky | `Table sticky` |
| Column | 标识 180、状态 112、数量 112 右齐；长 id 省略 + tooltip + 复制 | `DataTable` 固定列宽 |
| Row actions | 打开单据为链接；危险命令在弹层且 `danger` | 取消剩余 / 在途损耗 `danger` |
| Batch | 无契约批量则无复选框 | 已遵守 |
| Filters | 一行；`?q=` `?cutoffId=` `?cursor=` | 列表与对账写入 URL；履约出库用 `oq`/`oc` |
| Pagination | 契约 cursor；不把本页 12 条假装成分页权威 | `ListPager` 首页 / 下一页，无本地 pageSize |
| Open-in | 单据走路由；建单/命令走居中弹层；离开确认仍用 `Modal.confirm` | 弹层内按 `CommandCol` 分页签，一次一个命令 |
| Feedback | 字段→Form；契约→一条 Alert；瞬时→`message`（复制成功）；202 留状态条 | 409 只展示 code/message，不 dump JSON |
| Loading | 表 skeleton；全页转圈只给首次进壳 | 空表 loading 用 skeleton 行 |
| Leave guard | 弹层脏表单 Ant Modal | `Modal.confirm` |
| Icons | 仅 `@ant-design/icons`；图标+文字；仅关闭/溢出可纯图标 | 已基本遵守 |
| CJK | PingFang SC / Noto Sans SC；数量 `tabular-nums`；中文行高 ≥1.5 | 数量右齐 + tabular |
| Locale | 时间按仓时区展示，请求 UTC | 顶栏墙钟是本机 UTC 文本，可保留 |
| Permission UI | 无 scope 则隐藏命令；深链 403 | `hasScope`；PDA 无对应 receive/pick/ship scope 不提交 |
| Overlay | 同时一个命令弹层；Popover 可叠在顶栏 | 已遵守 |
| Motion | ≤200ms；`prefers-reduced-motion` 即时 | `styles.css` 已声明 |
| Dark mode | 关 | 已遵守 |
| 快捷键 | 见下表。不另做桌面 keymap | Esc 靠 kit；不抢浏览器查找 |
| 打印 | 不做 | 已遵守 |
| Id 复制 | 单据 id / operationId 旁「复制」，toast「已复制 {kind}」不回显全文 | `CopyId` |
| 离线条 | 不做本地待同步条 | 已遵守 |

### 无障碍

| Topic | 记录 |
| --- | --- |
| Contrast | 正文与 chip ≥4.5:1；侧栏选中用青绿底+白字 |
| Focus | 跳过链接 → 品牌 → 选仓 → 导航 → 主区；路由切换后焦点到 `h1` |
| Focus visible | 保留 Ant 2px ring |
| Focus trap | Modal 用 kit 默认 |
| Labels | 可见 label；占位符不是标签；表 `aria-labelledby` 页标题 |
| Live | 状态条与扫码结果 `role=status` |
| Keyboard | 导航用 Menu 箭头；表不发明 Excel 键；PDA 只 Enter |
| Target | 桌面 ≥24；PDA ≥44；危险与主按钮不贴在一起 |
| Semantics | 每路由一个 `h1`；`header`/`nav`/`main` |

### 空态文案

沿用技能句式：当前上下文 + 没有什么 + 下一步。不写营销、不怪用户、不发明行数。

### 命令分组

| Slot | 用法 |
| --- | --- |
| Primary | 每表面一个：登录、创建入库单、提交命令、回车提交、加载差异 |
| Secondary | 返回、打开 PDA、重置筛选 |
| Tertiary | 复制 id、打开列表 |
| Destructive | 取消剩余 / REJECT，`danger`，与保存分开；确认用 Modal |
| Batch | 无 |

对账页「加载差异」是该页主按钮；「审批修复」改为次要，避免两个 primary。

### 快捷键

| 范围 | 键 | 动作 | 不要 |
| --- | --- | --- | --- |
| 队列 | kit Modal Esc | 关弹层 | Esc 离开路由 |
| 表单 | Enter | 提交当前弹层里那一个主命令 |  |
| 扫描 | Enter | 只提交扫码框 | 全局 Enter、字母快捷键 |
| 全局 | 无 `/` 或 `Ctrl+K` | BRIEF 无统一搜索 | 抢浏览器查找 |

### 表格列宽

| 列 | 宽 | 对齐 | 溢出 |
| --- | --- | --- | --- |
| 标识 / 单号 | 160–200 | 左 | 省略 + tooltip + 复制 |
| 状态 | 88–120 | 左 | 不折行 |
| 数量 | 88–128 | **右** `tabular-nums` | 不折行 |
| SKU / 名称 | minmax(160, 1fr) | 左 | 一行省略 |
| 行操作 | 无固定操作列；id 即链接 |  |  |

先藏备注 → 名称 → 时间，永不藏 id/状态/数量。单响应默认 ≤50 行，不虚拟滚动。

### 错误码对照

| HTTP / code | 用户标题 | 详情 | 动作 |
| --- | --- | --- | --- |
| 401 | 登录已失效 | 请重新登录；不是仓权限不足 | 去登录 |
| 403 / WAREHOUSE_FORBIDDEN | 没有权限访问该资源 | `code` + 仓 + scope | 回上一作业 |
| 404 | 找不到该记录 | 当前仓 + id | 回队列 |
| 409 | 版本冲突 | 需确认最新记录；不换幂等键 | 刷新后重确认 |
| 422 | 填写有误 | 字段级优先 | 停在表单 |
| 429 | 请求过于频繁 | 稍后重试 | 禁用主按钮 |
| 5xx / 网络 | 对应服务不可达 | 服务角色 + `code` | 原键重试 |
| 202 | 已受理 | operationId；禁止「成功」 | 按原命令查询 |
| 未知 | 请求失败 | 截断的 `code` + `message` | 重试或返回 |

禁止：原始异常字符串当页标题；`JSON.stringify` 整段 body；把 401/5xx 写成权限不足。

## 12. 未决

F7 已落地。F-serial / F-202 / F-recovery / F-catalog 已接到现有命令弹层与任务页，不另起 IA。商品/库位/批次可点进公开 GET 详情。

仍 blocked / 不发明：

- 设备 UNKNOWN 与真实硬件（S8-05）
- 履约整单确认依赖真实 TC，不能写成 ALLOCATED
- AC-26 仍 open（本轮补了 202 不换键、单据双状态、401 去登录；未做现场黑盒）
- 出库 TCC 证据副本要由履约 outbox 消费写入；没有公开发明证据接口
- 公开拣/发/调拨序列号清单未入契约，页面不发明
- PDA 保留文字 + tone；可选短 beep，无音频设备时静默
