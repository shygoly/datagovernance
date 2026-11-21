package com.datagov.app.autonomy;

import java.util.function.Supplier;

/**
 * 防止自治 Activity 产生的副作用再次触发自治流程（例如“发起质检”触发另一个质检流程）。
 * 只在当前线程生效；异步 Activity 必须显式传递，不能依赖继承。
 */
public final class AutonomyExecutionGuard {

    private static final ThreadLocal<Boolean> IN_AUTONOMY_ACTIVITY = ThreadLocal.withInitial(() -> false);

    private AutonomyExecutionGuard() {
    }

    public static boolean active() {
        return Boolean.TRUE.equals(IN_AUTONOMY_ACTIVITY.get());
    }

    public static <T> T run(Supplier<T> action) {
        boolean previous = active();
        IN_AUTONOMY_ACTIVITY.set(true);
        try {
            return action.get();
        } finally {
            IN_AUTONOMY_ACTIVITY.set(previous);
        }
    }
}
