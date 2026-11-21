#!/usr/bin/env bash
# 本地开发启动：backend | frontend | agent | all
# 用法：
#   ./scripts/dev-start.sh backend
#   ./scripts/dev-start.sh frontend
#   ./scripts/dev-start.sh agent
#   ./scripts/dev-start.sh all          # 三个终端提示；默认只打印命令
#   ./scripts/dev-start.sh all --bg     # 后台起后端+前端+agent（日志写 logs/）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# --- JDK 21（与 deploy.md 一致；勿用 17/25）---
if [[ -z "${JAVA_HOME:-}" ]]; then
  for cand in \
    "$HOME/.local/jdk"/jdk-21*/Contents/Home \
    "/Library/Java/JavaVirtualMachines"/temurin-21.jdk/Contents/Home \
    "/opt/homebrew/opt/openjdk@21"
  do
    # shellcheck disable=SC2086
    for p in $cand; do
      if [[ -x "$p/bin/java" ]]; then
        export JAVA_HOME="$p"
        break 2
      fi
    done
  done
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# --- 密钥：复用 .env.local，禁止每次重生成 DG_SECRET_KEY ---
ensure_env_local() {
  if [[ ! -f "$ROOT/.env.local" ]]; then
    umask 077
    cat > "$ROOT/.env.local" <<EOF
DG_SECRET_KEY=$(openssl rand -base64 32)
DG_JWT_SIGNING_KEY=$(openssl rand -base64 48)
DG_ADMIN_PASSWORD=admin123
DG_AGENT_TOKEN=dev-agent-token
DG_PORT=8080
DG_DB_URL=jdbc:postgresql://localhost:5432/datagovernance
DG_DB_USER=datagov
DG_DB_PASSWORD=datagov
# 问数 Agent（可选）
# DEEPSEEK_API_KEY=
DEEPSEEK_MODEL=deepseek-flash
DG_BACKEND_URL=http://127.0.0.1:8080
EOF
    echo "[OK] 已生成 $ROOT/.env.local （请按需改 DG_ADMIN_PASSWORD / DEEPSEEK_API_KEY）"
  fi
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env.local"
  set +a
  export DG_AGENT_TOKEN="${DG_AGENT_TOKEN:-dev-agent-token}"
  export DG_BACKEND_URL="${DG_BACKEND_URL:-http://127.0.0.1:8080}"
  export DG_PORT="${DG_PORT:-8080}"
}

# 说明：在仓库根目录执行 `mvn -pl … spring-boot:run` 会落到 dg-parent 上
#（Unable to find a suitable main class）。可靠做法：先 install，再 -f dg-app。
build_backend() {
  echo "[BUILD] mvn -pl backend/dg-app -am -DskipTests install …"
  mvn -pl backend/dg-app -am -DskipTests install
}

cmd_backend() {
  ensure_env_local
  if ! command -v java >/dev/null || ! java -version 2>&1 | grep -q '"21\.'; then
    echo "[WARN] 未检测到 Java 21。当前: $(java -version 2>&1 | head -1 || echo none)"
    echo "       请 export JAVA_HOME 指向 JDK 21 后再启动。"
  fi
  build_backend
  echo "[RUN] 后端 dg-app → http://127.0.0.1:${DG_PORT}  (Swagger /swagger-ui.html)"
  exec mvn -f backend/dg-app/pom.xml spring-boot:run
}

cmd_frontend() {
  ensure_env_local
  cd "$ROOT/frontend"
  if [[ ! -d node_modules ]]; then
    pnpm install
  fi
  echo "[RUN] 前端 Vite → http://127.0.0.1:5173  (代理 /api → ${DG_API_TARGET:-http://localhost:8080})"
  exec pnpm run dev
}

cmd_agent() {
  ensure_env_local
  cd "$ROOT/agent-service"
  if [[ ! -d node_modules ]]; then
    npm install
  fi
  if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
    echo "[WARN] 未设置 DEEPSEEK_API_KEY，问数对话可能失败；可写入 .env.local 后重试。"
  fi
  echo "[RUN] Agent sidecar → http://127.0.0.1:8090  (DG_BACKEND_URL=$DG_BACKEND_URL)"
  exec env \
    DG_BACKEND_URL="$DG_BACKEND_URL" \
    DG_AGENT_TOKEN="$DG_AGENT_TOKEN" \
    DEEPSEEK_MODEL="${DEEPSEEK_MODEL:-deepseek-flash}" \
    npm start
}

print_all_commands() {
  ensure_env_local
  cat <<EOF

开三个终端分别执行：

  # 1) 后端
  cd $ROOT && ./scripts/dev-start.sh backend

  # 2) 前端
  cd $ROOT && ./scripts/dev-start.sh frontend

  # 3) Agent（智能问数，可选）
  cd $ROOT && ./scripts/dev-start.sh agent

就绪后：
  UI     http://127.0.0.1:5173
  API    http://127.0.0.1:${DG_PORT}/swagger-ui.html
  Agent  http://127.0.0.1:8090/health

灌 Synthea/CHANCE 本体种子（后端起来后）：
  python3 scripts/seed-clinical-multisource.py

EOF
}

bg_all() {
  ensure_env_local
  mkdir -p "$ROOT/logs"
  echo "[BG] 日志目录 $ROOT/logs"

  # 后端：fat jar + nohup。已有 jar 时跳过 install（FORCE_BUILD=1 强制重编）。
  set -a; source "$ROOT/.env.local"; set +a
  JAR="$ROOT/backend/dg-app/target/dg-app-0.1.0-SNAPSHOT.jar"
  : >"$ROOT/logs/backend.log"
  if [[ "${FORCE_BUILD:-}" == "1" || ! -f "$JAR" ]]; then
    echo "[BUILD] $(date -Iseconds) install dg-app …" | tee -a "$ROOT/logs/backend.log"
    mvn -pl backend/dg-app -am -DskipTests install >>"$ROOT/logs/backend.log" 2>&1
  else
    echo "[BUILD] skip (jar exists); FORCE_BUILD=1 to rebuild" | tee -a "$ROOT/logs/backend.log"
  fi
  echo "[RUN] $(date -Iseconds) java -jar $JAR" >>"$ROOT/logs/backend.log"
  nohup java -jar "$JAR" >>"$ROOT/logs/backend.log" 2>&1 &
  echo $! >"$ROOT/logs/backend.pid"
  disown $! 2>/dev/null || true
  echo "[BG] backend pid=$(cat "$ROOT/logs/backend.pid") → logs/backend.log"

  # 直接跑 vite/tsx 二进制（cwd 必须在模块根），避免 pnpm/npm 包装进程退出收割子进程
  if [[ ! -d "$ROOT/frontend/node_modules" ]]; then
    (cd "$ROOT/frontend" && pnpm install)
  fi
  # shellcheck disable=SC2094
  nohup bash -c "cd \"$ROOT/frontend\" && exec ./node_modules/.bin/vite --host 127.0.0.1 --port 5173" \
    >"$ROOT/logs/frontend.log" 2>&1 &
  echo $! >"$ROOT/logs/frontend.pid"
  disown $! 2>/dev/null || true
  echo "[BG] frontend pid=$(cat "$ROOT/logs/frontend.pid") → logs/frontend.log"

  if [[ ! -d "$ROOT/agent-service/node_modules" ]]; then
    (cd "$ROOT/agent-service" && npm install)
  fi
  nohup bash -c "cd \"$ROOT/agent-service\" && set -a && source \"$ROOT/.env.local\" && set +a && \
    export DG_BACKEND_URL=\"\${DG_BACKEND_URL:-http://127.0.0.1:8080}\" \
           DG_AGENT_TOKEN=\"\${DG_AGENT_TOKEN:-dev-agent-token}\" \
           DEEPSEEK_MODEL=\"\${DEEPSEEK_MODEL:-deepseek-flash}\" && \
    exec ./node_modules/.bin/tsx src/server.ts" \
    >"$ROOT/logs/agent.log" 2>&1 &
  echo $! >"$ROOT/logs/agent.pid"
  disown $! 2>/dev/null || true
  echo "[BG] agent pid=$(cat "$ROOT/logs/agent.pid") → logs/agent.log"

  echo "停止：kill \$(cat logs/backend.pid logs/frontend.pid logs/agent.pid)"
  echo "探活：curl -s http://127.0.0.1:8080/actuator/health ; curl -s -o /dev/null -w '%{http_code}\\n' http://127.0.0.1:5173/ ; curl -s http://127.0.0.1:8090/health"
}

usage() {
  echo "Usage: $0 {backend|frontend|agent|all} [--bg]"
  exit 1
}

TARGET="${1:-}"
FLAG="${2:-}"
case "$TARGET" in
  backend)  cmd_backend ;;
  frontend) cmd_frontend ;;
  agent)    cmd_agent ;;
  all)
    if [[ "$FLAG" == "--bg" ]]; then
      bg_all
    else
      print_all_commands
    fi
    ;;
  *) usage ;;
esac
