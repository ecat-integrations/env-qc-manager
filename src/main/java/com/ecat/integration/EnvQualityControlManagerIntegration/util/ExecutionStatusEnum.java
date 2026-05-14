package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import lombok.Getter;

/** 质控记录执行状态 */
public enum ExecutionStatusEnum {
    WAITING(0L, "等待中"),
    RUNNING(1L, "执行中"),
    SUCCESS(2L, "成功"),
    FAILED(3L, "失败"),
    STOPING(4L, "中止中");

    @Getter
    private final Long code;
    @Getter
    private final String displayName;

    ExecutionStatusEnum(Long code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }
}
