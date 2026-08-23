package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 设置钢瓶气浓度请求体（旧 @RequestBody Map 的 DTO 化，字段名与原 Map key 一致，REST 契约不变）。
 *
 * <p>{@code value} 保留 String 类型：前端可能提交数字或数字串；数值合法性（ppb 数值且 &gt;0）
 * 由 GasSettingService 在写设备前校验。</p>
 *
 * @author coffee
 */
@Data
public class GasSettingAddDto {

    /** 浓度属性 ID（原 Map key "id"） */
    @NotBlank(message = "id不能为空")
    private String id;

    /** 浓度值（原 Map key "value"；ppb 数值串） */
    @NotBlank(message = "value不能为空")
    private String value;

    /** 请求方携带的设备 ID（原 Map key "deviceId"，可空：校准仪通道不强制携带） */
    private String deviceId;
}
