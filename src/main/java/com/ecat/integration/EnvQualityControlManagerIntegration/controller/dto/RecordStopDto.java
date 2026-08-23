package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 中止质控记录请求体（旧 @RequestBody Map 的 DTO 化，字段名与原 Map key 一致，REST 契约不变）。
 *
 * @author coffee
 */
@Data
public class RecordStopDto {

    /** 质控记录 ID（原 Map key "id"） */
    @NotNull(message = "id不能为空")
    private Long id;
}
