# 数据治理平台 · 前端 DESIGN.md

> 本文是 `frontend/` 的设计源文档。它定义 **设计元语（design meta-language）**：
> 颜色、字体、间距、组件与交互的稳定词汇。页面实现可以变化，但这些词不能悄悄变化。
>
> 参考 `~/projects/followup/followup-nx/apps/web-frontend/DESIGN.md` 的「Clinical Calm」
> 组织方式，但本产品是数据治理 + AI 问数控制台，不照搬医院随访的品牌与业务规则。

## 1. 设计方向

**受治理的精密控制台（Governed Precision）**——瑞士精密仪器 × 临床台账气质（视觉基线由 Stitch Design System 沉淀，详见 `.stitch/DESIGN.md`）。

- **氛围与密度**：高信息吞吐（密度 `7/10`）但不拥挤，以 `1px` 极细分隔线划分结构层次，替代厚重阴影；
- **色彩克制**：深石墨导航轨（禁用纯黑 `#000000`）、雪花石冷灰画布、纯白工作表面，仅保留**单一钴蓝主强调色**（饱和度 < 70%），语义色严格留给状态、敏感分级（L1–L4）与溯源徽标；
- **精密数据呈现**：表格、字段血缘、六维正交度量、SQL/YAML 投影与四层校验闸门是视觉主角，数字/编码/哈希强制等宽对齐（`tabular-nums`）；
- **明确禁用**：紫色/青色 AI 霓虹渐变、数据表上叠玻璃拟态、表情符号（Emoji）、3 列等宽营销大卡片与虚构指标数字、`Inter` 与衬线字体、无意义的装饰性弹跳动效；
- 业务人员看「AI 问数 / 看板」，工程与治理人员看「数据源 / 语义本体 / 血缘 / 敏感分级」，两类页面共享同一套视觉元语。

## 2. 目标范围

实现目标：

- `frontend/`
- Vue 3 + TypeScript + Vite
- Element Plus
- Pinia + Vue Router
- ECharts（问数图表）

不要：

- 在 `backend/` 里写前端样式；
- 绕过 Element Plus 重新造一套基础组件；
- 绕过平台 `/api/v1` 直连任何外部 BI/数据库或将 SuperSonic UI 嵌为界面（SuperSonic 已彻底退场）。

## 3. 设计元语（Meta-language）

这些词是评审和 agent 提示里的共同语言：

| 元语 | 中文含义与职责 | 实现落点 |
|---|---|---|
| Canvas | 页面最底层冷灰画布 | `--dg-canvas` / `body` |
| Surface | 承载业务内容的纯白工作面 | `--dg-surface` / `el-card` |
| Ink | 主墨色文字与高强调标题（非纯黑） | `--dg-text` |
| Muted | 元数据、时间戳、摘要哈希与辅助说明 | `--dg-text-muted` |
| Primary | 单一主操作色（提交、保存、新建等，单页最多 1 个主 CTA） | `--dg-primary` / `el-button--primary` |
| Semantic | 治理状态色（通过/待复核/阻断/中性） | `--dg-success/warning/danger/info` 及 `*-soft` |
| Divider | 1px 极细结构分隔线与表格横线 | `--dg-border` |
| Rhythm | 页面留白与区块垂直节奏 | `--dg-space-*` |
| Density | 表格与表单的紧凑信息密度 | `size="small"` + `36–40px` 行高 + `.tabular-nums` |
| Elevation | 扁平结构优先，仅浮层使用冷色微阴影 | `shadow="never"` + `--dg-shadow-overlay` |
| Radius | 克制圆角体系（标签 4px / 按钮输入 6px / 卡片抽屉 8px） | `--dg-radius-sm` / `--dg-radius` / `--dg-radius-lg` |
| Shell | 应用壳层（220px 深石墨侧栏 + 56px 顶栏 + 主画布 + 右侧 AgentDock） | `layouts/MainLayout.vue` |
| SummaryStrip | 极细竖线分隔的单行治理概览条（替代 3 列等宽营销大卡片） | 工作台/本体页顶栏下方 |
| ProvenanceBadge | 字段证据来源徽标（库约束 / 知识图谱 / 历史 SQL / LLM 推断含人审锁） | 语义本体与度量检视面板 |
| VerificationGate | 四层证伪闸门状态胶囊（G1–G5 / 反模式 / 统计证伪 / 6 类探针） | 度量发布与复核队列 |
| Breadcrumb | 顶栏多级路径导航（菜单树父子链 + 详情父路由） | `MainLayout.vue` 顶栏左侧 |
| Overlay | 5 级分层浮层（轻提示 / 二次确认 / 短弹窗 / 侧滑抽屉 / 宿主审批） | `utils/confirm.ts`、`el-dialog`、`el-drawer` |
| HostConsent | C7 宿主独立高危确认视图（顶部琥珀/深红审慎色条，不可被 Agent 篡改） | `components/agent/AgentConsentDialog.vue` |

新词先加到本表，再写代码；不要用“那个灰蓝色的东西”这类不可检索的描述。

## 4. 设计 Tokens

唯一来源：`frontend/src/styles/tokens.css`。

### 4.1 颜色

| Token | 值 | 中文名称与用途 |
|---|---:|---|
| `--dg-canvas` | `#f7f8fa` | 雪花石画布：页面最底层背景 |
| `--dg-surface` | `#ffffff` | 纯白纸面：卡片、表格、编辑器、弹窗容器 |
| `--dg-surface-muted` | `#f1f3f6` | 瓷灰弱背景：表头、代码块、嵌套面板底色 |
| `--dg-sidebar` | `#161922` | 深石墨导航轨：左侧导航背景（禁用纯黑 `#000000`） |
| `--dg-sidebar-active` | `#232838` | 石墨选中块：左侧导航选中项背景（配 3px 钴蓝左竖条） |
| `--dg-sidebar-text` | `#a3aebf` | 雾灰导航字：未选中导航文字 |
| `--dg-sidebar-text-active` | `#ffffff` | 导航选中文字 |
| `--dg-text` | `#181c24` | 炭黑主墨色：标题、正文、表格主值 |
| `--dg-text-secondary` | `#525b6c` | 板岩次要字：辅助标签、说明文字 |
| `--dg-text-muted` | `#7c8698` | 铅灰元数据：时间戳、摘要哈希、面包屑 |
| `--dg-border` | `#e5e8ee` | 极细分隔线：1px 面板/表格/顶栏边框 |
| `--dg-border-strong` | `#cfd6e2` | 控件强调边框：输入框与次按钮描边 |
| `--dg-primary` | `#2456b8` | 钴蓝主强调色：主按钮、选中 Tab、焦点框 |
| `--dg-primary-hover` | `#1d4696` | 钴蓝悬停态 |
| `--dg-primary-soft` | `#edf2fc` | 钴蓝浅洗底：表格选中行、活跃筛选胶囊 |
| `--dg-success` / `*-soft` | `#178058` / `#ecf8f3` | 校验绿：`PUBLISHED`、`PASS`、`db_constraint` |
| `--dg-warning` / `*-soft` | `#b86b14` / `#fdf6ec` | 审慎琥珀：`PROPOSED`、`STALE_REVIEW`、`LOW_CONFIDENCE`、`L3`、`llm_inference` |
| `--dg-danger` / `*-soft` | `#c23b3b` / `#fdf1f1` | 审计深红：`FAIL`、闸门阻断、`L4`、删除拦截 |
| `--dg-info` / `*-soft` | `#4b5565` / `#f2f4f7` | 中性板岩：`DRAFT`、`DEPRECATED`、`INCONCLUSIVE`、`historical_sql` |

规则：

- 严禁在视图/组件中直接写 `#2456b8`、`#409eff`、`#f5f7fa`、`#000000` 等字面量色值，一律走 `var(--dg-*)`；
- 全站仅使用一个主强调色（钴蓝），禁止 AI 紫/青霓虹渐变与按钮外发光；
- 单页工具栏最多 1 个主 CTA 按钮，避免满屏蓝色；
- 卡片保持纯白纸面 + `1px` 极细边框；弱背景只用于表头、代码块与嵌套说明。

### 4.2 字体

- 无衬线（Sans）：`--dg-font-sans`（`Geist` + `PingFang SC` / `Noto Sans SC`，**禁用 `Inter` 与衬线字体**）；
- 等宽（Mono）：`--dg-font-mono`（`Geist Mono` / `JetBrains Mono`），强制用于 SQL、YAML 投影、规范对象编码、SHA-256 摘要、时间戳与**全部表格数字/指标列**（`.tabular-nums`）；
- 页面标题：`20px / 600`（字距 `-0.02em`）；
- 区块标题：`15px / 600`；
- 正文与表格：`13–14px / 400`（长说明行宽 ≤ `65ch`）；
- 元数据与表头：`12px / 500`，`--dg-text-muted`。

### 4.3 间距、圆角与布局

| Token | 值 | 用途 |
|---|---:|---|
| `--dg-page-padding` | `16px` | `.page-container` 内边距 |
| `--dg-sidebar-width` | `220px` | 左侧深石墨导航轨（可折叠至 `64px`） |
| `--dg-header-height` | `56px` | 顶部粘性工具栏 |
| `--dg-radius-sm` / `--dg-radius` / `--dg-radius-lg` | `4px` / `6px` / `8px` | 微标签 / 按钮输入框 / 卡片与抽屉圆角 |
| `--dg-space-xs` / `sm` / `md` / `lg` / `xl` | `4` / `8` / `12` / `16` / `24px` | 统一空间节奏 |

- 页面统一使用 `.page-container`，不要在视图里重复写 `padding: 16px`；
- 页面主块之间用 `16px`，卡片内部用 `12px`；
- 复杂工作台推荐采用**非对称 65/35 分栏**（左侧高密度列表 + 右侧检视抽屉），顶栏下方用单条水平极细分隔概览条（`SummaryStrip`），禁用 3 列等宽营销大卡片。

## 5. 布局骨架与壳层交互（App Shell）

```text
┌─────────────────────────────────────────────┐
│ Sidebar 220px │ Topbar 60px                  │
│ (MenuTree)    │ [Breadcrumb] [WS] [UserMenu] │
│               ├─────────────────────────────┤
│               │ Main: .page-container        │
│               │   page header                │
│               │   stats / filters            │
│               │   card/table                 │
│               │   detail drawer              │
└─────────────────────────────────────────────┘
```

- **侧边栏菜单（NavigationMenu）**：由后端 `pf_permission` 菜单树驱动（`MenuTree.vue`），启用 `unique-opened`，禁止在前端硬编码完整导航；详情页通过路由 `meta.parentPath` 保持对应父菜单高亮；
- **面包屑（Breadcrumb）**：由后端菜单树自动反查当前路由父子路径生成（如 `数据资产 / 语义本体`）；详情页（如 `/metadata/assets/:id`）拼接 `meta.parentPath` 形成可回退的多级路径（如 `数据资产 / 数据目录 / 资产详情`），中间路由级可点击，末级当前页不可点；
- **登录 / 退出 / 切换空间（Session Shell）**：
  - 登录页（`/login`）为独立全屏画布，不挂载 `MainLayout`；
  - 401/令牌失效时由 `api/request.ts` 统一清理会话并带 `redirect` 跳 `/login`；
  - 退出登录必须经 `confirmAction` 二次确认，成功后同时清空 `auth` 与 `agentSession` 并跳转 `/login`；
  - 切换 Workspace（顶栏空间选择器）必须调用后端换票接口重签令牌，同时清空 `agentSession`、用 `workspaceId` 隔离 `KeepAlive` 缓存键并回 `/workbench`，防止跨租户数据残留；
- **主内容区**：`overflow: auto`，表格横向滚动留在卡片内部；`MainLayout.vue` 严禁硬编码 `#263445` / `#fff` 等色值，统一使用 `var(--dg-sidebar-active)`、`var(--dg-surface)` 等 Token。

## 6. 组件规则

### 6.1 页面与卡片

- 每个页面以 `.page-container` 开始；
- 主内容用 `el-card shadow="never"`，不堆叠多层阴影；
- 卡片头用简洁标题 + 右侧操作，不使用大段说明；
- 复杂表单优先页面/抽屉；短表单可用 `el-dialog`，但不要所有 CRUD 都弹窗。

### 6.2 表格

- 默认 `size="small"`，紧凑但可读；
- 主实体列在前，状态列用 `el-tag`，操作列右对齐；
- 时间列区分「创建时间 / 更新时间 / 业务时间」，不要只写“时间”；
- 空数据用 `el-empty` 或简短空状态，不用插画；
- 长文本 `show-overflow-tooltip`，不要撑破布局。

### 6.3 表单

- 标签在上（`label-position="top"`），与 Element Plus 默认一致；
- 必填、校验错误在字段下方，不只靠 toast；
- 主操作放右侧或底部，取消在左、提交在右；
- 保存中禁用提交，防重复；
- 凭据/密钥字段使用 `type="password"`，不回显明文。

### 6.4 状态与反馈

- 成功：绿色 `el-tag` / `ElMessage.success`；
- 警告：橙色 `el-tag`；危险：红色；
- 中性/停用：灰色；
- 加载：表格 `v-loading`；按钮 `:loading`；
- 错误必须可定位：表单字段错误 + 页面/接口消息，不能只在 toast 里一闪而过。

### 6.5 SQL / 代码块

- 使用 `<pre>` + `--dg-font-mono`；
- 背景 `--dg-surface-muted`，圆角 `--dg-radius`；
- 长 SQL 横向滚动，不强行换行；
- 只读展示，不提供可编辑 SQL 入口（除专用 SQL 控制台）。

### 6.6 AI 问数与交互图表

- 输入区：语义主题/数据集选择 + 多行问题 + 主按钮；
- 结果区顺序：语义证据条（口径快照/排除条件/审计指纹）→ 摘要 → 交互图表 → 明细表 → SQL；
- 必须显示：`state`、耗时、是否截断、脱敏列；有敏感列时用警告条明确“哪些列被遮蔽”；
- 图表（C7 宿主白名单）：由后端 `ChartSpecRecommender` 确定性推荐，`InsightAnswerChart.vue` 渲染 10 类白名单图表（`kpi` / `bar` / `horizontal_bar` / `stacked_bar` / `line` / `area` / `pie` / `scatter` / `funnel` / `table`），多单位指标自动启用左右双 Y 轴，色板通过 `utils/chartTheme.ts` 读取 `--dg-*` Token；
- 三层图表交互：L1 单击零延迟过滤下方明细表并展开紧凑上下文操作条；L2 沿维度 `hierarchy` 下钻直调 `/api/v1/insight/ask`（零 LLM）并附面包屑回退；L3 仅在显式点击「AI 归因分析」时交由 Pi Agent；
- 导出按钮只在有结果时出现，并受 `insight:export` 权限控制；
- 看板：将问题定义、`chartSpec` 与 `executedSpec` 固化至 `layoutJson`，刷新时优先走确定性语义编译（零 LLM）。

### 6.7 看板

- 左侧看板列表，右侧详情/问题项；刷新结果卡片以“交互图表 + 明细表”呈现并支持卡片内 L1 联动、L2 下钻与跳转 L3 归因；
- 版本、刷新、ACL 是明确动作，不藏在右键菜单；
- 自动刷新默认关闭，开启时显示 60s 节奏；
- 单题失败显示错误，不阻塞其他题。

### 6.8 浮层与弹出框分层规约（Overlay Matrix）

所有弹出与浮层交互必须按以下 5 级严格选用，禁止混用：

| 级别 | 组件 / API | 适用场景 | 硬约束 |
|---|---|---|---|
| L1 轻量反馈 | `ElMessage` | 成功提示、非阻断提醒 | 不可作为表单或接口错误的唯一展示手段 |
| L2 二次确认 | `confirmAction` / `confirmDanger`（`utils/confirm.ts`） | 删除、退出登录、移出收件箱、停用、晋升确认 | **严禁页面直接裸调 `ElMessageBox.confirm`**，防止用户点取消产生未捕获 Promise rejection |
| L3 短表单 / 轻预览 | `el-dialog` | 1–5 字段的新建/保存、版本快照只读查看 | 宽度 `460px–560px`，取消在左、提交在右，提交按钮绑定 `:loading` |
| L4 详情 / 复杂配置 | `el-drawer` | 对象详情审阅、ACL 授权、目录浏览、多 Tab 编辑 | 避免弹窗套弹窗；保持列表上下文不丢失 |
| L5 AI 高危审批（C7） | `AgentConsentDialog` | Agent 提案的写操作、权限变更、发布 | 宿主独立渲染、不可被 Agent 篡改；严禁在聊天流中口头确认 |

## 7. 导航与权限

- 菜单树由后端 `pf_permission` 下发；
- 路由 `meta.permission` 决定能否直接访问 URL；
- 无权限跳 `/403`，未登录跳 `/login`；
- 不把敏感内容渲染在权限检查之前；
- 空间（Workspace）是租户，切换空间必须换后端令牌。

## 8. 交互与动效

- 动效只用于状态提示：展开、加载、成功；
- 不做页面级编排动画；
- hover 用背景/边框变化，不用大幅缩放；
- 弹窗只用于短表单、确认、轻量预览；
- 删除、导出、发布等不可逆动作要二次确认；
- 修改列表数据后重新拉取列表，不依赖组件残留状态。

## 9. 响应式

- 桌面优先，侧边栏固定；
- 窄屏折叠为抽屉/紧凑菜单；
- 表格允许横向滚动；
- 操作栏可换行，不溢出；
- 图表容器高度固定，宽度跟随卡片。

## 10. Agent 提示词

```text
只改 frontend/。使用 Vue 3 + TypeScript + Element Plus。
遵守 frontend/DESIGN.md 的「受治理的精密控制台（Governed Precision）」、App Shell 与 5 级 Overlay 规约；
使用 var(--dg-*) 和 .page-container，严禁写死颜色或使用纯黑/紫色渐变/表情符号；二次确认统一用 confirmAction / confirmDanger。
页面结构：紧凑页头（≤1 主 CTA）→ 单行极细分隔概览条 → 非对称工作台/高密度表格 → 侧滑检视抽屉。
表格紧凑、数字与编码列用 .tabular-nums、状态与溯源用语义徽标、加载优先骨架屏、错误不只靠 toast。
AI 问数结果顺序：证据条 → 摘要 → 10 类白名单交互图表 → 明细表 → SQL；显式标注脱敏列。
不要绕过 RBAC 菜单；不要生成可执行前端代码（C7）；不要在浏览器里放服务账号口令。
改完运行 pnpm type-check && pnpm build 与 python3 scripts/verify-primitives.py --strict。
```

## 11. Do / Don't

Do：

- 用 `var(--dg-*)`（含 `layouts/`、`views/`、`components/`）；
- 用 `el-card shadow="never"`；
- 用 `el-table size="small"`；
- 用语义色表达状态；
- 用 `.page-container`；
- 用 `confirmAction` / `confirmDanger` 做二次确认；
- 用 `formatDateTime` 统一时间格式；
- 用后端菜单/权限驱动导航与多级面包屑；
- 改完跑 `pnpm type-check`、`pnpm build` 与 `python3 scripts/verify-primitives.py --strict`。

Don't：

- 不写死 `#409eff` / `#f5f7fa` / `#263445`；
- 不在视图/组件中直接裸调 `ElMessageBox.confirm`；
- 不用紫色渐变、玻璃拟态、营销卡片；
- 不把完整详情塞在长表格下面；
- 不只靠 toast 报错；
- 不把凭据/密钥写进前端代码或日志；
- 不绕过 `AgentConsentDialog` 在聊天流内完成高危写操作确认（C7）；
- 不绕过 `meta.permission` 直接进页面。

## 12. 变更协议

- 新颜色/间距/圆角：先改 `tokens.css`，再改本文件；
- 新共享组件：先在本文件写规则，再实现；
- 新页面：必须说明使用的元语与布局模式；
- 任何视觉重构：保留现有信息密度与 Element Plus 语义，不要为了“好看”降低可读性。
