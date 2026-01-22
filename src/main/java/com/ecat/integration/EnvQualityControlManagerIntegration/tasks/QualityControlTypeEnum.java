package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import lombok.Getter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * QualityControlTypeEnum
 *
 * @author caohongbo
 * @description
 */ // 质控类型枚举
public enum QualityControlTypeEnum {
    ZERO_CHECK("0", "zero_check", "air.monitor.calibration.zero_check", "零点校准"),
    SPAN_CHECK("1", "span_check", "air.monitor.calibration.span_check", "跨度校准"),
    MULTI_CHECK("2", "multi_check", "air.monitor.calibration.multi_check", "多点检查"),
    PRECISION_CHECK("3", "precision_check", "air.monitor.calibration.precision_check", "精密度检查"),
    ACCURACY_CHECK("4", "accuracy_check", "air.monitor.calibration.accuracy_check", "准确度校准"),
    CONVERSION_CHECK("5", "conversion_check", "air.monitor.calibration.conversion_check", "转换率检查"),
    AUDIT_SPAN_CHECK("6", "audit_span_check", "air.monitor.calibration.audit-span-check", "人工核查");

    @Getter
    private final String code;
    @Getter
    private final String name;
    @Getter
    private final String className;
    @Getter
    private final String displayName;

    QualityControlTypeEnum(String code, String name, String className, String displayName) {
        this.code = code;
        this.name = name;
        this.className = className;
        this.displayName = displayName;
    }

    // 返回所有质控类型的name Set
    public static Set<String> getAllQualityControlTypeNameSet() {
        return Arrays.stream(values()).map(QualityControlTypeEnum::getName).collect(Collectors.toSet());
    }
}
