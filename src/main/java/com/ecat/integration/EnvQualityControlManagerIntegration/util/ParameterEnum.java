package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import lombok.Getter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 质控参数（与分析仪逻辑入口对应） */
public enum ParameterEnum {
    SO2("1", "SO2"),
    NO2("2", "NO2"),
    O3("3", "O3"),
    CO("4", "CO"),
    PM10("5", "PM10"),
    PM2_5("6", "PM2.5");

    @Getter
    private final String code;
    @Getter
    private final String name;

    ParameterEnum(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public static String getNameByCode(String code) {
        return Arrays.stream(values()).filter(item -> item.getCode().equals(code)).findFirst().map(ParameterEnum::getName).orElse(null);
    }

    public static Set<String> getAllParameterNameSet() {
        return Arrays.stream(values()).map(ParameterEnum::getName).collect(Collectors.toSet());
    }
}
