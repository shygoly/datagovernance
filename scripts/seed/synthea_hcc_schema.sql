-- CHANCE-HCC 专病表（MIMIC 已有表不造：无 admissions/icustays/labevents/prescriptions 克隆）
-- 目标库：mac@localhost:5432/synthea

CREATE SCHEMA IF NOT EXISTS synthea_hcc;

-- 移除早期 stub（与 MIMIC 同构的 patients/encounters），改专病表
DROP TABLE IF EXISTS synthea_hcc.imaging_report_zh CASCADE;
DROP TABLE IF EXISTS synthea_hcc.followup_mrecist CASCADE;
DROP TABLE IF EXISTS synthea_hcc.tace_session CASCADE;
DROP TABLE IF EXISTS synthea_hcc.hcc_diagnosis CASCADE;
DROP TABLE IF EXISTS synthea_hcc.cohort_patient CASCADE;
DROP TABLE IF EXISTS synthea_hcc.encounters CASCADE;
DROP TABLE IF EXISTS synthea_hcc.patients CASCADE;

-- 轻量队列锚点（非 MIMIC patients 克隆）
CREATE TABLE IF NOT EXISTS synthea_hcc.cohort_patient (
    patient_id          TEXT PRIMARY KEY,
    gender_std          TEXT NOT NULL,
    age_years           INTEGER NOT NULL,
    age_group_std       TEXT NOT NULL,
    race_std            TEXT NOT NULL DEFAULT 'ASIAN',
    synth_center_code   TEXT NOT NULL,
    survival_status     TEXT NOT NULL, -- Alive|Dead|Lost_to_FollowUp
    last_known_date     DATE,
    created_seed        BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS synthea_hcc.hcc_diagnosis (
    diagnosis_id            TEXT PRIMARY KEY,
    patient_id              TEXT NOT NULL REFERENCES synthea_hcc.cohort_patient (patient_id),
    icd_code                TEXT,
    hcc_etiology            TEXT NOT NULL,
    hcc_cnlc_stage          TEXT NOT NULL,
    hcc_child_pugh_class    TEXT NOT NULL,
    hcc_pvtt_grade          TEXT NOT NULL,
    hcc_extrahepatic_mets   BOOLEAN NOT NULL DEFAULT FALSE,
    tumor_count             INTEGER NOT NULL,
    max_tumor_diameter_cm   NUMERIC(6, 2) NOT NULL,
    baseline_afp_ng_ml      NUMERIC(12, 2),
    baseline_pivka_ii       NUMERIC(12, 2),
    albi_score              NUMERIC(6, 2),
    albi_grade              INTEGER,
    diagnosis_date          DATE NOT NULL
);

CREATE TABLE IF NOT EXISTS synthea_hcc.tace_session (
    session_id              TEXT PRIMARY KEY,
    patient_id              TEXT NOT NULL REFERENCES synthea_hcc.cohort_patient (patient_id),
    diagnosis_id            TEXT NOT NULL REFERENCES synthea_hcc.hcc_diagnosis (diagnosis_id),
    session_seq             INTEGER NOT NULL,
    modality                TEXT NOT NULL, -- cTACE|DEB-TACE|HAIC
    actual_exec_start       TIMESTAMPTZ NOT NULL,
    actual_exec_end_t0      TIMESTAMPTZ NOT NULL,
    embolic_agent           TEXT,
    lipiodol_volume_ml      NUMERIC(6, 2),
    systemic_regimen        TEXT,
    UNIQUE (patient_id, session_seq)
);

CREATE TABLE IF NOT EXISTS synthea_hcc.followup_mrecist (
    followup_id             TEXT PRIMARY KEY,
    patient_id              TEXT NOT NULL REFERENCES synthea_hcc.cohort_patient (patient_id),
    session_id              TEXT REFERENCES synthea_hcc.tace_session (session_id),
    visit_seq               INTEGER NOT NULL,
    visit_date              DATE NOT NULL,
    days_from_t0            INTEGER NOT NULL,
    mrecist_response        TEXT NOT NULL, -- CR|PR|SD|PD
    viable_target_sum_cm    NUMERIC(6, 2),
    afp_ng_ml               NUMERIC(12, 2),
    pivka_ii_mAU_ml         NUMERIC(12, 2),
    lost_to_followup        BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (patient_id, visit_seq)
);

CREATE TABLE IF NOT EXISTS synthea_hcc.imaging_report_zh (
    report_id               TEXT PRIMARY KEY,
    patient_id              TEXT NOT NULL REFERENCES synthea_hcc.cohort_patient (patient_id),
    session_id              TEXT REFERENCES synthea_hcc.tace_session (session_id),
    followup_id             TEXT REFERENCES synthea_hcc.followup_mrecist (followup_id),
    report_kind             TEXT NOT NULL, -- baseline|procedure|followup
    style_profile           TEXT NOT NULL,
    report_text             TEXT NOT NULL,
    generated_by            TEXT NOT NULL -- template|llm
);

CREATE INDEX IF NOT EXISTS idx_hcc_dx_patient ON synthea_hcc.hcc_diagnosis (patient_id);
CREATE INDEX IF NOT EXISTS idx_tace_patient ON synthea_hcc.tace_session (patient_id);
CREATE INDEX IF NOT EXISTS idx_fup_patient ON synthea_hcc.followup_mrecist (patient_id);
