<template>
  <el-dialog title="执行记录详情" v-model="visible" width="800px" append-to-body @closed="onDialogClosed">
    <div class="execution-log-dialog-content">
      <div style="background: #f0f9ff; padding: 8px; margin-bottom: 15px; border-radius: 4px; font-size: 12px; color: #666;">
        <strong>数据信息:</strong> 共 {{ Object.keys(parsedExecutionLog).length }} 个字段 |
        <strong>任务参数:</strong> {{ Object.keys(mergedExecutionParams).length ? '有' : '无' }} |
        <strong>执行结果:</strong> {{ executionResultSummaryLine }}
      </div>

      <div class="params-section">
        <h5>任务参数</h5>
        <template v-if="Object.keys(mergedExecutionParams).length">
          <el-table :data="[mergedExecutionParams]" border stripe size="small" class="params-table">
            <el-table-column
              v-for="(value, key) in mergedExecutionParams"
              :key="key"
              :prop="key"
              :label="getParamDisplayName(key)"
              align="center"
              min-width="120">
              <template #default="scope">
                <span>{{ formatParamValue(scope.row[key], key) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else description="暂无任务参数（执行日志更新后将自动显示）" :image-size="56" />
      </div>

      <div class="result-section">
        <h5>执行结果</h5>
        <template v-if="hasStructuredExecutionResult">
          <div v-if="Array.isArray(parsedExecutionLog.result)" class="result-array">
            <el-table :data="parsedExecutionLog.result" border stripe size="small" class="result-table">
              <el-table-column
                v-for="(value, key) in parsedExecutionLog.result[0]"
                :key="key"
                :prop="key"
                :label="getResultDisplayName(key)"
                align="center"
                min-width="120">
                <template #default="scope">
                  <span>{{ formatResultValue(scope.row[key], key) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div v-else class="result-object">
            <el-table :data="[parsedExecutionLog.result]" border stripe size="small" class="result-table">
              <el-table-column
                v-for="(value, key) in parsedExecutionLog.result"
                :key="key"
                :prop="key"
                :label="getResultDisplayName(key)"
                align="center"
                min-width="120">
                <template #default="scope">
                  <span>{{ formatResultValue(scope.row[key], key) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </template>
        <template v-else-if="statusMapHasDisplayableFields">
          <el-descriptions :column="1" size="small" border class="result-status-map">
            <el-descriptions-item v-if="parsedExecutionLog.statusMap.isPass != null" label="是否通过">
              {{ formatResultValue(parsedExecutionLog.statusMap.isPass, 'isPass') }}
            </el-descriptions-item>
            <el-descriptions-item v-if="parsedExecutionLog.statusMap.resultMessage" label="结果说明">
              {{ parsedExecutionLog.statusMap.resultMessage }}
            </el-descriptions-item>
            <el-descriptions-item v-if="parsedExecutionLog.statusMap.errorMessage" label="错误信息">
              {{ parsedExecutionLog.statusMap.errorMessage }}
            </el-descriptions-item>
            <el-descriptions-item v-if="parsedExecutionLog.statusMap.isException != null" label="是否异常">
              {{ formatResultValue(parsedExecutionLog.statusMap.isException, 'isException') }}
            </el-descriptions-item>
          </el-descriptions>
        </template>
        <el-empty v-else class="qc-exec-result-empty" description="暂无执行结果" :image-size="32" />
      </div>

      <!-- 如果没有params和result，直接显示所有数据 -->
      <div v-if="showRawExecutionLogFallback" class="all-data-section">
        <h5>执行记录数据</h5>
        <div class="data-grid">
          <div v-for="(value, key) in parsedExecutionLog" :key="key" class="data-item">
            <span class="data-label">{{ getResultDisplayName(key) }}:</span>
            <span class="data-value">{{ formatResultValue(value, key) }}</span>
          </div>
        </div>
      </div>

      <div v-if="showNoDataHint" class="no-data">
        <p>暂无详细记录数据</p>
      </div>

      <div class="params-section phase-section">
        <h5>执行阶段</h5>
        <!-- 使用 Element Plus 时间线组件替代表格（宿主全局注册，无需局部引入） -->
        <el-timeline class="custom-timeline">
          <!-- 开始阶段 -->
          <el-timeline-item
            :timestamp="row.startTime ? row.startTime : '开始时间未知'"
            type="primary"
            :color="getPhaseStatusColor(row.executionStatus)"
            size="large"
          >
            <template #icon>
              <el-icon><VideoPlay /></el-icon>
            </template>
            <div class="timeline-content">
              <h4>开始</h4>

            </div>
          </el-timeline-item>

          <!-- 执行阶段 -->
          <el-timeline-item
            v-for="(phase, index) in phaseList"
            :key="(phase.phaseCode || phase.phaseId || 'p') + '-' + index"
            :type="phaseTimelineType(phase)"
            :color="phaseTimelineColor(phase)"
            :timestamp="phaseTimelineTimestamp(phase)"
          >
            <template #icon>
              <el-icon v-if="phase.state === 'active'" class="qc-phase-icon qc-phase-icon--spin"><Loading /></el-icon>
              <el-icon v-else-if="phase.state === 'failed'" class="qc-phase-icon qc-phase-icon--fail"><CircleClose /></el-icon>
              <el-icon v-else-if="phase.state === 'completed'" class="qc-phase-icon qc-phase-icon--done"><CircleCheck /></el-icon>
              <el-icon v-else class="qc-phase-icon qc-phase-icon--pending"><Clock /></el-icon>
            </template>
            <div class="timeline-content" :class="{'current-phase': phase.state === 'active'}">
              <h4>{{ phase.phaseName || phase.displayName || '阶段' }}</h4>
              <p v-if="phase.estimatedSeconds != null && phase.estimatedSeconds >= 0" class="phase-est">
                预估：{{ formatEstimatedSeconds(phase.estimatedSeconds) }}
              </p>
              <p v-if="phase.startTimeMillis != null" class="phase-time">
                开始：{{ formatPhaseInstant(phase.startTimeMillis) }}
              </p>
              <p v-if="phase.endTimeMillis != null" class="phase-time">
                结束：{{ formatPhaseInstant(phase.endTimeMillis) }}
              </p>
              <p v-if="phase.state === 'failed' && phaseFailureHint" class="phase-fail">
                {{ phaseFailureHint }}
              </p>
            </div>
          </el-timeline-item>

          <!-- 结束阶段：成功 / 失败 / 手动中止 -->
          <el-timeline-item
            v-if="String(row.executionStatus) === '2'"
            :timestamp="displayRecordEndTime(row) || '结束时间未知'"
            type="success"
            color="#67C23A"
            size="large"
          >
            <template #icon>
              <el-icon><CircleCheck /></el-icon>
            </template>
            <div class="timeline-content">
              <h4>成功结束</h4>
            </div>
          </el-timeline-item>
          <el-timeline-item
            v-else-if="String(row.executionStatus) === '3' && !isQcManualAbortEndRow(row)"
            :timestamp="displayRecordEndTime(row) || '结束时间未知'"
            type="danger"
            color="#F56C6C"
            size="large"
          >
            <template #icon>
              <el-icon><CircleClose /></el-icon>
            </template>
            <div class="timeline-content">
              <h4>失败结束</h4>
              <p v-if="row.resultEvaluation" class="failure-reason">
                失败原因: {{ row.resultEvaluation }}
              </p>
            </div>
          </el-timeline-item>
          <el-timeline-item
            v-else-if="isQcManualAbortEndRow(row)"
            :timestamp="displayRecordEndTime(row) || '结束时间未知'"
            type="warning"
            color="#E6A23C"
            size="large"
          >
            <template #icon>
              <el-icon><CircleClose /></el-icon>
            </template>
            <div class="timeline-content timeline-content--aborted">
              <h4>手动中止</h4>
              <p v-if="row.resultEvaluation" class="abort-reason">
                说明: {{ row.resultEvaluation }}
              </p>
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </div>
  </el-dialog>
</template>

<script setup name="ExecutionLogDetailDialog">
import { getCurrentInstance, nextTick, ref, computed } from 'vue';
import { VideoPlay, CircleCheck, CircleClose, Loading, Clock } from '@element-plus/icons-vue';
import { getRecords, getExecutionPhases } from '@/api/quality_control/records';
import { useExecutionPolling } from '../../composables/useExecutionPolling';
import {
  isQcManualAbortEndRow,
  shouldPollExecutionDetailRow,
  displayRecordEndTime,
  formatEstimatedSeconds
} from '../recordRowStatus';

const emit = defineEmits(['row-updated']);
const { proxy } = getCurrentInstance();
const { quality_control_param } = proxy.useDict('quality_control_param');

const visible = ref(false);
const row = ref({});
const parsedExecutionLog = ref({});
const phaseList = ref([]);
/** 详情弹窗内用于浓度显示换算：存库 calculatedValue 为 ppm，非 CO 时按 ppb 展示（×1000） */
const executionDetailGasSymbol = ref('');

// 参数名中文映射（须在 mergedExecutionParams 之前定义）
const paramDisplayNames = {
  taskType: '任务类型',
  qualityControlType: '质控类型',
  parameter: '质控参数',
  deviceId: '设备ID',
  triggerType: '触发类型',
  calculatedValue: '计算值',
  standardValue: '标准值',
  monitoringData: '监测数据',
  stdGasInPortName: '标气入口（跨度口/采样口）',
  taskDescription: '任务描述',
  gas: '气体类型',
  readDataSpan: '读取数据间隔(秒)',
  genGasConc: '生成气体浓度',
  taskName: '任务名称',
  genGasTime: '生成气体时间(秒)',
  readDataCount: '读取数据次数',
  targetFlowLpm: '目标流量 (L/min)',
  flowRateLpm: '目标流量 (L/min)',
  targetFlow: '目标流量 (L/min)',
  stdGasConcentration: '标气浓度'
};

/** 执行记录详情中不展示的 params 键（调度元信息，避免干扰业务参数阅读） */
const EXECUTION_DETAIL_HIDDEN_PARAM_KEYS = new Set([
  'taskType',
  'taskDescription',
  'triggerType',
  'taskName'
]);

/** 执行日志 JSON 根级结构字段：不作为「任务参数」从根级扁平合并 */
const EXECUTION_LOG_STRUCTURE_KEYS = new Set([
  'result',
  'params',
  'statusMap',
  'qcPhaseTimelines',
  'keyParametersSnapshot',
  'keyParametersSamplingWindow'
]);

function recordFieldsAsExecutionParams(r) {
  if (!r || typeof r !== 'object') {
    return {};
  }
  const out = {};
  const put = (k, val) => {
    if (val === null || val === undefined || val === '') {
      return;
    }
    out[k] = val;
  };
  put('taskType', r.taskType);
  put('qualityControlType', r.qualityControlType);
  put('parameter', r.parameter);
  put('standardValue', r.standardValue);
  put('monitoringData', r.monitoringData);
  put('calculatedValue', r.calculatedValue);
  return out;
}

function flatScalarParamsFromParsedRoot(parsed) {
  if (!parsed || typeof parsed !== 'object') {
    return {};
  }
  const out = {};
  for (const [k, v] of Object.entries(parsed)) {
    if (EXECUTION_LOG_STRUCTURE_KEYS.has(k)) {
      continue;
    }
    if (v === null || v === undefined || v === '') {
      continue;
    }
    const t = typeof v;
    if (t === 'string' || t === 'number' || t === 'boolean') {
      out[k] = v;
    }
  }
  return out;
}

const mergedExecutionParams = computed(() => {
  const r = row.value;
  const parsed = parsedExecutionLog.value;
  const out = { ...recordFieldsAsExecutionParams(r) };
  Object.assign(out, flatScalarParamsFromParsedRoot(parsed));
  const p = parsed?.params;
  if (p && typeof p === 'object') {
    for (const [k, v] of Object.entries(p)) {
      if (EXECUTION_DETAIL_HIDDEN_PARAM_KEYS.has(k)) {
        continue;
      }
      if (v !== null && v !== undefined && v !== '') {
        out[k] = v;
      }
    }
  }
  return out;
});

const hasStructuredExecutionResult = computed(() => {
  const r = parsedExecutionLog.value?.result;
  if (r == null) {
    return false;
  }
  if (Array.isArray(r)) {
    return r.length > 0;
  }
  if (typeof r === 'object') {
    return Object.keys(r).length > 0;
  }
  return true;
});

const statusMapHasDisplayableFields = computed(() => {
  const sm = parsedExecutionLog.value?.statusMap;
  if (!sm || typeof sm !== 'object') {
    return false;
  }
  const rm = sm.resultMessage;
  const em = sm.errorMessage;
  if (rm != null && String(rm).trim()) {
    return true;
  }
  if (em != null && String(em).trim()) {
    return true;
  }
  if (sm.isPass != null && String(sm.isPass).trim() !== '') {
    return true;
  }
  if (sm.isException != null && String(sm.isException).trim() !== '') {
    return true;
  }
  return false;
});

const executionResultSummaryLine = computed(() => {
  const r = parsedExecutionLog.value?.result;
  if (r == null) {
    return '无';
  }
  if (Array.isArray(r)) {
    return r.length ? `${r.length}条记录` : '无';
  }
  if (typeof r === 'object') {
    return Object.keys(r).length ? '有' : '无';
  }
  return '有';
});

const showRawExecutionLogFallback = computed(() => {
  const p = parsedExecutionLog.value;
  if (!p || typeof p !== 'object') {
    return false;
  }
  const keys = Object.keys(p);
  if (keys.length === 0) {
    return false;
  }
  if (Object.keys(mergedExecutionParams.value).length > 0) {
    return false;
  }
  if (hasStructuredExecutionResult.value) {
    return false;
  }
  if (statusMapHasDisplayableFields.value) {
    return false;
  }
  return true;
});

const showNoDataHint = computed(() => {
  if (!row.value) {
    return false;
  }
  const p = parsedExecutionLog.value;
  const noParsed = !p || typeof p !== 'object' || Object.keys(p).length === 0;
  const noPhases = !phaseList.value || phaseList.value.length === 0;
  const noMerged = Object.keys(mergedExecutionParams.value).length === 0;
  const noResult = !hasStructuredExecutionResult.value && !statusMapHasDisplayableFields.value;
  return noParsed && noPhases && noMerged && noResult;
});

// 触发类型字典映射
const triggerTypeDict = [
  { value: '0', label: '自动触发' },
  { value: '1', label: '手动触发' }
];

// 任务类型字典映射（用于执行记录详情）
const taskTypeDict = [
  { value: '0', label: '计划任务' },
  { value: '1', label: '手动任务' },
  { value: '2', label: '现场任务' }
];

// 质控类型字典映射（用于执行记录详情，包含英文名称映射）
const qualityControlTypeDict = [
  { value: '0', label: '零点核查' },
  { value: '1', label: '跨度校准' },
  { value: '2', label: '线性核查' },
  { value: '3', label: '精密度检查' },
  { value: '4', label: '准确度校准' },
  { value: '5', label: '转换率检查' },
  { value: '6', label: '人工核查' },
  { value: '7', label: '多仪器零点质控' },
  // 添加英文名称映射
  { value: 'zero_check', label: '零点核查' },
  { value: 'span_calibration', label: '跨度校准' },
  { value: 'linear_check', label: '线性核查' },
  { value: 'precision_check', label: '精密度检查' },
  { value: 'accuracy_calibration', label: '准确度校准' },
  { value: 'conversion_rate_check', label: '转换率检查' },
  { value: 'audit_span_check', label: '人工核查' }
];

// 结果名中文映射
const resultDisplayNames = {
  resultValue: '结果值',
  stdValue: '标准值',
  deviceValue: '设备值',
  checkPassLimit: '通过限值',
  checkCalibLimit: '校准限值',
  slope: '斜率',
  intercept: '截距',
  correlation: '相关系数',
  deviceValues: '设备值列表',
  stdValues: '标准值列表',
  check_r_min: '相关系数最小值',
  check_a_max: '斜率最大值',
  check_a_min: '斜率最小值',
  check_b_scope: '截距范围',
  precision: '精密度',
  mean: '平均值',
  standardDeviation: '标准偏差',
  deviceStdGas: '设备标气浓度',
  checkRsd20Max: '20%满量程相对标准偏差最大值',
  efficiency: '转换效率',
  origNoDatas: '原始NO数据',
  origNoxDatas: '原始NOx数据',
  remNoDatas: '滴定NO数据',
  remNoxDatas: '滴定NOx数据',
  origNoAvg: '原始NO平均值',
  origNoxAvg: '原始NOx平均值',
  remNoAvg: '滴定NO平均值',
  remNoxAvg: '滴定NOx平均值',
  isPass: '是否通过',
  // 新增字段映射
  checkTime: '检查时间',
  checkData: '检查数据',
  qualityControlType: '质控类型',
  taskType: '任务类型',
  stdGasInPortName: '标气入口（跨度口/采样口）',
  taskDescription: '任务描述',
  gas: '气体类型',
  readDataSpan: '读取数据间隔(秒)',
  genGasConc: '生成气体浓度',
  taskName: '任务名称',
  triggerType: '触发类型',
  genGasTime: '生成气体时间(秒)',
  readDataCount: '读取数据次数',
  isException: '是否异常',
  resultMessage: '结果说明',
  errorMessage: '错误信息'
};

// 解析执行记录JSON
function parseExecutionLog(executionLog) {
  if (!executionLog) {
    return {};
  }
  const trimmed = String(executionLog).trim();
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
    return {};
  }
  try {
    return JSON.parse(trimmed);
  } catch (error) {
    console.error('解析执行记录失败:', error);
    return {};
  }
}

function looksLikeLogicAttrId(key) {
  if (key == null || typeof key !== 'string') {
    return false;
  }
  if (paramDisplayNames[key]) {
    return false;
  }
  return key.includes('_') && key.length > 10;
}

function inferAttrLabelFromId(key) {
  const lower = String(key).toLowerCase();
  if (lower.includes('flow') || /_flow$/.test(lower) || /^flow/.test(lower) || lower.includes('流')) {
    return '流量';
  }
  if (lower.includes('pressure') || lower.includes('press') || lower.includes('压')) {
    return '压力';
  }
  if ((lower.includes('temp') && !lower.includes('attempt')) || lower.includes('温')) {
    return '温度';
  }
  if (lower.includes('humid') || lower.includes('湿')) {
    return '湿度';
  }
  return '';
}

// 获取参数显示名称
function getParamDisplayName(key) {
  if (paramDisplayNames[key]) {
    return paramDisplayNames[key];
  }
  const inferred = inferAttrLabelFromId(key);
  if (inferred) {
    return looksLikeLogicAttrId(key) ? `${inferred}（逻辑属性）` : inferred;
  }
  return key;
}

// 获取结果显示名称
function getResultDisplayName(key) {
  return resultDisplayNames[key] || key;
}

// 获取字典标签
function getDictLabel(dictArray, value) {
  if (!dictArray || !Array.isArray(dictArray)) {
    return value;
  }

  // 尝试多种匹配方式
  let item = dictArray.find(dict => dict.value === value);
  if (!item) {
    // 如果精确匹配失败，尝试字符串匹配
    item = dictArray.find(dict => String(dict.value) === String(value));
  }
  if (!item) {
    // 如果字符串匹配也失败，尝试数字匹配
    const numValue = Number(value);
    if (!isNaN(numValue)) {
      item = dictArray.find(dict => Number(dict.value) === numValue);
    }
  }

  return item ? item.label : value;
}

/**
 * 从行数据与执行日志 params 推断气体符号（用于 calculatedValue：存 ppm，非 CO 展示为 ppb）。
 */
function resolveExecutionDetailGasSymbol(r, parsed) {
  const params = parsed && typeof parsed === 'object' ? parsed.params : null;
  const raw = params?.gas ?? params?.parameter ?? r?.parameter;
  if (raw == null || raw === '') {
    return '';
  }
  const s = String(raw).trim();
  if (/^[A-Za-z][A-Za-z0-9]*$/i.test(s) && !/^\d+$/.test(s)) {
    return s.toUpperCase();
  }
  const first = s.split(',')[0].trim();
  const label = getDictLabel(quality_control_param.value, first);
  return String(label)
    .trim()
    .toUpperCase()
    .replace(/O₂/g, 'O2')
    .replace(/O2/g, 'O2');
}

/**
 * 计算值在库中按 ppm 存储：CO 仍显示 ppm，其余气体显示为 ppb（×1000）。
 */
function formatCalculatedValueFromStoredPpm(value) {
  if (value === null || value === undefined) {
    return '无';
  }
  const n = typeof value === 'number' ? value : parseFloat(String(value).replace(/,/g, ''));
  if (Number.isNaN(n)) {
    return String(value);
  }
  const gas = executionDetailGasSymbol.value;
  if (gas === 'CO') {
    const t = Number.isInteger(n) ? String(n) : String(Number(n.toFixed(6)).valueOf());
    return `${t} ppm`;
  }
  const ppb = n * 1000;
  const t = Number.isInteger(ppb) ? String(ppb) : ppb.toFixed(2).replace(/\.?0+$/, '');
  return `${t} ppb`;
}

// 格式化参数值
function formatParamValue(value, key) {
  if (value === null || value === undefined) return '无';
  if (typeof value === 'object') return JSON.stringify(value);
  if (typeof value === 'boolean') {
    return value ? '是' : '否';
  }
  if (typeof value === 'number') {
    if (key === 'calculatedValue') {
      return formatCalculatedValueFromStoredPpm(value);
    }
    if (key === 'targetFlowLpm' || key === 'flowRateLpm' || key === 'targetFlow') {
      return `${Number.isInteger(value) ? value : value.toFixed(2)} L/min`;
    }
    return Number.isInteger(value) ? value.toString() : value.toFixed(2);
  }

  const strValue = String(value);

  switch (key) {
    case 'calculatedValue':
      return formatCalculatedValueFromStoredPpm(strValue);
    case 'taskType':
      return getDictLabel(taskTypeDict, strValue);
    case 'qualityControlType':
      return getDictLabel(qualityControlTypeDict, strValue);
    case 'triggerType':
      return getDictLabel(triggerTypeDict, strValue);
    case 'parameter':
      return getDictLabel(quality_control_param.value, strValue);
    case 'targetFlowLpm':
    case 'flowRateLpm':
    case 'targetFlow':
      return `${strValue} L/min`;
    case 'stdGasInPortName':
      if (strValue === '跨度检查') {
        return '跨度口';
      }
      if (strValue === '测量') {
        return '采样口';
      }
      return strValue;
    default:
      return strValue;
  }
}

// 格式化结果值
function formatResultValue(value, key) {
  if (value === null || value === undefined) return '无';
  if (Array.isArray(value)) {
    return value.length > 0 ? value.join(', ') : '空数组';
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否';
  }
  if (typeof value === 'number') {
    if (key === 'calculatedValue') {
      return formatCalculatedValueFromStoredPpm(value);
    }
    // 如果是整数，不显示小数位
    return Number.isInteger(value) ? value.toString() : value.toFixed(2);
  }

  // 根据字段名使用字典数据转换
  const strValue = String(value);

  switch (key) {
    case 'calculatedValue':
      return formatCalculatedValueFromStoredPpm(strValue);
    case 'taskType':
      return getDictLabel(taskTypeDict, strValue);
    case 'qualityControlType':
      return getDictLabel(qualityControlTypeDict, strValue);
    case 'triggerType':
      return getDictLabel(triggerTypeDict, strValue);
    default:
      return strValue;
  }
}

const phaseFailureHint = computed(() => {
  const sm = parsedExecutionLog.value && parsedExecutionLog.value.statusMap;
  if (!sm) {
    return '';
  }
  const em = sm.errorMessage || sm.resultMessage;
  return em ? String(em) : '该阶段执行失败';
});

function formatPhaseInstant(ms) {
  if (ms == null) {
    return '';
  }
  const d = new Date(Number(ms));
  if (Number.isNaN(d.getTime())) {
    return String(ms);
  }
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function phaseTimelineTimestamp(phase) {
  if (phase && phase.startTimeMillis != null) {
    return formatPhaseInstant(phase.startTimeMillis);
  }
  return '';
}

function phaseTimelineType(phase) {
  if (!phase) {
    return 'info';
  }
  if (phase.state === 'failed') {
    return 'danger';
  }
  if (phase.state === 'active') {
    return 'primary';
  }
  if (phase.state === 'completed') {
    return 'success';
  }
  return 'info';
}

function phaseTimelineColor(phase) {
  if (!phase) {
    return '#C0C4CC';
  }
  if (phase.state === 'failed') {
    return '#F56C6C';
  }
  if (phase.state === 'active') {
    return '#409EFF';
  }
  if (phase.state === 'completed') {
    return '#67C23A';
  }
  return '#C0C4CC';
}

// 获取阶段状态颜色
function getPhaseStatusColor(status) {
  // 状态为0-等待中，1-执行中，2-成功，3-失败，4-手动中止
  switch (String(status)) {
    case '0':
      return '#E6A23C'; // 等待中 - 橙色
    case '1':
      return '#409EFF'; // 执行中 - 蓝色
    case '2':
      return '#67C23A'; // 成功 - 绿色
    case '3':
      return '#F56C6C'; // 失败 - 红色
    case '4':
      return '#E6A23C'; // 手动中止 - 与等待区分：时间线起点仍用任务当前状态色
    default:
      return '#C0C4CC'; // 默认 - 灰色
  }
}

/** 状态轮询（G-VUE-3）：等待中/执行中/中止中每 5s 拉取阶段与最新 execution_log；终态自动停止 */
const { start: startPolling, stop: stopPolling } = useExecutionPolling({
  tick: refreshExecutionPhases,
  shouldContinue: () => visible.value && !!row.value && shouldPollExecutionDetailRow(row.value)
});

async function refreshExecutionPhases() {
  if (!row.value?.id) {
    return;
  }
  const id = row.value.id;
  try {
    const [resPhases, resRow] = await Promise.all([
      getExecutionPhases(id),
      getRecords(id)
    ]);
    if (resPhases && resPhases.code === 200 && resPhases.data) {
      if (Array.isArray(resPhases.data.phases)) {
        phaseList.value = resPhases.data.phases;
      }
      if (resPhases.data.executionStatus != null && resPhases.data.executionStatus !== '' && row.value) {
        row.value.executionStatus = resPhases.data.executionStatus;
      }
    }
    if (resRow && resRow.code === 200 && resRow.data) {
      const d = resRow.data;
      Object.assign(row.value, d);
      parsedExecutionLog.value = parseExecutionLog(row.value.executionLog);
      executionDetailGasSymbol.value = resolveExecutionDetailGasSymbol(row.value, parsedExecutionLog.value);
      // 列表行同步由父组件负责（单一数据源）
      emit('row-updated', id, d);
    }
  } catch (e) {
    // ignore transient network errors in dialog polling
  }
}

function onDialogClosed() {
  stopPolling();
  row.value = {};
  parsedExecutionLog.value = {};
  phaseList.value = [];
  executionDetailGasSymbol.value = '';
}

/** 打开详情弹窗：解析执行日志、拉取阶段，并按需启动轮询 */
function open(targetRow) {
  row.value = targetRow;
  parsedExecutionLog.value = parseExecutionLog(targetRow.executionLog);
  executionDetailGasSymbol.value = resolveExecutionDetailGasSymbol(targetRow, parsedExecutionLog.value);
  phaseList.value = [];
  stopPolling();
  nextTick(() => {
    visible.value = true;
    void refreshExecutionPhases().then(() => {
      if (shouldPollExecutionDetailRow(row.value)) {
        startPolling();
      }
    });
  });
}

defineExpose({ open });
</script>

<style>
.params-section, .result-section, .all-data-section, .phase-section {
  margin-bottom: 16px;
}

.phase-section .custom-timeline {
  margin-top: 4px;
}

/* 时间线样式 */
.custom-timeline {
  padding-left: 10px;
}

.timeline-content h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  font-weight: 600;
}

.timeline-content.current-phase {
  color: #409EFF;
  font-weight: bold;
  background-color: #ecf5ff;
  padding: 4px;
  border-radius: 4px;
}

.timeline-content p {
  margin: 4px 0;
  font-size: 12px;
  color: #606266;
}

.failure-reason {
  color: #F56C6C !important;
  font-weight: 500;
  background-color: #fef0f0;
  padding: 4px;
  border-radius: 4px;
  border-left: 2px solid #F56C6C;
}

.data-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.data-item {
  display: flex;
  justify-content: space-between;
  padding: 6px 8px;
  background: #fafafa;
  border-radius: 4px;
  font-size: 12px;
}

.data-label {
  color: #606266;
  font-weight: 500;
}

.data-value {
  color: #303133;
  word-break: break-all;
}

.no-data {
  text-align: center;
  color: #909399;
  font-style: italic;
}

.execution-log-dialog-content {
  max-height: 600px;
  overflow-y: auto;
}

.params-table, .result-table {
  margin-bottom: 20px;
}

.params-table .el-table__header-wrapper th {
  background-color: #f5f7fa;
  color: #606266;
  font-weight: 600;
}

.result-table .el-table__header-wrapper th {
  background-color: #f0f9ff;
  color: #409eff;
  font-weight: 600;
}

.params-section h5, .result-section h5, .phase-section h5 {
  margin-bottom: 12px;
  color: #303133;
  font-size: 16px;
  font-weight: 600;
  border-left: 4px solid #409eff;
  padding-left: 8px;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .data-grid {
    grid-template-columns: 1fr;
  }

  .params-table, .result-table {
    font-size: 12px;
  }
}

.result-status-map {
  margin-top: 4px;
}

/* 执行结果区块：无数据时紧凑占位 */
.qc-exec-result-empty.el-empty {
  padding: 6px 0 2px;
  min-height: 0;
}

.qc-exec-result-empty .el-empty__image {
  width: 32px;
  height: 32px;
}

.qc-exec-result-empty .el-empty__description {
  margin-top: 2px;
  padding: 0;
  line-height: 1.35;
}

.qc-exec-result-empty .el-empty__description p {
  margin: 0;
  font-size: 12px;
  color: #909399;
}

.timeline-content--aborted {
  border-left: 2px solid #e6a23c;
  padding-left: 8px;
}

.abort-reason {
  color: #b88230 !important;
  font-weight: 500;
  background-color: #fdf6ec;
  padding: 4px;
  border-radius: 4px;
}

.qc-phase-icon {
  font-size: 18px;
}

.qc-phase-icon--spin {
  animation: qc-spin 1.05s linear infinite;
  color: #409eff;
}

@keyframes qc-spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.qc-phase-icon--fail {
  color: #f56c6c;
}

.qc-phase-icon--done {
  color: #67c23a;
}

.qc-phase-icon--pending {
  color: #c0c4cc;
}

.phase-est,
.phase-time {
  font-size: 12px;
  color: #606266;
}

.phase-fail {
  margin-top: 6px;
  color: #f56c6c;
  font-weight: 600;
}
</style>
