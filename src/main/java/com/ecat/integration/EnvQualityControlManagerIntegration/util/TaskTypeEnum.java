package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import lombok.Getter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 质控任务类型（records 页「触发来源」词汇，展示名与 dict quality_control_task_type 标签对齐）
 *
 * @author coffee
 */
public enum TaskTypeEnum {
    AUTO("0", "计划触发"),
    MANUAL("1", "手动触发"),
    REMOTE("3", "远程平台触发"),
    LIVE("2", "现场任务");

    @Getter
    private final String code;
    @Getter
    private final String name;

    TaskTypeEnum(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public static Set<String> getAllTaskTypeCodes() {
        return Arrays.stream(values()).map(TaskTypeEnum::getCode).collect(Collectors.toSet());
    }

    /**
     * 按落库 code（qcm_record.task_type）查展示名。
     *
     * @param code 任务类型码
     * @return 展示名；未知 code 返回 null（调用方自行回退，如导出透传原值），不猜默认
     */
    public static String displayNameOf(String code) {
        for (TaskTypeEnum e : values()) {
            if (e.code.equals(code)) {
                return e.name;
            }
        }
        return null;
    }
}
