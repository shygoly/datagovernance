# AGENTS.md — 仓库导航地图

> 面向编码智能体与协作者的结构化入口（严格控制在 100 行内；详细规约按需读取目标文档）。

## 1. 先读宪法与权威元语文档

- **最高红线（必读）**：`CONSTITUTION.md` — 10 条不可妥协公理（C1–C10）与 Constitution Check
- **元语总索引**：`ARCHITECTURE.md` — 术语表、运行时拓扑、模块边界与变更协议
- **过程元语（8 Space）**：`SPACE-MODEL.md` + `docs/space-contracts/*.yaml`
- **治理元语（7 原语）**：`GOVERNANCE-MODEL.md` — Asset / Fact / Standard / Rule / Policy / Lineage / Event
- **智能体共性基座（A0）**：`AGENT-PRIMITIVES.md` — 9+1 融合映射、A0.1–A0.4、OWASP ASI 与工具链
- **跨系统与协议契约**：`INTEGRATION-ROADMAP.md` + `contracts/`（OpenAPI、Events、MCP、A2A）
- **前端设计元语**：`frontend/DESIGN.md` + `frontend/src/styles/tokens.css`
- **分析报告对象**：`docs/analysis-report.md`

## 2. 仓库目录拓扑

| 路径 | 职责与元语归属 | 关键约束 |
|---|---|---|
| `backend/dg-common` | 共享内核（响应、错误码、`WorkspaceContext`、加密） | 零 Spring Web 依赖 |
| `backend/dg-platform` | Platform Space（租户、用户、角色、菜单、凭据） | 凭据唯一解密入口 |
| `backend/dg-data-spi` | Data Space 契约与只读 SQL 护栏 | 零驱动依赖 |
| `backend/dg-data-connectors` | JDBC / 文件 / 对象存储连接器 | 不解释业务语义 |
| `backend/dg-metadata` | Metadata Space（数据源、目录、血缘、敏感、词汇表、语义本体与知识图谱） | 不可见 `dg-data-connectors` |
| `backend/dg-runtime` | Runtime Space（统一 Execution、执行器、制品） | 不可见 `dg-metadata`/`dg-control` |
| `backend/dg-control` | Control Space（任务定义、编译、调度、下发） | 不直接执行物理任务 |
| `backend/dg-governance` | Governance Space（监控、告警、审计、分析报告） | 不可见 `dg-control`，不发命令 |
| `backend/dg-app` | 唯一装配层（REST、拦截器、Flyway `V1–V37+`、SPA） | 代理 Agent 与原生语义问数网关 |
| `agent-service/` | Pi Agent Node sidecar（`:8090`，多轮会话/SSE/工具） | 不直连数据库；仅调 Java 内部端点 |
| `frontend/` | Vue 3 + TS + Element Plus + Pinia + ECharts | 只消费 `/api/v1`；严守 `--dg-*` |
| `contracts/` | 跨系统与智能体协议契约（`openapi/`、`events/`、`mcp/`、`a2a/`） | 变更须同步校验契约结构 |
| `skills/` | A0.2 渐进式技能包（`SKILL.md`） | 按需激活禁全量预加载；最小 `allowed-tools` |
| `knowledge/` | A0.1/A0.3 编译型知识库（`raw/`、`research/`、`articles/`） | 先查 `index.md`；新沉淀仅写 `research/` |
| `evals/` | A0.4 智能体轨迹评测集（`regression/`、`capability/`、`graders/`） | 核心回归以 `pass^3` 终态 Outcome 为门禁 |
| `openspec/` | 增量行为规约（`specs/`、`changes/`、`archive/`） | 提案须含 Non-goals 与 Constitution Check |
| `docs/space-contracts/` | 8 份机器可读 Space Contract YAML + `verify.py` | 架构变更必重跑 |
| `scripts/` | 元语防漂移校验、验收脚本与评测脚本 | 变更后按域执行对应校验 |

## 3. 核心构建与防漂移校验命令

```bash
# 1. 元语与护栏综合校验（Space 契约 + 宪法/导航 + Maven 边界 + Skill + Token + 知识库 + MCP/A2A）
python3 scripts/verify-primitives.py

# 2. Space Contract 单独校验
python3 docs/space-contracts/verify.py

# 3. 后端编译与全量测试（Java 21 + Spring Boot 3.3）
mvn -pl backend/dg-app -am test

# 4. 前端类型检查与生产构建（Vue 3 + TS）
cd frontend && pnpm type-check && pnpm build

# 5. Agent sidecar 类型检查
cd agent-service && npx tsc --noEmit

# 6. 智能体轨迹回归门禁（离线 pass^3）
python3 scripts/run-agent-evals.py --suite regression --trials 3 --mode offline
```

## 4. 变更硬纪律（摘要）

1. **术语纪律**：代码与 API 一律用 `Workspace` 表示租户，禁止用 `Space` 命名运行时对象；新术语先写入 `ARCHITECTURE.md` §1。
2. **规约与契约先行**：修改逻辑前核对 `openspec/specs/` 与 `docs/space-contracts/*.yaml` / Maven 依赖；重大需求先在 `openspec/changes/` 立项。
3. **前端纪律**：禁止在视图中硬编码 `#409eff` / `#f5f7fa` 等字面量色值，统一用 `var(--dg-*)` 与 `.page-container`。
4. **安全纪律**：Agent 工具端点必须按 `userId + workspaceId` 实时校验成员资格与权限，禁止共享服务账号与聊天流内直接完成高危确认。
5. **禁止裸改权威知识库**：分析或排障沉淀只能写入 `knowledge/research/`（或候选 `Fact`）并走审校/PR，严禁直接覆写 `knowledge/articles/`（C9）。
6. **报错即指令**：遇到 `verify-primitives.py`、`verify.py` 或评测门禁报错，直接按输出指引重构，严禁通过 `--no-verify` 或注释校验绕过门禁。
7. **诚实汇报**：任何“已完成”声明必须附带实际运行通过的命令输出、迁移脚本或测试验证记录。
