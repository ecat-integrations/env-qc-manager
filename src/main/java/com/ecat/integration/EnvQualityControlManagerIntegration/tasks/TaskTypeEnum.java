package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import lombok.Getter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * TaskTypeEnum
 *
 * @author caohongbo
 * @description
 */ // 任务类型m枚举
public enum TaskTypeEnum {
    AUTO("0", "自动任务"),
    MANUAL("1", "手动任务"),
    REMOTE("3", "远程任务"),
    LIVE("2", "现场任务");


    @Getter
    private final String code;
    @Getter
    private final String name;

    TaskTypeEnum(String code, String name) {
        this.code = code;
        this.name = name;
    }

    // 返回所有枚举code
    public static Set<String> getAllTaskTypeCodes() {
        return Arrays.stream(values()).map(TaskTypeEnum::getCode).collect(Collectors.toSet());
    }
}
