package com.ecat.integration.EnvQualityControlManagerIntegration.api;

/**
 * SDK 执行状态词汇（闭域，§3 域 4）：质控记录的执行状态。内部 qcm_record.execution_status 落
 * int 编码 0~4，旧契约给的是中文名串（等待中/执行中/…）——人读串无法穷尽判定，本枚举让消费方
 * 编译期穷尽分支；中文展示由调用方自行 switch（SDK 不掺展示语言）。
 *
 * @author coffee
 */
public enum SdkExecutionStatus {

    /** 等待中（内部编码 0，非终态） */
    WAITING,

    /** 执行中（内部编码 1，非终态） */
    RUNNING,

    /** 成功（内部编码 2，终态） */
    SUCCESS,

    /** 失败（内部编码 3，终态） */
    FAILED,

    /** 手动中止中（内部编码 4，非终态——STOPPING 由设备恢复与终态落库异步收敛） */
    STOPPING
}
