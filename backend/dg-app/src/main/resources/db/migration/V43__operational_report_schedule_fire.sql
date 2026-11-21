-- W5: OperationalReportSchedule 下次触发 / 上次触发（供装配层 cron 消费）

ALTER TABLE gv_operational_report_schedule
    ADD COLUMN IF NOT EXISTS next_fire_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_fire_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_gv_op_schedule_due
    ON gv_operational_report_schedule (enabled, next_fire_at)
    WHERE enabled = TRUE;

COMMENT ON COLUMN gv_operational_report_schedule.next_fire_at IS '下次计划触发时刻；由 upsert/fireDue 推进';
COMMENT ON COLUMN gv_operational_report_schedule.last_fire_at IS '上次实际触发时刻';
