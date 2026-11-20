/**
 * 统一从 tokens.css 读取 --dg-* CSS 变量供 ECharts 使用，
 * 严格遵守 frontend/DESIGN.md 与 verify-primitives.py 零硬编码色值约束。
 */
export function cssVar(name: string, fallback: string): string {
  if (typeof window === 'undefined') {
    return fallback
  }
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

export function getInsightChartTheme() {
  const primary = cssVar('--dg-primary', 'rgb(64, 158, 255)')
  const success = cssVar('--dg-success', 'rgb(103, 194, 58)')
  const warning = cssVar('--dg-warning', 'rgb(230, 162, 60)')
  const danger = cssVar('--dg-danger', 'rgb(245, 108, 108)')
  const info = cssVar('--dg-info', 'rgb(144, 147, 153)')
  const text = cssVar('--dg-text', 'rgb(48, 49, 51)')
  const textSecondary = cssVar('--dg-text-secondary', 'rgb(96, 98, 102)')
  const textMuted = cssVar('--dg-text-muted', 'rgb(144, 147, 153)')
  const border = cssVar('--dg-border', 'rgb(228, 231, 237)')
  const surface = cssVar('--dg-surface', 'rgb(255, 255, 255)')

  return {
    palette: [primary, success, warning, danger, info, textSecondary],
    primary,
    success,
    warning,
    danger,
    info,
    text,
    textSecondary,
    textMuted,
    border,
    surface,
  }
}
