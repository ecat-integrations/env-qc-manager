package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

/**
 * SDK 操作者（来源契约 §6，请求侧统一载体）：触发与停止两类操作的来源信息强制留痕，
 * 调用方传入事实而非让 SDK 探测。存储侧拼平为 displayOperator
 * （PLATFORM 且 ip 非空 = {@code name@ip[:port]}，其余形态 = {@code name}）落
 * qcm_record.trigger_user / updated_by。
 */
@Value
@Builder
public class SdkOperator {

    /** 来源形态（决定其余字段约定与拼平结果） */
    SdkOperatorSource sourceType;

    /** 操作者标识（必填）：system / 登录账号 / 集成名 / 平台标识 */
    String name;

    /** 对端 IP（仅 PLATFORM：网络边界填真实对端，SDK 进程内拿不到） */
    String ip;

    /** 对端端口（仅 PLATFORM，随 ip 一起填） */
    Integer port;

    /** 补充说明（选填，如工单号/操作原因，仅透传留痕） */
    String remark;
}
