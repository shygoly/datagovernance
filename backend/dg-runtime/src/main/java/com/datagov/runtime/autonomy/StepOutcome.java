package com.datagov.runtime.autonomy;

public enum StepOutcome {
    CONTINUE,
    WAIT_APPROVAL,
    WAIT_VERIFY,
    COMPLETED,
    SHADOW_COMPLETED,
    FAILED
}
