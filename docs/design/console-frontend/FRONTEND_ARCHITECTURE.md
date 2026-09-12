# 控制台前端架构

依据 [BRIEF.md](BRIEF.md) 与已批准交接/契约。仓库只提供约束，不提供信息架构抄本。

## 1. 目标与非目标

目标：一个可部署的作业台，按仓作业、按契约读数、按命令写，覆盖交接中的用户路径与页面状态。

非目标：微前端、独立 PDA 工程、页面 Mock 库存、客户端浮点决定发运量、把健康检查当业务验收。

## 2. 角色与路由树

```text
/login                         登录
/callback                      OIDC 回调
/                              已登录 → 跳到 /w/:warehouseId
/w/:warehouseId                工作台首页（待办入口，无写死数字）
/w/:warehouseId/catalog        商品 / 库位（只读）
/w/:warehouseId/inbound                      入库列表 + 建单
/w/:warehouseId/inbound/:inboundOrderId      收货 / 质检 / 上架
/w/:warehouseId/stock                        库存台账
/w/:warehouseId/fulfillment                  履约列表 + 本仓出库列表
/w/:warehouseId/fulfillment/:fulfillmentId   准备分配 / 生成本仓出库单
/w/:warehouseId/outbound/:outboundOrderId    规划拣货 / 拣 / 包 / 部分发 / 取消回库
/w/:warehouseId/transfers                    调拨列表 + 建单
/w/:warehouseId/transfers/:transferId        发出 / 接收授权 / 接收 / 损耗
/w/:warehouseId/counts                       盘点列表 + 建计划
/w/:warehouseId/counts/:countPlanId          排空冻结 / 点数 / 复盘 / 审批 / 调整
/w/:warehouseId/jobs                         任务列表
/w/:warehouseId/jobs/:jobId                  回收租约 / 领取分片
/w/:warehouseId/recon                        对账查询 + 审批修复
/pda/:warehouseId/receive                    PDA 收货（独立壳）
```

范围外：OMS/ERP 门户、设备固件 UI、对账导出桌面工具。

旧路径 `/inbound` 等重定向到带仓的新路径，避免书签断裂。

## 3. 形态

**选择：单应用。**

| 方案 | 结论 |
| --- | --- |
| 单应用 | 采用。一个构建、一套 OIDC、一套反代 |
| workspace 多包 | 拒绝。当前只有一个可部署 UI |
| 微前端 | 拒绝。没有多团队独立发布门禁 |

## 4. 前端栈

仓库已锁定 React 19 + TypeScript + Vite 7 + `oidc-client-ts` + Vitest。2026-09-12 用户要求补上组件库：作业台使用 Ant Design 5（自定义青绿主题），不绑假数据。

| 决策 | 选项 | 选择 | 拒绝原因 |
| --- | --- | --- | --- |
| 框架 | React / Vue | React | 现有锁文件、测试、Docker、OIDC 适配 |
| 语言 | TypeScript / 无类型 | TypeScript | OpenAPI 与数量字符串契约 |
| 样式 | tokens+CSS / Tailwind / Ant Design | Ant Design 5 + 少量布局 CSS | 用户要求组件库；表格/表单/布局用 Ant，行数据仍来自接口 |
| 服务端数据 | fetch 包装 / 查询库 | fetch 包装 | 列表短、202 轮询有界，不需要第二缓存 |
| 路由 | react-router | 框架默认 | 已用于登录与回调 |
| 表格/表单 | 轻量 / 管理套件 | Ant Design Table / Form | 列来自契约字段，不预置业务行 |

## 5. 模块与目录（提议）

```text
wms-console/src/
  app/           路由与会话装配
  auth/          OIDC 与 returnTo
  api/           前缀路由、信封解析、幂等键
  design/        色板、间距、字号 tokens
  shell/         桌面壳、PDA 壳、仓选择（写 URL）
  shared/ui      状态条、表、数量文本
  features/*     按作业：home catalog inbound stock fulfillment transfer count jobs recon pda
  pages/         仅登录/配置等无仓页
```

壳与作业分离。作业页不得 import 另一作业的领域组件。路由级按页拆分。

## 6. 状态与数据流

| 种类 | 放哪里 |
| --- | --- |
| 当前仓 | URL `:warehouseId` |
| 会话 | oidc-client-ts user |
| 列表 / asOf | 每次进入页面向活 API 拉取，不进全局 store |
| 幂等键 | sessionStorage，按「一次有意操作」复用 |
| 扫码框焦点 | 组件局部 |

客户端：`GET/POST /api/wms/v1/...` → `routeFor` 到四服务前缀。解析 `CursorPage.items` 与错误体。数量字段只当字符串渲染。

演示行必须来自 seed 后的 API，测试 fixture 不得冒充演示数据。

## 7. 屏幕状态矩阵

每个作业页至少：

| 状态 | 行为 |
| --- | --- |
| loading | 保留过滤条件，按钮禁用 |
| empty | 写明当前仓与过滤，给出允许动作 |
| error / 5xx | 服务不可用，不伪装无权限 |
| forbidden / 401/403 | 说明拒绝；动隐藏但不替代服务端权威 |
| 202 accepted | 处理中 + operationId，禁止显示成功 |
| 409 | 最新记录 + 需重确认；不换幂等键 |
| stale | asOf / lagSeconds 提示刷新 |
| TCC | 「库存已预留，等待全局完成」，无强制释放 |
| PDA | 文字+tone，不能只靠颜色 |

## 8. 视觉 tokens（假设品牌）

企业作业台：侧栏深蓝、内容浅灰、密表、KPI 行数来自接口。

| token | 值 |
| --- | --- |
| `--ink` | `#122033` |
| `--aside` | `#10243c` |
| `--paper` | `#e8edf3` |
| `--card` | `#ffffff` |
| `--line` | `#d5deea` |
| `--accent` | `#1d6b8a` |
| `--ok` / `--warn` / `--err` | `#1a7a46` / `#9a6b12` / `#b42318` |
| 半径 | 6–8px |
| 字号 | 12 / 13 / 14 / 22 / 28（KPI） |

## 9. 视口策略

交接已要求同应用 PDA 页，因此不单做桌面、也不另开移动产品。

| 表面 | 宽度 | 行为 |
| --- | --- | --- |
| 桌面工作台 | ≥1280 | 左侧作业导航 + 顶栏选仓 + KPI / 密表 |
| 窄桌面 | 768–1279 | 导航横滑，表横向滚动 |
| PDA 路由 | ≥390 | 独立壳、大触控、扫码框 autofocus |

## 10. API / 认证 / 错误假设

- 认证：OIDC 授权码 + PKCE；空 issuer 不回退免认证
- 授权：令牌仓范围；UI 隐藏不可执行动作
- 错误体：`code` / `message` / `retryable`
- 写操作：`Idempotency-Key`；扫描 `scanSequence` 由后续切片接契约
- 时区：展示可按仓，请求 UTC
- TP99：本切片新增的写接口预算 unverified；前端不宣称达标

作业详情提交已落地命令：入库收货/质检/上架，出库拣包发与未拣取消，调拨发出/授权/接收/损耗，盘点冻结点数审批调整，任务回收/领取，对账 APPROVE/REJECT。跨仓 ALLOCATED 仍要求 TC Committed 证据，页面不伪造确认。OpenAPI `GET /warehouses/{id}/tasks` 仍未实现，出库任务挂在出库单详情。

## 11. 未决

- 设备 UNKNOWN 与真实硬件仍 blocked（S8-05）
- 主数据写 API 未实现，catalog 保持只读
- 履约整单确认依赖真实 TC，控制台不能写成 ALLOCATED
- AC-26 全链路现场走查（收货→上架→跨仓→拣→部分发→剩余取消）尚未用活数据验收，不是 50 AC accepted
