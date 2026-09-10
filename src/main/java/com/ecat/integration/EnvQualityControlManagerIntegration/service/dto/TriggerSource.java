package com.ecat.integration.EnvQualityControlManagerIntegration.service.dto;

import com.ecat.integration.EnvQualityControlManagerIntegration.util.TaskTypeEnum;
import lombok.Getter;

/**
 * 质控执行触发源（FR-02-01）：SCHEDULED(调度器)/MANUAL(页面立即执行)/REMOTE(SDK) 三源殊途同归，
 * code 与 {@link TaskTypeEnum} 任务类型编码对齐（qcm_record.task_type 落该 code）。
 *
 * @author coffee
 */
public enum TriggerSource {

    /** 计划调度器到点触发（ScheduledPlanFireAction → 编排器）。 */
    SCHEDULED(TaskTypeEnum.AUTO.getCode()),
    /** 页面/任务框架手动触发。 */
    MANUAL(TaskTypeEnum.MANUAL.getCode()),
    /** 对外 SDK 触发。 */
    REMOTE(TaskTypeEnum.REMOTE.getCode());

    @Getter
    private final String code;

    TriggerSource(String code) {
        this.code = code;
    }

    /**
     * 任务框架/quartz 的 triggerType 串映射为触发源：'0' 自动→SCHEDULED、'3' 远程→REMOTE；
     * '1' 手动与其余合法值（如 '2' 现场，quartz 参数无用户上下文时视同人工发起）→MANUAL。
     */
    public static TriggerSource fromTaskTypeCode(String triggerType) {
        if (TaskTypeEnum.AUTO.getCode().equals(triggerType)) {
            return SCHEDULED;
        }
        if (TaskTypeEnum.REMOTE.getCode().equals(triggerType)) {
            return REMOTE;
        }
        return MANUAL;
    }
}
