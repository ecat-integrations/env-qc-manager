package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import com.ecat.core.EcatCore;
import com.ecat.integration.EnvCalibrationComposerIntegration.AbstractCalibrationFlow;
import com.ecat.integration.EnvCalibrationComposerIntegration.PhaseInfo;
import com.ecat.integration.EnvQualityControlManagerIntegration.EnvQualityControlManagerIntegration;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 质控记录「执行阶段」视图：运行中合并 {@link AbstractCalibrationFlow} 的 {@link PhaseInfo}，
 * 已结束时使用 execution_log 根级 {@link QualityControlExecutionLogHelper#QC_PHASE_TIMELINES_KEY}。
 */
public final class QualityControlExecutionPhasePayload {

    private QualityControlExecutionPhasePayload() {
    }

    public static Map<String, Object> build(EcatCore core, QcmRecord record) {
        Objects.requireNonNull(record, "record");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordId", record.getId());
        Integer st = record.getExecutionStatus();
        out.put("executionStatus", st);

        AbstractCalibrationFlow live = resolveLiveFlow(core, record.getId());
        List<Map<String, Object>> phases;
        if (live != null) {
            phases = phasesFromLiveExecutor(live);
        } else {
            phases = phasesFromPersistedTimelines(record.getExecutionLog(), record.getExecutionStatus());
        }
        out.put("phases", phases);

        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(record.getExecutionLog());
        Object sm = root.get("statusMap");
        if (sm instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> status = (Map<String, Object>) sm;
            out.put("statusMap", new LinkedHashMap<>(status));
        } else {
            out.put("statusMap", new LinkedHashMap<String, Object>());
        }
        return out;
    }

    private static AbstractCalibrationFlow resolveLiveFlow(EcatCore core, Long recordId) {
        if (core == null || recordId == null) {
            return null;
        }
        try {
            EnvQualityControlManagerIntegration integration =
                    (EnvQualityControlManagerIntegration) core.getIntegrationRegistry()
                            .getIntegration("integration-env-quality-control-manager");
            if (integration == null || integration.executorMap == null) {
                return null;
            }
            return integration.executorMap.get(recordId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<Map<String, Object>> phasesFromLiveExecutor(AbstractCalibrationFlow flow) {
        List<Map<String, Object>> out = new ArrayList<>();
        PhaseInfo current = flow.getCurrentPhase();
        String currentId = current != null ? current.getId() : null;
        for (PhaseInfo pi : flow.getExecutorPhases()) {
            if (pi == null) {
                continue;
            }
            Map<String, Object> row = baseRow(pi.getId(), pi.getDisplayName(), pi.getEstimatedSeconds());
            putInstantMillis(row, "startTimeMillis", pi.getStartInstant());
            putInstantMillis(row, "endTimeMillis", pi.getEndInstant());
            String state;
            if (pi.getEndInstant() != null) {
                state = "completed";
            } else if (currentId != null && currentId.equals(pi.getId())) {
                state = "active";
            } else if (pi.getStartInstant() != null) {
                state = "active";
            } else {
                state = "pending";
            }
            row.put("state", state);
            out.add(row);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> phasesFromPersistedTimelines(String executionLog, Integer executionStatus) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> root = QualityControlExecutionLogHelper.parseRootMap(executionLog);
        Object raw = root.get(QualityControlExecutionLogHelper.QC_PHASE_TIMELINES_KEY);
        if (!(raw instanceof List)) {
            return out;
        }
        boolean failed = executionStatus != null && executionStatus.longValue() == 3L;
        Object sm = root.get("statusMap");
        if (sm instanceof Map) {
            Object ex = ((Map<?, ?>) sm).get("isException");
            if (Boolean.TRUE.equals(ex) || "true".equalsIgnoreCase(String.valueOf(ex))) {
                failed = true;
            }
        }
        for (Object o : (List<?>) raw) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> src = (Map<String, Object>) o;
            String code = src.get("phaseCode") != null ? String.valueOf(src.get("phaseCode")) : "";
            String name = src.get("phaseName") != null ? String.valueOf(src.get("phaseName")) : "";
            int est = 0;
            Object esec = src.get("estimatedSeconds");
            if (esec instanceof Number) {
                est = ((Number) esec).intValue();
            }
            Map<String, Object> row = baseRow(code, name, est);
            Object smillis = src.get("startTimeMillis");
            Object emillis = src.get("endTimeMillis");
            if (smillis != null) {
                row.put("startTimeMillis", toLong(smillis));
            }
            if (emillis != null) {
                row.put("endTimeMillis", toLong(emillis));
            }
            String state;
            if (row.containsKey("startTimeMillis") && row.containsKey("endTimeMillis")) {
                state = "completed";
            } else if (row.containsKey("startTimeMillis")) {
                state = failed ? "failed" : "active";
            } else {
                state = "pending";
            }
            row.put("state", state);
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> baseRow(String phaseCode, String phaseName, int estimatedSeconds) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phaseCode", phaseCode);
        row.put("phaseName", phaseName);
        row.put("estimatedSeconds", estimatedSeconds);
        return row;
    }

    private static void putInstantMillis(Map<String, Object> row, String key, Instant instant) {
        if (instant != null) {
            row.put(key, instant.toEpochMilli());
        }
    }

    private static long toLong(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        return Long.parseLong(String.valueOf(o).trim());
    }
}
