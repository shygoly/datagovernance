# deploy.md — 运行时工具链与本地/私有化部署元语

> 记录本仓库开发、构建、测试与私有化部署所需的权威运行时环境与工具链约束。

## 1. JDK 21 工具链（后端编译与全量测试硬约束）

- **要求版本**：**Java 21（LTS）**（`pom.xml` 锁定 `maven.compiler.release=21`）。
- **本机已安装路径**：
  ```bash
  export JAVA_HOME="/Users/mac/.local/jdk/jdk-21.0.5+11/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
  ```
- **避坑说明**：
  - 不可使用 **JDK 17**：类文件版本 65.0（Java 21）无法在 Java 17（最高 61.0）下运行测试；
  - 不可使用 **JDK 25**：`maven-compiler-plugin` + Lombok（`TypeTag::UNKNOWN`）与 Mockito inline mock 在 JDK 25 上会报错；
  - macOS `/usr/libexec/java_home -v 21` 在同时装有 JDK 25 时会向上匹配到 JDK 25，因此须显式指定上述 `JAVA_HOME`。
- **标准后端测试命令**：
  ```bash
  JAVA_HOME=/Users/mac/.local/jdk/jdk-21.0.5+11/Contents/Home mvn -pl backend/dg-app -am test
  ```

## 2. 前端与 Agent Sidecar 运行时

| 组件 | 目录 | 运行时与工具链 | 构建 / 校验命令 |
|---|---|---|---|
| **前端控制台** | `frontend/` | Node.js + `pnpm`（Vue 3 + TS + Element Plus + ECharts） | `cd frontend && pnpm type-check && pnpm build` |
| **Pi Agent Sidecar** | `agent-service/` | Node.js（监听 `127.0.0.1:8090`，不直连数据库） | `cd agent-service && npx tsc --noEmit && npx tsx --test src/*.test.ts` |
| **元语与评测脚本** | `scripts/` | Python 3 + `pyyaml` | `python3 scripts/verify-primitives.py && python3 scripts/run-agent-evals.py --suite regression --trials 3 --mode offline` |

## 3. 关键环境变量（私有化与合规护栏）

| 变量名 | 默认值 | 说明 |
|---|---|---|
| `DG_BACKEND_URL` | `http://127.0.0.1:18080` | `agent-service` 回调用 Java 内部工具端点地址 |
| `DG_AGENT_TOKEN` | `dev-agent-token` | Java 与 Node sidecar 内部服务鉴权令牌（生产必须覆盖） |
| `AGENT_MODEL_ENDPOINT_KIND` | `EXTERNAL` | **C5 按密级模型路由标识**（`SELF_HOSTED` \| `EXTERNAL`）；涉及 L3/L4 敏感明细须配置 `SELF_HOSTED` |
| `DG_OTEL_CAPTURE_CONTENT` | `false` | **C10 遥测默认脱敏**：默认禁止采集原始 Prompt/输出文本 |
| `DG_EVOLUTION_RESEARCH_DIR` | （空，关闭写盘） | 可选：受控演进 Staging 草稿输出目录（仅限 `knowledge/research/`，物理禁写 `articles/`） |
| `DG_EVOLUTION_MAINTENANCE_LOG_PATH` | （空） | 可选：审校驳回防重提账本路径（`knowledge/maintenance-log.md`） |

## 4. 基础设施依赖

- **PostgreSQL**：元数据库与 Flyway 迁移（`V1–V44+`），启用行级安全策略（RLS，`V35` / `V44`）；单测通过 `EmbeddedPostgres` 自动拉起。
- **StarRocks / MinIO**：分析湖仓组件部署清单见 `deploy/starrocks/`。
