package com.ecat.integration.EnvQualityControlManagerIntegration.domain;

import lombok.Data;

/**
 * 质控执行关键参数子表 qcm_record_key_param（§4.0 工况+溯源层）。
 *
 * <p>执行完成时一次性冻结为快照写入；读时零关联。</p>
 *
 * @author coffee
 */
@Data
public class QcmRecordKeyParam {

    /** 子表行 ID */
    private Long id;

    /** 关联 qcm_record.id（逻辑关联，不物理外键；记录删除由应用层同步清理） */
    private Long recordId;

    /** 参数序号（同 record 内有序） */
    private Integer seq;

    /** 参数名 */
    private String name;

    /** 参数值（字符串呈现；数值语义由 name+unit 界定） */
    private String value;

    /** 参数单位 */
    private String unit;

    /** 参考范围（人读） */
    private String refRange;
}
