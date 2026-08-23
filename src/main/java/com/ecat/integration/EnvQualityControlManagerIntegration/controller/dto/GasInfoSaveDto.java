package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 标气溯源登记请求体（方案 A）：转写 airstation 钢瓶档案（来源/编号两字段）。
 *
 * @author coffee
 */
@Data
public class GasInfoSaveDto {

    /** 气体代码（闭集 SO2/NO2/CO/O3） */
    @NotBlank(message = "gasCode不能为空")
    private String gasCode;

    /** 标气来源（供应商/标准物质名称，可空=未录入） */
    private String gasSource;

    /** 标气编号（钢瓶/标准物质编号，可空=未录入） */
    private String gasNo;


}
