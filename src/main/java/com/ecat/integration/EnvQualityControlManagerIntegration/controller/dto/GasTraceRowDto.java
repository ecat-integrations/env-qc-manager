package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 标气溯源行（REST 配置面）：直读 airstation 钢瓶逻辑设备档案属性（方案 A 2026-08-23）。
 *
 * <p>gasCode 用名称词汇（SO2/NO2/CO/O3，与 GasSetting 卡片/前端闭集一致）；
 * O3 无钢瓶供应，三要素字段恒 null（发生器供气，如实展示）。</p>
 */
@Data
@Builder
public class GasTraceRowDto {

    /** 气体名称键（闭集 SO2/NO2/CO/O3） */
    private String gasCode;

    /** 标气来源（供应商/标准物质名称；未登记=「未设置」） */
    private String gasSource;

    /** 标气编号（钢瓶/标准物质编号） */
    private String gasNo;

    /** 标气浓度（钢瓶档案浓度，可空） */
    private BigDecimal gasConcentration;

    /** 浓度单位（如 ppm，可空） */
    private String unit;
}
