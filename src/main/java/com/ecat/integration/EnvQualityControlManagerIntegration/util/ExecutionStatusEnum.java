package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import lombok.Getter;

/** 质控记录执行状态
 *
 * @author coffee
 */
public enum ExecutionStatusEnum {
    WAITING(0L, "等待中"),
    RUNNING(1L, "执行中"),
    SUCCESS(2L, "成功"),
    FAILED(3L, "失败"),
    STOPPING(4L, "手动中止"),
    /** 同日优先级让位（03 设计 §7.3）：当日被同类高优先级行覆盖，整行不执行留痕；非 SUCCESS，报表天然排除。 */
    SKIPPED(5L, "让位未执行");

    @Getter
    private final Long code;
    @Getter
    private final String displayName;

    ExecutionStatusEnum(Long code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }
}
