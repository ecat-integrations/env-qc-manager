<template>
  <el-dialog title="执行记录详情" v-model="visible" width="72%" top="5vh" append-to-body @closed="onDialogClosed">
    <div class="execution-log-dialog-content">
      <!-- 批 C：弹窗分两页——「执行阶段」承载原有全部内容；「过程展示」为执行中实时属性/秒级曲线
           与已完成 ADM 回放（详见 QcRecordProcessPanel）。默认停在执行阶段页。 -->
      <el-tabs v-model="activeTab" class="qc-detail-tabs">
        <el-tab-pane label="执行阶段" name="phase">

      <!-- ==== 概要 ==== -->
      <div class="qc-summary-bar">
        <span v-for="item in summaryItems" :key="item.label" class="qc-summary-item">
          <strong>{{ item.label }}:</strong> {{ item.text }}
        </span>
      </div>

      <!-- ==== 任务参数 ==== -->
      <div class="params-section">
        <h5>任务参数</h5>
        <el-descriptions v-if="paramItems.length" :column="2" border size="small" class="params-desc">
          <el-descriptions-item v-for="item in paramItems" :key="item.key" :label="item.label">
            {{ item.text }}
          </el-descriptions-item>
        </el-descriptions>
        <el-empty v-else description="暂无任务参数（执行日志更新后将自动显示）" :image-size="56" />
      </div>

      <!-- ==== 执行结果 ==== -->
      <div class="result-section">
        <h5>执行结果</h5>
        <template v-if="hasStructuredExecutionResult">
          <!-- 语义说明：composer 三个原始字段的业务口径，帮助读数（判定指标单位随质控类型而异） -->
          <el-alert type="info" :closable="false" class="result-semantics-alert">
            设备值＝质控期间分析仪示值的采集均值（仪器实际读数）；标准值＝通入标气的名义浓度；结果值＝判定指标（零点为漂移绝对量，跨度为相对漂移%）。
          </el-alert>
          <!-- 人工核查（audit_span_check）历史结构：result 为 [{checkData, checkTime}] 列表，按时间序列平铺 -->
          <el-table
            v-if="Array.isArray(parsedExecutionLog.result)"
            :data="parsedExecutionLog.result"
            border
            stripe
            size="small"
            class="result-table"
          >
            <el-table-column label="核查时间" prop="checkTime" align="center" min-width="150" />
            <el-table-column label="核查读数" align="center" min-width="120">
              <template #default="scope">
                <span>{{ formatResultValue(scope.row.checkData, 'checkData') }}</span>
              </template>
            </el-table-column>
          </el-table>
          <!-- 指标体（零点/跨度/多点/精密度/转换率共用）：标量两列排布，序列值整行换行展示 -->
          <el-descriptions v-else :column="2" border size="small" class="result-desc">
            <el-descriptions-item
              v-for="item in resultItems"
              :key="item.key"
              :label="item.label"
              :span="item.isList ? 2 : 1"
            >
              <span :class="{ 'result-value-list': item.isList }">{{ item.text }}</span>
            </el-descriptions-item>
          </el-descriptions>
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

      <!-- ==== 执行阶段 ==== -->
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

      <!-- 旧版扁平 execution_log 兜底：指标散在根级（无 params/result 结构）时按结果口径展示 -->
      <div v-if="flatResultMetrics.length" class="all-data-section">
        <h5>执行记录数据</h5>
        <div class="data-grid">
          <div v-for="item in flatResultMetrics" :key="item.key" class="data-item">
            <span class="data-label">{{ item.label }}:</span>
            <span class="data-value">{{ item.text }}</span>
          </div>
        </div>
      </div>

      <div v-if="showNoDataHint" class="no-data">
        <p>暂无详细记录数据</p>
      </div>

        </el-tab-pane>
        <el-tab-pane label="过程展示" name="process" lazy>
          <!-- :key=row.id——弹窗关闭后 row 被父级重置（id 变化）强制重挂载，旧记录的轮询/图表随组件卸载清理 -->
          <QcRecordProcessPanel v-if="visible" :key="row.id" :row="row" :active="activeTab === 'process'" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-dialog>
</template>

<script setup name="ExecutionLogDetailDialog">
import { getCurrentInstance, nextTick, ref, computed } from 'vue';
import { VideoPlay, CircleCheck, CircleClose, Loading, Clock } from '@element-plus/icons-vue';
import { getRecords, getExecutionPhases } from '@/api/quality_control/records';
import { useExecutionPolling } from '../../composables/useExecutionPolling';
import QcRecordProcessPanel from './QcRecordProcessPanel.vue';
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
/** 详情弹窗当前页：phase=执行阶段（默认），process=过程展示（关闭弹窗时复位） */
const activeTab = ref('phase');
const row = ref({});
const parsedExecutionLog = ref({});
const phaseList = ref([]);
/** 详情弹窗内的气体符号（SO2/NO2/O3/CO…）：概要「质控参数」与参数区展示共用 */
const executionDetailGasSymbol = ref('');

/**
 * 质控类型权威中文命名（全弹窗统一术语）。
 * execution_log.params 存英文名（*_check，composer 的 ExecutorType 契约），
 * qcm_record.quality_control_type 行级列存数字码——两态都要能翻，任何位置不得露出英文原始值。
 */
const QC_TYPE_TEXTS = {
  zero_check: '零点检查',
  span_check: '跨度检查',
  multi_check: '多点检查',
  precision_check: '精密度检查',
  accuracy_check: '准确度检查',
  conversion_check: '转换率检查',
  audit_span_check: '人工核查',
  multi_zero_check: '多仪器零点质控'
};

/** 行级数字码 → 英文名（对齐后端 QualityControlTypeEnum 的 code/name 次序） */
const QC_TYPE_CODE_TO_KEY = {
  0: 'zero_check',
  1: 'span_check',
  2: 'multi_check',
  3: 'precision_check',
  4: 'accuracy_check',
  5: 'conversion_check',
  6: 'audit_span_check',
  7: 'multi_zero_check'
};

/** 任务类型（qcm_record.task_type 存数字码）：0 计划调度 / 1 手动 / 2 现场 / 3 远程（SDK） */
const TASK_TYPE_TEXTS = {
  0: '计划触发',
  1: '手动触发',
  2: '现场任务',
  3: '远程平台触发'
};

/** 质控参数数字编码 → 化学符号（对齐后端 ParameterEnum；新结构 params 里通常已是符号本身） */
const PARAM_GAS_TEXTS = { 1: 'SO2', 2: 'NO2', 3: 'O3', 4: 'CO', 5: 'PM10', 6: 'PM2.5' };

/** 标气入口旧值归一：历史数据存「跨度检查/测量」，统一映射为气路口径名 */
const STD_GAS_PORT_TEXTS = { '跨度检查': '跨度口', '测量': '采样口' };

/**
 * 任务参数中文名与展示次序（Object.keys 的插入次序即渲染次序）。
 * 未登记的键一律不展示——防止英文裸键露出；新增 params 键须先在此登记。
 */
const PARAM_LABELS = {
  qualityControlType: '质控类型',
  parameter: '质控参数',
  gas: '质控参数',
  taskType: '触发方式',
  planId: '所属质控计划',
  triggerUser: '触发人／远程来源',
  concentrationPpb: '标气浓度',
  spanConcentrationPpb: '标气浓度',
  // stdGasConcentration 是质控完成时从标准气逻辑设备快照的钢瓶原气浓度（气瓶档案口径，通常 ppm 量级），
  // 与「标气浓度」（concentrationPpb 等＝任务稀释后通入分析仪的目标浓度，ppb）语义不同，命名与单位都须区分
  stdGasConcentration: '钢瓶原气浓度（快照）',
  genGasConc: '标气浓度',
  targetFlowLpm: '目标稀释流量',
  flowRateLpm: '目标稀释流量',
  targetFlow: '目标稀释流量',
  stableTimeSeconds: '标气稳定等待',
  genGasTime: '生成气体时间',
  sampleCount: '采样次数',
  readDataCount: '采样次数',
  sampleIntervalSeconds: '采样间隔',
  readDataSpan: '采样间隔',
  recoveryDelaySeconds: '恢复等待',
  multiPointPercents: '校准点位（量程百分比）',
  accuracyPointPercents: '核查点位（量程百分比）',
  stdGasInPortName: '标气入口（跨度口/采样口）',
  deviceId: '设备ID'
};
const PARAM_DISPLAY_ORDER = Object.keys(PARAM_LABELS);

/**
 * 执行记录详情中不展示的 params 键：
 * - taskDescription / taskName / triggerType / user：调度框架注入的元信息（触发方式已由行级 taskType 翻译展示）。
 */
const EXECUTION_DETAIL_HIDDEN_PARAM_KEYS = new Set([
  'taskDescription',
  'taskName',
  'triggerType',
  'user'
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

/** 结果字段中文名（通用口径；类型特化见 RESULT_LABEL_OVERRIDES） */
const resultDisplayNames = {
  deviceValue: '仪器示值均值',
  stdValue: '标气浓度',
  resultValue: '判定指标',
  checkPassLimit: '通过限值',
  checkCalibLimit: '校准限值',
  verificationValue: '校准后复核示值',
  slope: '校准曲线斜率',
  intercept: '校准曲线截距',
  correlation: '相关系数',
  relativeError: '相对误差',
  deviceValues: '各点仪器示值序列',
  stdValues: '各点标气浓度序列',
  precision: '精密度（RSD）',
  mean: '平均值',
  standardDeviation: '标准偏差',
  deviceStdGas: '核查用标气浓度',
  check_a_min: '斜率下限',
  check_a_max: '斜率上限',
  check_r_min: '相关系数下限',
  check_b_scope: '截距限值区间',
  checkRsd20Max: '精密度限值',
  efficiency: 'NOx 转化效率',
  origNoDatas: '原始 NO 数据序列',
  origNoxDatas: '原始 NOx 数据序列',
  remNoDatas: '滴定后 NO 数据序列',
  remNoxDatas: '滴定后 NOx 数据序列',
  origNoAvg: '原始 NO 平均值',
  origNoxAvg: '原始 NOx 平均值',
  remNoAvg: '滴定后 NO 平均值',
  remNoxAvg: '滴定后 NOx 平均值',
  isPass: '是否通过',
  isException: '是否异常',
  resultMessage: '结果说明',
  errorMessage: '错误信息',
  checkData: '核查读数',
  checkTime: '核查时间'
  // 注：devicesStdGas 是后端与 deviceStdGas 同值双写的兼容键，不重复登记展示
};

/** 同一键在不同质控类型下的业务含义不同（composer 判定语义），按类型特化命名 */
const RESULT_LABEL_OVERRIDES = {
  zero_check: { resultValue: '零点漂移' },
  span_check: { resultValue: '跨度漂移' },
  audit_span_check: { resultValue: '跨度漂移' }
};

/** 零点/跨度/人工核查共用一套指标体次序（CheckResult 载荷） */
const RESULT_ORDER_CHECK_LIKE = [
  'deviceValue', 'stdValue', 'resultValue',
  'checkPassLimit', 'checkCalibLimit', 'verificationValue', 'isPass'
];

/**
 * 各质控类型结果指标的展示次序；check_a_range 为合成键（check_a_min + check_a_max 合并成区间一行）。
 * 不在序列内但已登记中文名的键由 resultItems 兜底追加，防类型识别偏差导致整段丢失。
 */
const RESULT_FIELD_ORDER = {
  zero_check: RESULT_ORDER_CHECK_LIKE,
  span_check: RESULT_ORDER_CHECK_LIKE,
  audit_span_check: RESULT_ORDER_CHECK_LIKE,
  multi_check: [
    'slope', 'intercept', 'correlation', 'check_a_range', 'check_r_min', 'check_b_scope',
    'stdValues', 'deviceValues', 'isPass'
  ],
  accuracy_check: [
    'slope', 'intercept', 'correlation', 'relativeError', 'check_a_range', 'check_r_min', 'check_b_scope',
    'stdValues', 'deviceValues', 'isPass'
  ],
  precision_check: [
    'mean', 'standardDeviation', 'precision', 'checkRsd20Max', 'deviceStdGas', 'deviceValues', 'isPass'
  ],
  conversion_check: [
    'efficiency', 'origNoAvg', 'origNoxAvg', 'remNoAvg', 'remNoxAvg',
    'origNoDatas', 'origNoxDatas', 'remNoDatas', 'remNoxDatas', 'isPass'
  ]
};

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
  // 行级旧列 standardValue/monitoringData/calculatedValue 不并入：与执行结果区
  // （标气浓度 / 仪器示值均值 / 判定指标）同值双显，且无 params 的记录本来就读不到业务参数
  put('taskType', r.taskType);
  put('qualityControlType', r.qualityControlType);
  put('parameter', r.parameter);
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
    // 根级散落的指标键属于执行结果口径（旧版扁平日志），不并入任务参数，由「执行记录数据」兜底区展示
    if (k in resultDisplayNames) {
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

/** 当前记录的质控类型，统一归一为英文 key（行级数字码与 params 英文名两态兼容）；未知编码返回 '' */
const qcTypeKey = computed(() => {
  const raw = mergedExecutionParams.value.qualityControlType ?? row.value.qualityControlType;
  if (raw == null || raw === '') {
    return '';
  }
  const s = String(raw);
  if (QC_TYPE_TEXTS[s]) {
    return s;
  }
  return QC_TYPE_CODE_TO_KEY[s] || '';
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

/** 概要「是否通过」：优先结构化 result.isPass，其次 statusMap，最后行级旧列 */
const summaryPassText = computed(() => {
  const r = parsedExecutionLog.value?.result;
  const sm = parsedExecutionLog.value?.statusMap;
  let v;
  if (r && !Array.isArray(r) && typeof r === 'object' && r.isPass != null) {
    v = r.isPass;
  } else if (sm && sm.isPass != null) {
    v = sm.isPass;
  } else if (row.value?.isPass != null) {
    v = row.value.isPass;
  } else {
    return '—';
  }
  return formatResultValue(v, 'isPass');
});

/** 概要条：类型/参数/触发方式/结论/起止时间一行速览 */
const summaryItems = computed(() => {
  const r = row.value || {};
  const items = [
    { label: '质控类型', text: qcTypeKey.value ? QC_TYPE_TEXTS[qcTypeKey.value] : '—' },
    { label: '质控参数', text: executionDetailGasSymbol.value || '—' },
    { label: '触发方式', text: TASK_TYPE_TEXTS[String(r.taskType)] || '—' },
    { label: '是否通过', text: summaryPassText.value }
  ];
  if (r.startTime) {
    items.push({ label: '开始时间', text: String(r.startTime) });
  }
  const end = displayRecordEndTime(r);
  if (end) {
    items.push({ label: '结束时间', text: end });
  }
  return items;
});

/** 任务参数展示项：按登记次序渲染；parameter/gas 恒同值，同时存在只展示一条 */
const paramItems = computed(() => {
  const src = mergedExecutionParams.value;
  const items = [];
  for (const key of PARAM_DISPLAY_ORDER) {
    if (key === 'gas' && 'parameter' in src) {
      continue;
    }
    if (!(key in src)) {
      continue;
    }
    const v = src[key];
    if (v === null || v === undefined || v === '') {
      continue;
    }
    items.push({ key, label: PARAM_LABELS[key], text: formatParamValue(v, key) });
  }
  return items;
});

/** 结果指标展示项：标量走 descriptions 两列，序列（deviceValues 等）整行换行展示 */
const resultItems = computed(() => {
  const r = parsedExecutionLog.value?.result;
  if (!r || Array.isArray(r) || typeof r !== 'object') {
    return [];
  }
  const type = qcTypeKey.value;
  const used = new Set();
  const items = [];
  const push = (key, label, text, isList) => {
    used.add(key);
    items.push({ key, label, text, isList: !!isList });
  };
  for (const key of RESULT_FIELD_ORDER[type] || []) {
    if (key === 'check_a_range') {
      // 斜率上下限合并为单行区间（多点/准确度的判定标准是 a∈[min,max] 整体区间）
      if (r.check_a_min !== undefined && r.check_a_max !== undefined) {
        used.add('check_a_min');
        used.add('check_a_max');
        items.push({
          key: 'check_a_range',
          label: '斜率限值区间',
          text: `${numText(r.check_a_min)} ~ ${numText(r.check_a_max)}`,
          isList: false
        });
      }
      continue;
    }
    if (!(key in r)) {
      continue;
    }
    const override = RESULT_LABEL_OVERRIDES[type];
    const label = (override && override[key]) || resultDisplayNames[key] || key;
    push(key, label, formatResultValue(r[key], key, type), Array.isArray(r[key]));
  }
  // 兜底：不在类型序列内但已登记中文名的键也展示；未登记键不展示（禁英文裸键）
  for (const key of Object.keys(r)) {
    if (used.has(key) || !(key in resultDisplayNames)) {
      continue;
    }
    push(key, resultDisplayNames[key], formatResultValue(r[key], key, type), Array.isArray(r[key]));
  }
  // 结果说明/错误信息（statusMap）非空时并入结果区，避免只看指标错过失败原因
  const sm = parsedExecutionLog.value?.statusMap;
  if (sm && typeof sm === 'object') {
    if (sm.resultMessage && String(sm.resultMessage).trim()) {
      push('resultMessage', '结果说明', String(sm.resultMessage), false);
    }
    if (sm.errorMessage && String(sm.errorMessage).trim()) {
      push('errorMessage', '错误信息', String(sm.errorMessage), false);
    }
  }
  return items;
});

/** 旧版扁平 execution_log（指标散在根级、无 params/result 结构）：按结果口径展示根级指标键 */
const flatResultMetrics = computed(() => {
  const p = parsedExecutionLog.value;
  if (!p || typeof p !== 'object') {
    return [];
  }
  return Object.keys(p)
    .filter(k => k in resultDisplayNames)
    .map(k => ({ key: k, label: resultDisplayNames[k], text: formatResultValue(p[k], k) }));
});

const showNoDataHint = computed(() => {
  if (!row.value) {
    return false;
  }
  const p = parsedExecutionLog.value;
  const noParsed = !p || typeof p !== 'object' || Object.keys(p).length === 0;
  const noPhases = !phaseList.value || phaseList.value.length === 0;
  const noMerged = Object.keys(mergedExecutionParams.value).length === 0;
  const noResult = !hasStructuredExecutionResult.value
    && !statusMapHasDisplayableFields.value
    && flatResultMetrics.value.length === 0;
  return noParsed && noPhases && noMerged && noResult;
});

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
 * 从行数据与执行日志 params 推断气体符号（概要与参数区的「质控参数」显示用）。
 */
function resolveExecutionDetailGasSymbol(r, parsed) {
  const params = parsed && typeof parsed === 'object' ? parsed.params : null;
  const raw = params?.gas ?? params?.parameter ?? r?.parameter;
  if (raw == null || raw === '') {
    return '';
  }
  const s = String(raw);
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

// ---------- 数值格式化（单位集中在这一组函数，模板不散落拼接） ----------

/** 宽松转数值：失败返回 null（交由调用方按原样展示） */
function toNumber(value) {
  const n = typeof value === 'number' ? value : parseFloat(String(value).replace(/,/g, ''));
  return Number.isNaN(n) ? null : n;
}

/** 数值文本：整数不带小数、小数最多 2 位并去尾零（400.0 → 400；96.125 → 96.13） */
function numText(value) {
  const n = toNumber(value);
  if (n === null) {
    return String(value);
  }
  if (Number.isInteger(n)) {
    return String(n);
  }
  return String(Number(n.toFixed(2)));
}

/** 相关系数等高精度值：最多 4 位小数（0.9995 若按 2 位会截成 1） */
function preciseNumText(value) {
  const n = toNumber(value);
  if (n === null) {
    return String(value);
  }
  return String(Number(n.toFixed(4)));
}

/** 漂移类带方向符：正值显式 +，负值自带 -（+2.5% / -96.13%） */
function signedNumText(value) {
  const n = toNumber(value);
  if (n === null) {
    return String(value);
  }
  return (n > 0 ? '+' : '') + numText(n);
}

/** 标量兜底：布尔转是否、数组以顿号连接、其余按原样字符串 */
function scalarText(value) {
  if (Array.isArray(value)) {
    return value.length
      ? value.map(v => (typeof v === 'number' ? numText(v) : String(v))).join('、')
      : '—';
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否';
  }
  return String(value);
}

function formatPpb(value) {
  const n = toNumber(value);
  return n === null ? String(value) : `${numText(n)} ppb`;
}

/** 标气浓度：0 视为零气（零点检查通入的是零气） */
function formatStdGasConcPpb(value) {
  const n = toNumber(value);
  if (n === null) {
    return String(value);
  }
  return n === 0 ? '0 ppb（零气）' : `${numText(n)} ppb`;
}

/**
 * 钢瓶原气浓度（快照）：值是气瓶档案浓度（通常 ppm 量级），历史快照无单位键，不得硬贴 ppb：
 * 优先行级冻结对 gasConcentration+gasConcentrationUnit（有单位带单位；旧数据无单位裸值展示不猜）；
 * 行级缺失回退 execution_log 根级快照值（新数据根级带 stdGasConcentrationUnit 键则拼上，旧数据裸值）。
 */
function formatCylinderGasConc(value) {
  const rowConc = row.value?.gasConcentration;
  if (rowConc !== null && rowConc !== undefined && rowConc !== '') {
    const u = String(row.value?.gasConcentrationUnit || '').trim();
    const n = toNumber(rowConc);
    const text = n === null ? String(rowConc) : numText(rowConc);
    return u ? `${text} ${u}` : text;
  }
  const n = toNumber(value);
  const u = String(parsedExecutionLog.value?.stdGasConcentrationUnit || '').trim();
  if (n === null) {
    return u ? `${String(value)} ${u}` : String(value);
  }
  return u ? `${numText(n)} ${u}` : numText(n);
}

/** 目标稀释流量：固定 1 位小数（4.0 L/min） */
function formatFlowLpm(value) {
  const n = toNumber(value);
  return n === null ? String(value) : `${n.toFixed(1)} L/min`;
}

/** 对称限值 → ppb 绝对区间（漂移判据为 |drift| ≤ limit，零点类） */
function formatPpbLimitRange(value) {
  const n = toNumber(value);
  if (n === null) {
    return String(value);
  }
  const a = Math.abs(n).toFixed(1);
  return `-${a} ~ +${a} ppb`;
}

/** 对称限值 → 百分比绝对区间（跨度/人工核查类） */
function formatPercentLimitRange(value) {
  const n = toNumber(value);
  if (n === null) {
    return String(value);
  }
  const a = Math.abs(n).toFixed(1);
  return `-${a}% ~ +${a}%`;
}

/** 量程百分比序列（composer 契约为 0~1 小数）：×100 后合并展示（80%、60%、40%、20%） */
function formatPercentList(value) {
  if (!Array.isArray(value)) {
    return scalarText(value);
  }
  return value
    .map(v => {
      const n = toNumber(v);
      return n === null ? String(v) : `${Math.round(n * 100)}%`;
    })
    .join('、');
}

/** 质控类型显示：params 英文名与行级数字码两态兼容；未知编码不上英文裸值 */
function qcTypeTextOf(value) {
  const s = String(value);
  if (QC_TYPE_TEXTS[s]) {
    return QC_TYPE_TEXTS[s];
  }
  const key = QC_TYPE_CODE_TO_KEY[s];
  return key ? QC_TYPE_TEXTS[key] : '—';
}

/** 质控参数显示：数字编码翻化学符号（对齐后端 ParameterEnum），已是符号的直接大写 */
function gasTextOf(value) {
  const s = String(value);
  if (PARAM_GAS_TEXTS[s]) {
    return PARAM_GAS_TEXTS[s];
  }
  return /^[A-Za-z0-9.]+$/.test(s) ? s.toUpperCase() : s;
}

// 格式化参数值（任务参数区的唯一值出口）
function formatParamValue(value, key) {
  if (value === null || value === undefined) {
    return '无';
  }
  switch (key) {
    case 'qualityControlType':
      return qcTypeTextOf(value);
    case 'parameter':
    case 'gas':
      return gasTextOf(value);
    case 'taskType':
      return TASK_TYPE_TEXTS[String(value)] || '—';
    case 'concentrationPpb':
    case 'spanConcentrationPpb':
    case 'genGasConc':
      return formatStdGasConcPpb(value);
    case 'stdGasConcentration':
      // 钢瓶原气浓度是档案值（通常 ppm），不是任务稀释后的 ppb 目标浓度，禁止走 ppb 格式化
      return formatCylinderGasConc(value);
    case 'targetFlowLpm':
    case 'flowRateLpm':
    case 'targetFlow':
      return formatFlowLpm(value);
    case 'stableTimeSeconds':
    case 'genGasTime':
    case 'recoveryDelaySeconds':
    case 'sampleIntervalSeconds':
    case 'readDataSpan':
      return `${numText(value)} 秒`;
    case 'sampleCount':
    case 'readDataCount':
      return `${numText(value)} 次`;
    case 'multiPointPercents':
    case 'accuracyPointPercents':
      return formatPercentList(value);
    case 'stdGasInPortName':
      return STD_GAS_PORT_TEXTS[value] || String(value);
    default:
      return scalarText(value);
  }
}

// 格式化结果值（执行结果区/兜底区的唯一值出口；type 缺省取当前记录质控类型）
function formatResultValue(value, key, type = qcTypeKey.value) {
  if (value === null || value === undefined) {
    return '无';
  }
  if (Array.isArray(value)) {
    // 指标序列（各点示值/标气/原始·滴定数据）一律 ppb 口径，整行换行展示
    return value.length
      ? `${value.map(v => (typeof v === 'number' ? numText(v) : String(v))).join('、')} ppb`
      : '空';
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  switch (key) {
    case 'isPass':
      return (value === true || value === 'true') ? '通过' : '未通过';
    case 'isException':
      return (value === true || value === 'true') ? '是' : '否';
    case 'resultValue':
      // 判定指标单位随类型：零点为 ppb 绝对量，跨度/人工核查为相对漂移 %
      if (type === 'zero_check' || type === 'multi_zero_check') {
        return `${signedNumText(value)} ppb`;
      }
      if (type === 'span_check' || type === 'audit_span_check') {
        return `${signedNumText(value)}%`;
      }
      return signedNumText(value);
    case 'checkPassLimit':
    case 'checkCalibLimit':
      // 限值按绝对区间展示（用户决策②）：判据是 |drift| ≤ limit 的对称区间
      if (type === 'zero_check' || type === 'multi_zero_check') {
        return formatPpbLimitRange(value);
      }
      if (type === 'span_check' || type === 'audit_span_check') {
        return formatPercentLimitRange(value);
      }
      return numText(value);
    case 'stdValue':
      return formatStdGasConcPpb(value);
    case 'deviceValue':
    case 'verificationValue':
    case 'intercept':
    case 'mean':
    case 'standardDeviation':
    case 'deviceStdGas':
    case 'origNoAvg':
    case 'origNoxAvg':
    case 'remNoAvg':
    case 'remNoxAvg':
    case 'checkData':
      return formatPpb(value);
    case 'slope':
      return numText(value);
    case 'correlation':
      return preciseNumText(value);
    case 'relativeError':
      return `${signedNumText(value)}%`;
    case 'precision':
    case 'efficiency':
      return `${numText(value)}%`;
    case 'checkRsd20Max':
      return `≤ ${numText(value)}%`;
    case 'check_r_min':
      return `≥ ${preciseNumText(value)}`;
    case 'check_b_scope':
      // 截距限值：|b| ≤ 满量程 1%，存的是正数上界，按对称区间展示
      return formatPpbLimitRange(value);
    default:
      return scalarText(value);
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
  // 复位到执行阶段页：过程展示面板随 active=false 停轮询、随 row 重置(v-if/key)卸载清理 echarts
  activeTab.value = 'phase';
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
  activeTab.value = 'phase';
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

/* 概要条：弹窗顶部业务速览 */
.qc-summary-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  background: #f0f9ff;
  padding: 8px 12px;
  margin-bottom: 15px;
  border-radius: 4px;
  font-size: 12px;
  color: #666;
}

.qc-summary-item strong {
  font-weight: 600;
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

/* 弹窗 top=5vh，内容区滚动（占位约 76vh，预留标题/内边距） */
.execution-log-dialog-content {
  max-height: 76vh;
  overflow-y: auto;
}

/* tabs 分页：页签紧凑，内容区不再额外缩进 */
.qc-detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.result-semantics-alert {
  margin-bottom: 12px;
}

/* 序列类指标（各点示值/标气等）整行展示并允许换行 */
.result-value-list {
  word-break: break-all;
}

.result-table {
  margin-bottom: 20px;
}

.result-table .el-table__header-wrapper th {
  background-color: #f0f9ff;
  color: #409eff;
  font-weight: 600;
}

.params-section h5, .result-section h5, .all-data-section h5, .phase-section h5 {
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

  .result-table {
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
