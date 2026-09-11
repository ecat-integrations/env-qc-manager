package com.ecat.integration.EnvQualityControlManagerIntegration.api;

/**
 * SDK 结构化失败原因词汇（闭域，§3 域 7）：与 qcm_record.failure_reason 现有落库值集逐一对应
 * （触发前拒绝留痕 + 编排器两道闸）。null = 无结构化原因（运行中/成功/启动期无执行事实的失败行），
 * 人读细节在该场景走 resultEvaluation 文案，不往本通道塞自由文本。
 *
 * @author coffee
 */
public enum SdkFailureReason {

    /** 执行器被互斥闸占用，批次未执行即落 FAILED 终态留痕 */
    EXECUTOR_BUSY_CONFLICT,

    /** 执行器类型未接线（composer 尚无该质控类型的 ExecutorType 映射），批次落 FAILED 终态留痕 */
    EXECUTOR_TYPE_NOT_READY,

    /** 请求参数非法：参数完整（类型/仪器可解析）故建行留痕后拒绝 */
    INVALID_PARAM,

    /** 请求要求排队（allowQueue=true）但 SDK 触发不支持：参数完整故建行留痕后拒绝 */
    QUEUE_NOT_SUPPORTED
}
