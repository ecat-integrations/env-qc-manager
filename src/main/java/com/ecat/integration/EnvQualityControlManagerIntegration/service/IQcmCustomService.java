package com.ecat.integration.EnvQualityControlManagerIntegration.service;

/**
 * IQcmCustomService
 *
 * @author caohongbo
 * @version 1.0
 */
public interface IQcmCustomService {


    /**
     * 执行自定义质控-单点检查任务
     *
     * @param gas
     * @param genGasTime
     * @param readDataCount
     * @param readDataSpan
     * @param genGasConc
     * @return 结果
     */
    public boolean executeCustomAuditCheck(String gas, int genGasTime, int readDataCount, int readDataSpan, float genGasConc, String stdGasInPortName, Double targetFlowLpm);
}
