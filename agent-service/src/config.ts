import { existsSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

/**
 * 本地开发便捷加载 Agent 专属与仓库根级 .env.local（均已在 .gitignore，严禁入库）。
 * 按候选顺序合并，先出现的配置优先，仅填充尚未在 process.env 中显式注入的键。
 */
function loadDotEnvLocal() {
  const candidates = [
    resolve(process.cwd(), '.env.local'),
    resolve(process.cwd(), '..', '.env.local'),
  ]
  for (const file of candidates) {
    if (!existsSync(file)) continue
    try {
      const raw = readFileSync(file, 'utf-8')
      for (const line of raw.split(/\r?\n/)) {
        const trimmed = line.trim()
        if (!trimmed || trimmed.startsWith('#')) continue
        const eq = trimmed.indexOf('=')
        if (eq <= 0) continue
        const key = trimmed.slice(0, eq).trim()
        let val = trimmed.slice(eq + 1).trim()
        if ((val.startsWith("'") && val.endsWith("'")) || (val.startsWith('"') && val.endsWith('"'))) {
          val = val.slice(1, -1)
        }
        if (key && process.env[key] === undefined) {
          process.env[key] = val
        }
      }
    } catch {
      // 忽略读取异常，回退到环境变量默认值
    }
  }
}

loadDotEnvLocal()

export const config = {
  port: Number(process.env.AGENT_PORT ?? 8090),
  backendUrl: (process.env.DG_BACKEND_URL ?? 'http://127.0.0.1:18080').replace(/\/+$/, ''),
  agentToken: process.env.DG_AGENT_TOKEN ?? 'dev-agent-token',
  deepseekApiKey: process.env.AGENT_DEEPSEEK_API_KEY ?? process.env.DEEPSEEK_API_KEY ?? '',
  deepseekModel: process.env.DEEPSEEK_MODEL ?? 'deepseek-flash',
  dataDir: process.env.AGENT_DATA_DIR ?? join(process.cwd(), 'data', 'agent'),
  skillsDir: process.env.SKILL_DIR ?? resolve(process.cwd(), '..', 'skills'),
  defaultMaxToolCallsPerTurn: Number(process.env.AGENT_MAX_TOOL_CALLS ?? 12),
  defaultMaxConsecutiveErrors: Number(process.env.AGENT_MAX_CONSECUTIVE_ERRORS ?? 2),
  otelCaptureContent: process.env.DG_OTEL_CAPTURE_CONTENT === 'true',
  /** C5 按密级模型路由标识：SELF_HOSTED（私有/内网托管）或 EXTERNAL（公网外部 API）。 */
  modelEndpointKind: (process.env.AGENT_MODEL_ENDPOINT_KIND?.toUpperCase() === 'SELF_HOSTED'
    ? 'SELF_HOSTED'
    : 'EXTERNAL') as 'SELF_HOSTED' | 'EXTERNAL',
}
