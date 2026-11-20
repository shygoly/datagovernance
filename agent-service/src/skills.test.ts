import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { isToolPermittedBySkills, loadSkills, renderSkillIndex } from './skills.js'
import { GenAiAttr, GEN_AI_SEMCONV_VERSION, startGenAiSpan } from './telemetry.js'

const SKILLS_DIR = resolve(process.cwd(), '..', 'skills')

describe('A0.2 SKILL.md progressive disclosure runtime', () => {
  it('loads repo skills and renders Level 1 index without full body', () => {
    const skills = loadSkills(SKILLS_DIR)
    assert.ok(skills.has('parquet-local-onboarding'))
    assert.ok(skills.has('insight-data-ask'))

    const index = renderSkillIndex(skills)
    assert.ok(index.includes('parquet-local-onboarding'))
    assert.ok(index.includes('insight-data-ask'))
    assert.ok(!index.includes('标准执行步骤'), 'Level 1 index must not inline Level 2 instructions')
  })

  it('blocks write tools until authorizing skill is activated', () => {
    const skills = loadSkills(SKILLS_DIR)
    const active = new Set<string>()

    const before = isToolPermittedBySkills('create_local_datasource', active, skills)
    assert.equal(before.allowed, false)
    assert.equal(before.requiredSkillHint, 'parquet-local-onboarding')

    // Read-only tools stay allowed
    assert.equal(isToolPermittedBySkills('list_datasets', active, skills).allowed, true)

    // Activating skill unlocks declared write tool
    active.add('parquet-local-onboarding')
    assert.equal(isToolPermittedBySkills('create_local_datasource', active, skills).allowed, true)
  })
})

describe('C1 OpenTelemetry GenAI semconv defaults', () => {
  it('locks semconv 1.42.0 and omits raw prompt/output by default', () => {
    process.env.DG_OTEL_LOG_SPANS = 'false'
    const span = startGenAiSpan({
      name: 'invoke_agent',
      workspaceId: 'ws_1',
      userId: 'usr_1',
      conversationId: 'conv_1',
      model: 'deepseek-flash',
      inputPreview: '敏感患者姓名与手机号 13800138000',
    })
    const record = span.end({ status: 'OK', outputPreview: '敏感输出' })
    assert.equal(record.attributes[GenAiAttr.SEMCONV_VERSION], GEN_AI_SEMCONV_VERSION)
    assert.equal(record.attributes[GenAiAttr.INPUT_MESSAGES], undefined)
    assert.equal(record.attributes[GenAiAttr.OUTPUT_MESSAGES], undefined)
  })
})
