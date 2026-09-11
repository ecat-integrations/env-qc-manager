package com.ecat.integration.EnvQualityControlManagerIntegration.api;

/**
 * SDK 计划状态词汇（闭域，§3 域 6）：质控计划的生命周期状态，qcm_plan.status 同词汇。
 * queryPlans 过滤入参与出参 PlanSetting.status 同用本枚举，null 语义收敛在接口 javadoc。
 *
 * @author coffee
 */
public enum SdkPlanStatus {

    /** 启用中：到点触发（enable 操作落此状态） */
    ACTIVE,

    /** 已暂停：保留配置不触发（pause 操作落此状态） */
    PAUSED,

    /** 已终结：一次性计划触发完成或被显式终止，不再参与「当前设置」 */
    FINISHED
}
