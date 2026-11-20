# 编译型知识库规约（A0.1 / A0.3 · `knowledge/schema.md`）

> 遵循 `CONSTITUTION.md` C9 与 `AGENT-PRIMITIVES.md §3`（llmWiki + Google OKF 便携式 Markdown 规范）。

## 三层目录纪律

1. **`raw/`（不可变原始源）**：只追加，每份原始文档登记 SHA-256 摘要，允许随时全量重编译。
2. **`research/`（暂存演进区 / Staging）**：Reflector 从外部验证反馈（`AnalysisReport(CONFIRMED)`、人工纠偏、测试验证）中提取的增量草稿必须先落于此，绝不允许直接写入 `articles/`。
3. **`articles/`（权威发布区 / Canonical）**：仅允许从 `research/` 经 Git Diff 人工审校与 `verify-primitives.py` 校验通过后晋升；严禁夹带 L3/L4 患者原始明文 PII。

## 必备账本

- `index.md`：全部权威文章的单行摘要目录（Query 首选入口）；
- `log.md`：只追加操作流水（Ingest / Promote / Lint / Rollback）；
- `maintenance-log.md`：记录人工采纳、拒绝或延缓的裁决理由，防止智能体重复提出已被否决的建议。
