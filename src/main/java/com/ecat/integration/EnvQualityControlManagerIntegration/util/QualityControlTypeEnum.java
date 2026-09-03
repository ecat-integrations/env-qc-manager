package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import lombok.Getter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 质控类型 */
public enum QualityControlTypeEnum {
    // 展示名用户拍板（2026-09 质控术语统一）：校准→检查；code/name/className 是协议与落库契约，绝不随展示名改
    ZERO_CHECK("0", "zero_check", "air.monitor.calibration.zero_check", "零点检查"),
    SPAN_CHECK("1", "span_check", "air.monitor.calibration.span_check", "跨度检查"),
    MULTI_CHECK("2", "multi_check", "air.monitor.calibration.multi_check", "多点检查"),
    PRECISION_CHECK("3", "precision_check", "air.monitor.calibration.precision_check", "精密度检查"),
    ACCURACY_CHECK("4", "accuracy_check", "air.monitor.calibration.accuracy_check", "准确度检查"),
    CONVERSION_CHECK("5", "conversion_check", "air.monitor.calibration.conversion_check", "转换率检查"),
    AUDIT_SPAN_CHECK("6", "audit_span_check", "air.monitor.calibration.audit-span-check", "人工核查"),
    /**
     * 多仪器零点质控（FR-02-14）：一次触发对 N 台分析仪（1~4）并行做零点核查。
     * composer 接线契约：composer 侧未来新增 multi_zero_check ExecutorType，以
     * {@code execute(instruments[], 零点参数)} 一次 flow 并行驱动 N 台、保持单飞模型
     * （同一时刻全局仍只允许一个校准 flow）。接线前该 className 在 ExecutorType 中
     * 无映射（getEnum 抛 IllegalArgumentException），编排器按 EXECUTOR_TYPE_NOT_READY
     * 闸前拒绝（N 条记录落 FAILED 留痕），不做任何猜测降级。
     */
    MULTI_ZERO_CHECK("7", "multi_zero_check", "air.monitor.calibration.multi_zero_check", "多仪器零点质控");

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

    public static Set<String> getAllQualityControlTypeNameSet() {
        return Arrays.stream(values()).map(QualityControlTypeEnum::getName).collect(Collectors.toSet());
    }

    /**
     * 按落库 code（qcm_record.quality_control_type 存数字码）查枚举。
     *
     * @param code 数字码
     * @return 枚举；未知 code 返回 null，由调用方决定回退形态（如导出透传原值），此处不做猜测默认
     */
    public static QualityControlTypeEnum fromCode(String code) {
        for (QualityControlTypeEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return null;
    }

    /**
     * 旧库遗留类型标识（G-STD-4）：历史记录 {@code quality_control_type} 直接存名 {@code "calibration_check"}
     * （臭氧校准设备量值传递记录），新记录一律存数字码。仅报告生成侧比对使用；不作为枚举常量加入，
     * 避免泄漏进计划/任务配置的类型白名单（getAllQualityControlTypeNameSet / PlanParamValidator）。
     */
    public static final String LEGACY_CALIBRATION_CHECK_TYPE = "calibration_check";
}
