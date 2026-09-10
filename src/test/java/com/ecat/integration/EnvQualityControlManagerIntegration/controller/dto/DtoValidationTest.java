package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;

import org.junit.jupiter.api.Test;

/**
 * controller 入参 DTO 的 bean 校验注解行为单测（校验失败路径与 ruoyi 全局异常前的字段级 message 一致）。
 *
 * @author coffee
 */
public class DtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> messages(Set<? extends ConstraintViolation<?>> violations) {
        Set<String> msgs = new HashSet<>();
        for (ConstraintViolation<?> v : violations) {
            msgs.add(v.getMessage());
        }
        return msgs;
    }

    @Test
    public void recordStop_nullId_rejected() {
        RecordStopDto dto = new RecordStopDto();
        Set<String> msgs = messages(validator.validate(dto));
        assertEquals(java.util.Collections.singleton("id不能为空"), msgs);
    }


    @Test
    public void auditSpanCheck_missingAndOutOfRangeFields_rejected() {
        AuditSpanCheckDto dto = new AuditSpanCheckDto();
        dto.setGenGasTime(-1);
        dto.setReadDataCount(0);
        dto.setReadDataSpan(5);
        dto.setGenGasConc(0f);
        Set<String> msgs = messages(validator.validate(dto));
        assertTrue(msgs.contains("gas不能为空"));
        assertTrue(msgs.contains("genGasTime必须>=0"));
        assertTrue(msgs.contains("readDataCount必须>=1"));
        assertTrue(msgs.contains("genGasConc必须大于0"));
        assertTrue(msgs.contains("genGasConc不能为空") == false);
    }

    @Test
    public void auditSpanCheck_validDto_passes() {
        AuditSpanCheckDto dto = new AuditSpanCheckDto();
        dto.setGas("SO2");
        dto.setGenGasTime(10);
        dto.setReadDataCount(6);
        dto.setReadDataSpan(30);
        dto.setGenGasConc(0.5f);
        dto.setStdGasInPortName("port1");
        dto.setTargetFlowLpm(2.0);
        assertTrue(validator.validate(dto).isEmpty());
    }
}
