package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/**
 * 自定义质控（审核跨度核查）立即执行请求体（旧 @RequestBody Map 的 DTO 化，
 * 字段名与原 Map key 一致，REST 契约不变；数值字段由 Jackson 自动完成串→数值转换）。
 *
 * @author coffee
 */
@Data
public class AuditSpanCheckDto {

    /** 目标气体（如 SO2/NO/CO/O3） */
    @NotNull(message = "gas不能为空")
    private String gas;

    /** 生成标气时间（分钟） */
    @NotNull(message = "genGasTime不能为空")
    @Min(value = 0, message = "genGasTime必须>=0")
    private Integer genGasTime;

    /** 读数次数 */
    @NotNull(message = "readDataCount不能为空")
    @Min(value = 1, message = "readDataCount必须>=1")
    private Integer readDataCount;

    /** 读数间隔 */
    @NotNull(message = "readDataSpan不能为空")
    @Min(value = 1, message = "readDataSpan必须>=1")
    private Integer readDataSpan;

    /** 标气浓度（ppb，必须为正数；旧 Float.parseFloat 通道同语义） */
    @NotNull(message = "genGasConc不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "genGasConc必须大于0")
    private Float genGasConc;

    /** 标气入口名称 */
    private String stdGasInPortName;

    /** 目标流量（L/min，可空） */
    private Double targetFlowLpm;
}
