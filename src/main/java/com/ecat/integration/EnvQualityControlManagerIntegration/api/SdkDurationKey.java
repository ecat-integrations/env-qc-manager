package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import lombok.Getter;

/**
 * SDK 阶段时长覆盖键（闭域，§3 域 8）：计划参数表 14 键白名单的强类型化——旧契约 key 是裸
 * String，拼错一个字母静默失效（覆盖不生效也不报错），枚举让拼错在编译期暴露。枚举常量名是
 * SCREAMING_SNAKE 裁体，{@link #getKey()} 才是内部 flowParams/计划参数表的实际键（camelCase），
 * 互译收口在 SDK 实现装配边界。
 *
 * <p>值语义分两类：*Seconds/*Rounds/*Count 类须正整数（秒或次数）；两个 *PointPercents 类是
 * 0~1 严格升序、至少 2 项的序列（见各常量注）。白名单的一致性由 SDK 测试对校验器锁定。</p>
 *
 * @author coffee
 */
public enum SdkDurationKey {

    /** 指令下发后等待时长（秒） */
    COMMAND_DELAY_SECONDS("commandDelaySeconds"),

    /** 采样前稳定时长（秒） */
    STABLE_TIME_SECONDS("stableTimeSeconds"),

    /** 采样次数（次） */
    SAMPLE_COUNT("sampleCount"),

    /** 采样间隔（秒） */
    SAMPLE_INTERVAL_SECONDS("sampleIntervalSeconds"),

    /** 校准时长（秒） */
    CALIBRATION_TIME_SECONDS("calibrationTimeSeconds"),

    /** 验证前稳定时长（秒） */
    VERIFICATION_STABLE_TIME_SECONDS("verificationStableTimeSeconds"),

    /** 验证采样次数（次） */
    VERIFICATION_SAMPLE_COUNT("verificationSampleCount"),

    /** 零气打开后延迟（秒） */
    ZERO_GAS_OPEN_DELAY_SECONDS("zeroGasOpenDelaySeconds"),

    /** 恢复等待时长（秒） */
    RECOVERY_DELAY_SECONDS("recoveryDelaySeconds"),

    /** 精密度检查轮次（轮） */
    PRECISION_ROUNDS("precisionRounds"),

    /** 转换率检查轮次（轮） */
    CONVERSION_ROUNDS("conversionRounds"),

    /** GPT 时间（秒） */
    GPT_TIME_SECONDS("gptTimeSeconds"),

    /** 多点检查量程百分比序列（0~1 严格升序 ≥2 项） */
    MULTI_POINT_PERCENTS("multiPointPercents"),

    /** 准确度检查量程百分比序列（0~1 严格升序 ≥2 项） */
    ACCURACY_POINT_PERCENTS("accuracyPointPercents");

    @Getter
    private final String key;

    SdkDurationKey(String key) {
        this.key = key;
    }
}
