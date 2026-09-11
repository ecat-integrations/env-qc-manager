package com.ecat.integration.EnvQualityControlManagerIntegration.api;

/**
 * SDK 仪器词汇（闭域，§3 域 1）：受检分析仪的六个参数位。内部存储与呈现是两套形态
 * （qcm_record.parameter 落数字码 1~6，逻辑设备入口用字母名，PM2_5 展示名还带点），SDK 契约
 * 只认本枚举——入参出参都是常量，存储码/字母名的互译收口在 SDK 实现装配边界，调用方不再猜。
 *
 * @author coffee
 */
public enum SdkInstrument {

    /** 二氧化硫（内部存储码 1） */
    SO2,

    /** 二氧化氮（内部存储码 2） */
    NO2,

    /** 臭氧（内部存储码 3） */
    O3,

    /** 一氧化碳（内部存储码 4） */
    CO,

    /** PM10 颗粒物（内部存储码 5） */
    PM10,

    /** PM2.5 颗粒物（内部存储码 6；内部展示名带点「PM2.5」，常量名用下划线承载体裁） */
    PM2_5
}
