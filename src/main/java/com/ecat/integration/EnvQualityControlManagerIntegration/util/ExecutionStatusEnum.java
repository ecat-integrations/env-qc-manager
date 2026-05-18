package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorResultBase;
import lombok.Getter;

/** 质控记录执行状态 */
public enum ExecutionStatusEnum {
    WAITING(0L, "等待中"),
    RUNNING(1L, "执行中"),
    SUCCESS(2L, "成功"),
    FAILED(3L, "失败"),
    STOPING(4L, "手动中止");

    @Getter
    private final Long code;
    @Getter
    private final String displayName;

    ExecutionStatusEnum(Long code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * 编排器在用户 stop 并完成恢复后返回的结果（{@link com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorStoppedException#toResult()}）。
     */
    public static boolean isUserManualStopOutcome(ExecutorResultBase result) {
        if (result == null) {
            return false;
        }
        String em = result.getErrorMessage();
        return em != null && em.contains("手动终止");
    }
}
