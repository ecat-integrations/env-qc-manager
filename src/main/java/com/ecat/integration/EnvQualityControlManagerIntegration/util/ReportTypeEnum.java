package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import lombok.Getter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 报告类型
 *
 * @author coffee
 */
public enum ReportTypeEnum {
    ZERO_SPAN("1", "zero_span", "零点和跨度检查", "ReportD2"),
    MULTI("2", "multi", "多点检查", "ReportD3"),
    PRECISION("3", "precision", "精密度审核", "ReportD4"),
    ACCURACY("4", "accuracy", "准确度审核", "ReportD5"),
    CONVERSION("5", "conversion", "转换率检查", "ReportD6"),
    CALIBRATION("6", "calibration", "校准", "ReportD7"),
    AUDIT_SPAN("7", "audit_span", "人工核查", "ReportD1");

    @Getter
    private final String code;
    @Getter
    private final String name;
    @Getter
    private final String displayName;
    @Getter
    private final String component;

    ReportTypeEnum(String code, String name, String displayName, String component) {
        this.code = code;
        this.name = name;
        this.displayName = displayName;
        this.component = component;
    }

    /** 按库存 report_type 数字码（"1".."7"）查枚举；未知码返回 null（严格模式，不猜测兜底）。 */
    public static ReportTypeEnum findByCode(String code) {
        if (code == null) {
            return null;
        }
        for (ReportTypeEnum t : values()) {
            if (t.code.equals(code.trim())) {
                return t;
            }
        }
        return null;
    }

    public static Set<String> getAllReportTypeSet() {
        return Arrays.stream(values()).map(ReportTypeEnum::name).collect(Collectors.toSet());
    }
}
