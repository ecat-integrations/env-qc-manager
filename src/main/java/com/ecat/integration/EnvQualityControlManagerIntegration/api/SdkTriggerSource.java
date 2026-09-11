package com.ecat.integration.EnvQualityControlManagerIntegration.api;

/**
 * SDK 触发来源词汇（闭域，§3 域 3）：一次质控执行由谁发起。内部 qcm_record.task_type 落任务
 * 类型编码，SDK 契约只认本枚举，编码互译收口在 SDK 实现装配边界（ResultFilter 过滤同样给枚举，
 * 不再让调用方传编码/字母名猜空）。
 *
 * @author coffee
 */
public enum SdkTriggerSource {

    /** 计划调度器到点触发 */
    SCHEDULED,

    /** 页面/任务框架手动触发 */
    MANUAL,

    /** 对外 SDK 触发 */
    REMOTE
}
