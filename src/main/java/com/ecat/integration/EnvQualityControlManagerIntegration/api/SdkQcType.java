package com.ecat.integration.EnvQualityControlManagerIntegration.api;

/**
 * SDK 质控类型词汇（闭域，§3 域 2）：八种质控核查类型。内部存储双形态——qcm_plan.qc_type 落
 * snake_case 名、qcm_record.quality_control_type 落数字码 0~7，SDK 契约只认本枚举，两种存储
 * 形态的互译收口在 SDK 实现装配边界。
 *
 * @author coffee
 */
public enum SdkQcType {

    /** 零点检查（内部存储码 0） */
    ZERO_CHECK,

    /** 跨度检查（内部存储码 1） */
    SPAN_CHECK,

    /** 多点检查（内部存储码 2） */
    MULTI_CHECK,

    /** 精密度检查（内部存储码 3） */
    PRECISION_CHECK,

    /** 准确度检查（内部存储码 4） */
    ACCURACY_CHECK,

    /** 转换率检查（内部存储码 5，锁定 NO2） */
    CONVERSION_CHECK,

    /** 人工核查（内部存储码 6） */
    AUDIT_SPAN_CHECK,

    /** 多仪器零点质控（内部存储码 7，1~4 台并行） */
    MULTI_ZERO_CHECK
}
