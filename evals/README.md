# 智能体轨迹评测与回归门禁（A0.4 · `evals/`）

> 所属元语：`A0.4`（`AGENT-PRIMITIVES.md §3`、`CONSTITUTION.md C10`）。
> 核心原则：
> 1. 严格区分 **Task / Trial / Trajectory / Outcome**，以真实后端终态为裁判依据，不采信模型口头自述；
> 2. 确定性代码裁判优先，模型裁判（LLM-as-a-Judge）仅运行于独立上下文窗口；
> 3. 生产回归门禁以 **`pass^k`**（连续 $k$ 次独立试验全胜率，默认 `pass^3`）为红线。

## 目录结构

- `regression/cases.json`：CI 必过回归套件（权限隔离、敏感脱敏、技能门禁、宿主确认提案、熔断与拒答边界）
- `capability/cases.json`：探索性能力评测集（达标饱和后“毕业”晋升至 `regression/`）
- `graders/deterministic.py`：确定性代码裁判（校验终态 Outcome、脱敏列、越权拦截、技能与提案轨迹）
- `graders/llm_judge.py`：独立上下文模型裁判接口（Rubric 评分，离线模式提供确定性基线）

## 运行方式

```bash
# 离线回归门禁（3 次独立试验计算 pass^3，零外部模型依赖）
python3 scripts/run-agent-evals.py --suite regression --trials 3 --mode offline

# 在线对接本地 agent-service / dg-app 运行实测
python3 scripts/run-agent-evals.py --suite regression --trials 3 --mode live --base-url http://127.0.0.1:18080
```
