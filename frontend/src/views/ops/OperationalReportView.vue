<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  operationalReportApi,
  type OperationalReportRunView,
  type OperationalReportView,
} from '@/api/operationalReport'

/**
 * 运营报表 MVP（≠ AnalysisReport）：发布定义 → 运行快照 → 导出 MD/CSV。
 */

const publishing = ref(false)
const running = ref(false)
const report = ref<OperationalReportView | null>(null)
const lastRun = ref<OperationalReportRunView | null>(null)

const form = reactive({
  code: 'weekly.demo',
  name: '示例周报',
  markdown: '本报告由运营报表引擎生成，块内禁止裸 SQL。',
  metricCode: 'metric.order.gmv',
})

async function publish() {
  publishing.value = true
  try {
    report.value = await operationalReportApi.publish({
      code: form.code.trim(),
      name: form.name.trim(),
      paramSchema: { week: { type: 'string' } },
      blocks: [
        { type: 'STATIC_MD', title: '概述', payload: { markdown: form.markdown } },
        {
          type: 'SMQ_BLOCK',
          title: '核心指标',
          payload: { metrics: [form.metricCode.trim()] },
        },
      ],
    })
    ElMessage.success(`已发布 ${report.value.code} v${report.value.latestVersion}`)
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败')
  } finally {
    publishing.value = false
  }
}

async function runOnce() {
  if (!report.value?.id) {
    ElMessage.warning('请先发布报表定义')
    return
  }
  running.value = true
  try {
    lastRun.value = await operationalReportApi.run(report.value.id, { week: '2026-W46' })
    if (lastRun.value.status === 'SUCCEEDED') {
      ElMessage.success('运行成功，已生成快照与指纹')
    } else {
      ElMessage.error(lastRun.value.errorMessage || '运行失败')
    }
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '运行失败')
  } finally {
    running.value = false
  }
}

function openExport(format: string) {
  if (!lastRun.value?.id) {
    ElMessage.warning('请先成功运行一次')
    return
  }
  window.open(operationalReportApi.exportUrl(lastRun.value.id, format), '_blank')
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">运营报表</h1>
        <p class="page-desc">
          参数化复合块编排与运行快照（非分析结论报告）。块类型：STATIC_MD / SMQ_BLOCK / COHORT_BLOCK / STATS_BLOCK。
        </p>
      </div>
    </header>

    <section class="panel">
      <h2 class="panel-title">发布定义</h2>
      <el-form label-width="100px" class="form">
        <el-form-item label="编码">
          <el-input v-model="form.code" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="概述 Markdown">
          <el-input v-model="form.markdown" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="度量 code">
          <el-input v-model="form.metricCode" placeholder="Catalog Grammar 内的 metric code" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="publishing" @click="publish">发布</el-button>
          <el-button :disabled="!report" :loading="running" @click="runOnce">运行</el-button>
          <el-button :disabled="lastRun?.status !== 'SUCCEEDED'" @click="openExport('md')">导出 MD</el-button>
          <el-button :disabled="lastRun?.status !== 'SUCCEEDED'" @click="openExport('csv')">导出 CSV</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section v-if="report" class="panel">
      <h2 class="panel-title">当前定义</h2>
      <dl class="meta">
        <div><dt>ID</dt><dd>{{ report.id }}</dd></div>
        <div><dt>版本</dt><dd>v{{ report.latestVersion }} · {{ report.status }}</dd></div>
        <div><dt>digest</dt><dd class="mono">{{ report.contentDigest }}</dd></div>
      </dl>
    </section>

    <section v-if="lastRun" class="panel">
      <h2 class="panel-title">最近运行</h2>
      <dl class="meta">
        <div><dt>状态</dt><dd>{{ lastRun.status }}</dd></div>
        <div><dt>指纹</dt><dd class="mono">{{ lastRun.reproducibilityFingerprint || '—' }}</dd></div>
      </dl>
      <pre v-if="lastRun.artifactMd" class="artifact">{{ lastRun.artifactMd }}</pre>
      <p v-if="lastRun.errorMessage" class="error">{{ lastRun.errorMessage }}</p>
    </section>
  </div>
</template>

<style scoped>
.page-header {
  margin-bottom: var(--dg-space-6, 24px);
}
.page-title {
  margin: 0;
  font-size: var(--dg-font-size-xl, 22px);
  color: var(--dg-text-primary, #1f2329);
}
.page-desc {
  margin: 8px 0 0;
  color: var(--dg-text-secondary, #646a73);
  font-size: var(--dg-font-size-sm, 13px);
}
.panel {
  margin-bottom: var(--dg-space-5, 20px);
  padding: var(--dg-space-5, 20px);
  background: var(--dg-bg-elevated, #fff);
  border: 1px solid var(--dg-border-subtle, #e5e6eb);
}
.panel-title {
  margin: 0 0 16px;
  font-size: var(--dg-font-size-md, 16px);
  color: var(--dg-text-primary, #1f2329);
}
.form {
  max-width: 720px;
}
.meta {
  display: grid;
  gap: 8px;
  margin: 0;
}
.meta dt {
  color: var(--dg-text-secondary, #646a73);
  font-size: 12px;
}
.meta dd {
  margin: 0;
}
.mono {
  font-family: var(--dg-font-mono, ui-monospace, monospace);
  word-break: break-all;
}
.artifact {
  margin-top: 12px;
  padding: 12px;
  background: var(--dg-bg-muted, var(--dg-fill-blank, #f7f8fa));
  color: var(--dg-text-primary, #1f2329);
  overflow: auto;
  white-space: pre-wrap;
}
.error {
  color: var(--dg-danger, #c45656);
}
</style>
