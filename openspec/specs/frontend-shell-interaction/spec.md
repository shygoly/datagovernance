# frontend-shell-interaction Specification

## Purpose

定义前端应用壳层（App Shell）与全局交互元语的行为基线，涵盖多级面包屑、权限菜单联动、登录/退出/Workspace 切换会话流，以及 5 级浮层与二次确认规范，与 `frontend/DESIGN.md`、`docs/space-contracts/ui.yaml`、`CONSTITUTION.md`（C4/C7）保持一致。

## Requirements

### Requirement: 多级面包屑导航与菜单高亮联动

前端壳层 `MainLayout.vue` SHALL 基于后端下发的菜单树 `auth.menus` 与当前路由 `route.path` / `route.meta.parentPath` 生成多级面包屑，并保持侧边栏菜单正确高亮。

#### Scenario: 访问二级或更深菜单页面

- **WHEN** 用户导航到位于菜单树分组下的路由（例如 `/metadata/semantic`）
- **THEN** 顶栏面包屑展示完整父子路径（例如 `数据资产 / 语义本体`）
- **AND** 当前末级节点不可点击

#### Scenario: 访问不在侧边栏中的详情子路由

- **WHEN** 用户导航到声明了 `meta.parentPath` 的详情页（例如 `/metadata/assets/:id`，`parentPath: '/metadata/catalog'`）
- **THEN** 顶栏面包屑按 `parentPath` 对应菜单链路拼接当前页标题（例如 `数据资产 / 数据目录 / 资产详情`）
- **AND** 中间路由节点（`数据目录`）支持点击跳转回上级列表页
- **AND** 侧边栏保持 `parentPath` 对应菜单项高亮

### Requirement: 登录、退出与 Workspace 切换会话流

前端壳层与会话模块 SHALL 保证身份校验、租户隔离与退出清理的确定性。

#### Scenario: 退出登录二次确认与会话清理

- **WHEN** 用户点击顶栏用户菜单中的「退出登录」
- **THEN** 系统通过 `confirmAction` 弹出二次确认
- **AND** 若用户点击取消，不退出且控制台不产生未捕获 Promise rejection
- **AND** 若用户点击退出，清空 `auth` 与 `agentSession` 状态并跳转至 `/login`

#### Scenario: 切换 Workspace 租户

- **WHEN** 用户在顶栏选择另一个 `ACTIVE` 状态的 Workspace
- **THEN** 前端调用后端换票接口重签令牌
- **AND** 清空 `agentSession` 并通过 `workspaceId` 隔离 `KeepAlive` 缓存键
- **AND** 跳转回 `/workbench` 避免跨租户页面数据残留

#### Scenario: 未登录或 401 令牌失效

- **WHEN** 用户访问非 `meta.public` 路由但未认证或接口返回 401
- **THEN** 清理会话状态并携带 `redirect` 参数重定向至 `/login`

### Requirement: 浮层与确认框分级治理

前端页面与组件 SHALL 遵循 `frontend/DESIGN.md` §6.8 的 5 级浮层规约。

#### Scenario: 不可逆动作的二次确认

- **WHEN** 页面执行删除、移除授权、移出收件箱、停用或提案晋升等确认交互
- **THEN** 必须调用 `frontend/src/utils/confirm.ts` 的 `confirmAction` 或 `confirmDanger`
- **AND** 严禁在视图或业务组件中直接裸调 `ElMessageBox.confirm`
- **AND** 用户点击取消时静默返回 `false`，不产生未捕获异常

#### Scenario: AI 高危动作确认（CONSTITUTION C7）

- **WHEN** 智能体提出涉及写操作、权限变更或发布的提案
- **THEN** 必须由宿主前端独立组件 `AgentConsentDialog` 渲染结构化审批视图
- **AND** 严禁在聊天流中通过自然语言直接完成确认

### Requirement: 布局壳层 Design Token 防漂移

`frontend/src/layouts/` 与 `views/`、`components/` 一同受 `scripts/verify-primitives.py` 机械门禁保护。

#### Scenario: 壳层样式令牌化

- **WHEN** 运行 `python3 scripts/verify-primitives.py --strict`
- **THEN** 校验器扫描 `layouts/`、`views/`、`components/` 中的硬编码品牌/状态/侧边栏色值（含 `#263445`）以及裸 `ElMessageBox.confirm(` 调用
- **AND** 任何违规均输出明确修复指令并阻断 CI
