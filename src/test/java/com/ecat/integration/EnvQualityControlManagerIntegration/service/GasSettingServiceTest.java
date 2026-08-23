package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.GasInfoSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.CylinderArchiveSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.GasTraceRowDto;
import com.ecat.core.EcatCore;
import org.mockito.MockedStatic;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import com.ruoyi.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钢瓶标气浓度数值校验（写设备前的 ppb 范围门禁）单测。
 */
public class GasSettingServiceTest {

    private GasSettingService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new GasSettingService();
        Field f = GasSettingService.class.getDeclaredField("core");
        f.setAccessible(true);
        f.set(service, mock(EcatCore.class));
    }

    /** 配置面：gasCode 闭集（SO2/NO2/CO/O3）外硬拒 400，不猜映射、不触发设备写。 */
    @org.junit.jupiter.api.Test
    public void saveGasInfo_gasCodeOutsideClosedSet_rejected400() {
        GasInfoSaveDto dto = new GasInfoSaveDto();
        dto.setGasCode("NOx");
        try (MockedStatic<CylinderArchiveSupport> support = mockStatic(CylinderArchiveSupport.class)) {
            AjaxResult r = service.saveGasInfo(dto, "admin");
            assertEquals(400, r.get("code"));
            support.verifyNoInteractions();
        }
    }

    /** O3 发生器供气无钢瓶档案：硬拒 400（语义正确，非配置缺失）。 */
    @org.junit.jupiter.api.Test
    public void saveGasInfo_o3NoCylinder_rejected400() {
        GasInfoSaveDto dto = new GasInfoSaveDto();
        dto.setGasCode("O3");
        AjaxResult r = service.saveGasInfo(dto, "admin");
        assertEquals(400, r.get("code"));
    }

    /** 换瓶登记（方案 A）：转写来源/编号到 airstation 钢瓶档案（trim 后），qcm 不落自有表。 */
    @org.junit.jupiter.api.Test
    public void saveGasInfo_valid_writesArchiveAttributes() {
        GasInfoSaveDto dto = new GasInfoSaveDto();
        dto.setGasCode(" SO2 ");
        dto.setGasSource("  国家标准物质研究所 ");
        dto.setGasNo("GBW-E-050123");
        try (MockedStatic<CylinderArchiveSupport> support = mockStatic(CylinderArchiveSupport.class)) {
            support.when(() -> CylinderArchiveSupport.writeTraceByName(
                            any(EcatCore.class), eq("SO2"), eq("国家标准物质研究所"), eq("GBW-E-050123")))
                    .thenReturn(true);
            AjaxResult r = service.saveGasInfo(dto, "qc-operator");
            assertEquals(200, r.get("code"));
            support.verify(() -> CylinderArchiveSupport.writeTraceByName(
                    any(EcatCore.class), eq("SO2"), eq("国家标准物质研究所"), eq("GBW-E-050123")));
        }
    }

    /** 档案写入失败（设备未注册/属性写被拒）：返回 500，不谎报成功。 */
    @org.junit.jupiter.api.Test
    public void saveGasInfo_archiveWriteRejected_returns500() {
        GasInfoSaveDto dto = new GasInfoSaveDto();
        dto.setGasCode("SO2");
        try (MockedStatic<CylinderArchiveSupport> support = mockStatic(CylinderArchiveSupport.class)) {
            support.when(() -> CylinderArchiveSupport.writeTraceByName(any(), eq("SO2"), any(), any()))
                    .thenReturn(false);
            AjaxResult r = service.saveGasInfo(dto, "admin");
            assertEquals(500, r.get("code"));
        }
    }

    /** 档案行列表（方案 A）：恒 4 行闭集顺序，读 airstation 档案（O3 三要素 null）。 */
    @org.junit.jupiter.api.Test
    public void listGasInfo_fourRowsFromArchive() {
        try (MockedStatic<CylinderArchiveSupport> support = mockStatic(CylinderArchiveSupport.class)) {
            support.when(() -> CylinderArchiveSupport.readArchiveByName(any(), eq("SO2")))
                    .thenReturn(new CylinderArchiveSupport.GasTrace("国家标准物质中心", "GBW-E-060522", null, null));
            List<GasTraceRowDto> out = service.listGasInfo();
            assertEquals(4, out.size());
            assertEquals("SO2", out.get(0).getGasCode());
            assertEquals("GBW-E-060522", out.get(0).getGasNo());
            assertEquals("O3", out.get(3).getGasCode());
            assertNull(out.get(3).getGasSource());
        }
    }

    @Test
    public void parse_validPositiveNumber_ok() {
        assertEquals(Double.valueOf(0.5d), GasSettingService.parseConcentrationPpb("0.5"));
        assertEquals(Double.valueOf(100d), GasSettingService.parseConcentrationPpb(" 100 "));
        assertEquals(Double.valueOf(1e-3d), GasSettingService.parseConcentrationPpb("0.001"));
    }

    @Test
    public void parse_zeroOrNegative_rejected() {
        try {
            GasSettingService.parseConcentrationPpb("0");
            fail("0 浓度应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("标气浓度必须大于0: 0", e.getMessage());
        }
        try {
            GasSettingService.parseConcentrationPpb("-3.2");
            fail("负浓度应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("标气浓度必须大于0: -3.2", e.getMessage());
        }
    }

    @Test
    public void parse_nonNumeric_rejected() {
        try {
            GasSettingService.parseConcentrationPpb("abc");
            fail("非数值应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("标气浓度必须为数值: abc", e.getMessage());
        }
    }

    @Test
    public void parse_blankOrNull_rejected() {
        try {
            GasSettingService.parseConcentrationPpb("  ");
            fail("空白串应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("标气浓度不能为空", e.getMessage());
        }
        try {
            GasSettingService.parseConcentrationPpb(null);
            fail("null 应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("标气浓度不能为空", e.getMessage());
        }
    }

    @Test
    public void parse_nanOrInfinite_rejected() {
        try {
            GasSettingService.parseConcentrationPpb("NaN");
            fail("NaN 应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("标气浓度必须为有效数值: NaN", e.getMessage());
        }
        try {
            GasSettingService.parseConcentrationPpb("Infinity");
            fail("Infinity 应被拒绝");
        } catch (IllegalArgumentException e) {
            assertEquals("标气浓度必须为有效数值: Infinity", e.getMessage());
        }
    }
}
