package com.ecat.integration.EnvQualityControlManagerIntegration.api;

/**
 * SDK 操作者来源形态（来源契约 §6）：调用方传入「发起事实」，SDK 只承载不探测——
 * 进程内 SDK 无法自取对端网络信息，远端 ip:port 只有网络边界（协议集成/REST 网关）知道。
 * 形态决定 {@link SdkOperator} 各字段的填写约定与存储侧拼平结果。
 */
public enum SdkOperatorSource {

    /** 进程内调度器/系统自身发起：name="system"，ip/port 留空。 */
    SCHEDULE,

    /**
     * REST 网关登录用户发起：name=登录账号，ip/port 留空——请求网络信息由
     * ruoyi 侧（登录态/sys_oper_log）承载，不随本契约重复传递。
     */
    REST_USER,

    /** 进程内 SDK 集成（本机服务）发起：name=集成名（如 lims-system），ip/port 留空。 */
    SDK_LOCAL,

    /**
     * 外部平台经网络边界转发发起：name=平台标识，ip/port=网络边界填写的真实对端
     * （必填，SDK 进程内拿不到，见来源契约 §6）；缺 ip 时存储侧退化为仅 name 可辨。
     */
    PLATFORM
}
