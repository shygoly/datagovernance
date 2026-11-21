-- W4: 跨路径可复现审计指纹（C8）
-- Insight / AnalysisReport / OperationalReportRun / ClinicalOperator 共用 schema（JSON TEXT）

ALTER TABLE gv_analysis_report_version
    ADD COLUMN IF NOT EXISTS reproducibility_fingerprint TEXT;

COMMENT ON COLUMN gv_analysis_report_version.reproducibility_fingerprint IS
    'C8 reproducibility_fingerprint JSON（schemaVersion/datasourceSnapshots/semanticObjectVersions/…）';
