import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildDistributionProfileBlock,
  containsRawPii,
  validateUiBlock,
} from './uiBlocks.js'

test('C7: accepts whitelisted FilterFunnelCard and DistributionProfileCard blocks', () => {
  const funnel = validateUiBlock({
    component: 'FilterFunnelCard',
    version: '1',
    props: {
      title: '纳排漏斗',
      steps: [
        { label: '全量患者', count: 1000, retentionRatio: 1.0 },
        { label: '有效检验记录', count: 988, retentionRatio: 0.988 },
      ],
    },
  })
  assert.ok(funnel)
  assert.equal(funnel.component, 'FilterFunnelCard')

  const dist = buildDistributionProfileBlock({
    id: 'dpr_1',
    datasetId: 'cd_1',
    datasetName: 'lab_orders',
    verdict: 'ANOMALIES_DETECTED',
    totalRows: 1000,
    truncated: false,
    anomalyCount: 1,
    patternFingerprint: 'pat_abc',
    nullRates: [{ column: 'unit', nullCount: 0, nullRatio: 0, anomalous: false }],
    anomalies: [{ dimension: 'UNIT_ENUM_HETEROGENEITY', columnName: 'unit', violationCount: 12, violationRatio: 0.012 }],
  })
  assert.ok(dist)
  assert.equal(dist.component, 'DistributionProfileCard')
})

test('C7: rejects unknown component and executable HTML/JS payloads', () => {
  assert.equal(validateUiBlock({ component: 'RawHtmlWidget', version: '1', props: {} }), null)
  assert.equal(
    validateUiBlock({
      component: 'RuleDiffProposalCard',
      version: '1',
      props: { title: '<script>alert(1)</script>' },
    }),
    null,
  )
  assert.equal(
    validateUiBlock({
      component: 'FilterFunnelCard',
      version: '1',
      props: { label: 'javascript:void(0)' },
    }),
    null,
  )
})

test('C5: detects unmasked phone and ID card numbers', () => {
  assert.equal(containsRawPii('patient phone 13812345678'), true)
  assert.equal(containsRawPii('id 310101199001011234'), true)
  assert.equal(containsRawPii('masked 138****5678'), false)
})
