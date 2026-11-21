# Design: Data-Driven Ops Profile & Flow IR（`data-driven-ops-profile`）

## 1. 总体分层架构与 Space 切分

```text
┌────────────────────────────────────────────────────────────────────────────┐
│ Layer A: 静态声明契约（Metadata Space · /metadata/semantic Layer 3）       │
│  ├─ Target (md_semantic_object: object_type='TARGET')                      │
│  │    ├─ Invariants[]  (硬不变量：引用 Rule / Metric 阈值红线)             │
│  │    └─ Objectives[]  (软目标：Metric + 方向 MAX/MIN + 权重)              │
│  ├─ ActionType (md_semantic_object: object_type='ACTION_TYPE')             │
│  │    ├─ authLevel: L0_ADVISORY | L1_AUTO | L2_APPROVAL | L3_DUAL_APPROVAL │
│  │    ├─ domainScope: PLATFORM_INTERNAL | EXTERNAL_EXEMPTED                │
│  │    ├─ paramEnvelopeSchema (JSON Schema + 数值界/白名单/速率上限)         │
│  │    ├─ dryRunSpec (预演探针契约)                                          │
│  │    ├─ compensatingActionRef (配对补偿动作)                               │
│  │    └─ defaultCooldownSec (默认实体冷却期)                                │
│  └─ FilterPreset (命名可复用 SMQ Filter / Cohort 条件模板)                 │
└───────────────────────────────────┬────────────────────────────────────────┘
                                    │ 引用（不污染静态本体）
┌───────────────────────────────────▼────────────────────────────────────────┐
│ Layer B: 控制与裁决（Control Space · /autonomy/*）                         │
│  ├─ TriggerSpec（指标阈值 / 质检事件 / 数据到达 + 去抖窗口）               │
│  ├─ FlowSpec IR（声明式控制环 JSON/YAML IR，支持静态编译校验）             │
│  ├─ FlowCompiler（静态校验：包络/补偿/冷却/权限/不变量/无环）              │
│  └─ Arbiter（两阶段裁决：硬不变量否决 → 实体锁/冷却 → 规则表 → L2 升级）   │
└───────────────────────────────────┬────────────────────────────────────────┘
                                    │ 下发已编译计划
┌───────────────────────────────────▼────────────────────────────────────────┐
│ Layer C: 持久化工作流执行（Runtime Space · DurableFlowEngine SPI）         │
│  ├─ 默认实现：PgDurableFlowEngine（PG 状态机 Checkpoint + Timer + Lock）   │
│  ├─ 预留 SPI：TemporalFlowEngineAdapter / RestateFlowEngineAdapter         │
│  └─ 无状态原子 Activity（暂停同步 / 发起质检 / 生成报表 / 发送工单）       │
└───────────────────────────────────┬────────────────────────────────────────┘
                                    │ 事件与证据流
┌───────────────────────────────────▼────────────────────────────────────────┐
│ Layer D: 观测、影子回测与熔断（Governance Space · /autonomy/traces）       │
│  ├─ DecisionTrace（触发快照 → 裁决 → Dry-Run → 审批 → 执行 → T+1 回检）    │
│  ├─ ShadowEvaluator（影子模式对比真实 T+1 指标产出回测报告）               │
│  └─ KillSwitchBridge（对接 V33：急停一键将全租户 L1_AUTO 降级回 SHADOW）   │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 语义本体 Layer 3 扩展（`TARGET` & `ACTION_TYPE`）

直接复用 [V36__semantic_ontology_kernel.sql](file:///Users/mac/projects/datagovernance/backend/dg-app/src/main/resources/db/migration/V36__semantic_ontology_kernel.sql) 的 `md_semantic_object`、`md_semantic_link` 与 `md_semantic_object_version`（天然获得双时间态版本、内容摘要、RLS 隔离与审批流）：

1. **`object_type = 'TARGET'`**：
   - `spec_json` 结构：
     ```json
     {
       "targetEntityRef": "sem_entity_sync_job",
       "invariants": [
         { "code": "INV_NO_L4_UNMASKED", "ruleRef": "rule_l4_mask", "severity": "HARD_BLOCK" }
       ],
       "objectives": [
         { "metricRef": "sem_metric_freshness_sla", "direction": "MINIMIZE", "threshold": 300, "weight": 0.7 },
         { "metricRef": "sem_metric_quality_pass_rate", "direction": "MAXIMIZE", "threshold": 0.99, "weight": 0.3 }
       ]
     }
     ```
   - 通过 `md_semantic_link`（`CONSTRAINS` / `AFFECTS`）绑定到对应 `ENTITY` 与 `METRIC`。

2. **`object_type = 'ACTION_TYPE'`**：
   - `spec_json` 结构：
     ```json
     {
       "actionCode": "PAUSE_SYNC_JOB",
       "domainScope": "PLATFORM_INTERNAL",
       "authLevel": "L2_APPROVAL",
       "activityRef": "control.job.pause",
       "paramEnvelope": {
         "required": ["jobId", "reason"],
         "properties": {
           "jobId": { "type": "string" },
           "maxPauseMinutes": { "type": "integer", "minimum": 5, "maximum": 1440 }
         },
         "maxExecutionsPerDay": 10
       },
       "dryRunMode": "SIMULATE_IMPACT",
       "compensatingActionCode": "RESUME_SYNC_JOB",
       "defaultCooldownSec": 1800,
       "verifyDelaySec": 86400
     }
     ```

---

## 3. 声明式 `FlowSpec IR` 规约（D3）

`FlowSpec IR` 是连接 `Metadata`、`Control`、`Runtime` 与 `A0 Agent` 的唯一流程契约，采用确定性 JSON Schema（版本化 `schemaVersion: "flow.ir/v1"`）：

```json
{
  "schemaVersion": "flow.ir/v1",
  "flowCode": "quality_anomaly_auto_guard",
  "runMode": "SHADOW",
  "entityKeyExpr": "trigger.datasetId",
  "cooldownSec": 3600,
  "arbiterPolicyRef": "arbiter_default_governance",
  "steps": [
    { "id": "s1_snapshot", "kind": "CAPTURE_CONTEXT", "metrics": ["quality_pass_rate", "row_count_delta"] },
    { "id": "s2_invariant", "kind": "CHECK_INVARIANT", "targetRef": "target_core_dataset_sla" },
    { "id": "s3_dry_run", "kind": "DRY_RUN_ACTION", "actionTypeRef": "action_pause_sync_job" },
    { "id": "s4_arbiter", "kind": "ARBITER_DECIDE", "fallbackToL2": true },
    { "id": "s5_approval", "kind": "WAIT_HUMAN_SIGNAL", "timeoutSec": 14400, "skipWhen": "runMode == 'SHADOW' || authLevel == 'L1_AUTO'" },
    { "id": "s6_execute", "kind": "INVOKE_ACTIVITY", "actionTypeRef": "action_pause_sync_job", "skipWhen": "runMode == 'SHADOW'", "compensateWith": "action_resume_sync_job" },
    { "id": "s7_verify", "kind": "DELAYED_VERIFY", "delaySec": 86400, "verifyTargetRef": "target_core_dataset_sla" }
  ]
}
```

### `FlowCompiler` 编译期静态闸门（C2 / C3）
1. **存在性闸门**：所有 `targetRef`、`actionTypeRef`、`metricRef` 必须处于 `PUBLISHED` 状态；
2. **安全与 C6 闸门**：`domainScope == EXTERNAL_EXEMPTED` 时校验租户白名单开关；未开启直接编译失败；
3. **包络与补偿闸门**：`L2/L3` 或有副作用的 `L1` 动作必须声明 `paramEnvelope`；可逆动作必须声明 `compensateWith`；
4. **防震荡闸门**：必须声明 `entityKeyExpr` 与 `cooldownSec > 0`；
5. **灰度晋升闸门**：新发布或核心参数变更后的版本默认锁定 `runMode = SHADOW`，不允许跳过影子回测直接设为 `L1_AUTO`。

---

## 4. `DurableFlowEngine` SPI 与 PG 持久化状态机（D3）

### 4.1 引擎 SPI（可无缝切换 Temporal / Restate）
```java
public interface DurableFlowEngine {
    String startInstance(CompiledFlowPlan plan, TriggerContext ctx, UserContext initiator);
    void sendSignal(String instanceId, String signalName, Map<String, Object> payload, UserContext actor);
    void cancelInstance(String instanceId, String reason, UserContext actor);
    FlowInstanceView inspect(String instanceId);
}
```

### 4.2 默认实现：`PgDurableFlowEngine` 核心表结构（Flyway 迁移）
- **`au_trigger_def`**：触发器定义（指标/质检事件源、SMQ 条件、去抖窗口、绑定的 `flow_id`、启停状态）。
- **`au_flow_def` / `au_flow_version`**：`FlowSpec IR` 版本化存储、`run_mode`（`SHADOW / L2_ASSISTED / L1_AUTO`）、编译摘要与晋升审计。
- **`au_flow_instance`**：持久化工作流实例（`status`: `RUNNING / WAITING_APPROVAL / WAITING_VERIFY / COMPENSATING / COMPLETED / FAILED / SHADOW_COMPLETED / CANCELLED`、`current_step_id`、`checkpoint_json`、`wake_at`、`entity_key`）。
- **`au_entity_lock_cooldown`**：实体级在途互斥锁（唯一索引 `workspace_id + entity_key`）与冷却截止时间 `cooldown_until`，通过 PG 事务原子抢锁，杜绝并发震荡。
- **`au_decision_trace`**：不可变决策因果链（`trace_id`、`trigger_snapshot_json`、`arbiter_decision_json`、`dry_run_result_json`、`proposal_id`（关联 `V32`）、`activity_result_json`、`verify_status`: `PENDING / IMPROVED / NO_EFFECT / DEGRADED / INCONCLUSIVE`、`reproducibility_fingerprint`）。

---

## 5. 两阶段确定性裁决（Arbiter）与影子灰度闭环（D4 / D5）

1. **Arbiter 执行顺序**：
   - **Stage 1（硬拦截）**：评估 `Target.invariants` → 任一不满足直接 `REJECT(INVARIANT_VIOLATED)` 并记入 `DecisionTrace`；
   - **Stage 2（防震荡）**：检查 `au_entity_lock_cooldown` → 在途冲突或冷却期内直接 `SUPPRESSED(IN_FLIGHT_OR_COOLDOWN)`；
   - **Stage 3（优先级规则表）**：多触发/多动作命中同一实体时按确定性优先级与互斥矩阵裁决；若命中冲突且无明确规则覆盖，自动将 `authLevel` 抬升至 `L2_APPROVAL`，调用 A0 Agent 生成结构化归因建议（只读），交由人类在治理收件箱裁决。
2. **影子模式与 Kill Switch 联动**：
   - `SHADOW` 模式下完整运行 `s1 → s4`（含 `Dry-Run`）并跳至 `s7_verify`（T+1 延迟回检），落盘 `DecisionTrace(run_mode=SHADOW)`；
   - 回检结果达到阈值且经人工审核后，通过受控晋升动作切换至 `L2_ASSISTED`（对接现有 `V32` `ig_agent_action_proposal` + `AgentConsentDialog`）；
   - 当 `V33` `ig_agent_control_policy.kill_switch_enabled = true` 时，自动拦截所有 `L1_AUTO` 真实副作用并一键降级回 `SHADOW`。

---

## 6. 前端信息架构与页面规划（D1）

- **语义本体（`/metadata/semantic`）**：新增 Layer 3 页签/筛选维度 **“目标与动作契约（Target / ActionType）”**，支持在语义图谱中查看 `Entity/Metric ↔ Target ↔ ActionType` 关联。
- **新建一级菜单域：数据自治（`/autonomy/*`）**：
  1. `/autonomy/triggers`：触发器与命名过滤器（`FilterPreset`）管理；
  2. `/autonomy/flows`：`FlowSpec IR` 编排与灰度控制台（展示 `SHADOW / L2_ASSISTED / L1_AUTO` 状态、冷却配置、Dry-Run 试跑与晋升审批）；
  3. `/autonomy/traces`：**DecisionTrace 审计与回检台**（时间线还原：触发快照 → Arbiter 裁决 → Dry-Run → 宿主审批 → 执行/补偿 → T+1 效果归因）；
  4. 审批任务直接汇入现有 `/workbench/inbox`（治理收件箱）与 `AgentConsentDialog.vue`，不造第二套审批入口。
