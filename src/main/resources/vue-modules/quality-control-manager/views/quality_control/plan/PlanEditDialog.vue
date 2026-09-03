<template>
  <el-dialog
    :title="form.id != null ? '编辑质控计划' : '新建质控计划'"
    v-model="visible"
    width="860px"
    append-to-body
    destroy-on-close
    @closed="resetAll"
  >
    <el-steps :active="step" finish-status="success" simple>
      <el-step title="调度方式" />
      <el-step title="类型与仪器" />
      <el-step title="浓度" />
      <el-step title="流量" />
      <el-step title="时长参数" />
    </el-steps>

    <div class="qc-plan-step-body" v-loading="submitting">
      <!-- 步骤 1：调度方式（FR-01-26..28 / FR-01-42 有效期） -->
      <div v-show="step === 0">
        <el-form label-width="120px">
          <el-form-item label="调度方式">
            <el-radio-group v-model="form.scheduleType">
              <el-radio value="DAILY">每天</el-radio>
              <el-radio value="WEEKLY">每周</el-radio>
              <el-radio value="MONTHLY">每月</el-radio>
              <el-radio value="ONCE">一次性</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="form.scheduleType === 'DAILY'" label="触发时刻">
            <el-time-picker v-model="dayTime" format="HH:mm" value-format="HH:mm" placeholder="选择时刻" />
          </el-form-item>
          <template v-if="form.scheduleType === 'WEEKLY'">
            <el-form-item label="星期">
              <el-checkbox-group v-model="form.weekdays">
                <el-checkbox v-for="(name, i) in WEEKDAY_NAMES" :key="name" :value="i + 1">{{ name }}</el-checkbox>
              </el-checkbox-group>
            </el-form-item>
            <el-form-item label="触发时刻">
              <el-time-picker v-model="dayTime" format="HH:mm" value-format="HH:mm" placeholder="选择时刻" />
            </el-form-item>
          </template>
          <template v-if="form.scheduleType === 'MONTHLY'">
            <el-form-item label="每月几号">
              <el-checkbox-group v-model="form.monthDays" class="qc-plan-month-grid">
                <el-checkbox v-for="d in 31" :key="d" :value="d">{{ d }}</el-checkbox>
              </el-checkbox-group>
            </el-form-item>
            <el-form-item label="触发时刻">
              <el-time-picker v-model="dayTime" format="HH:mm" value-format="HH:mm" placeholder="选择时刻" />
            </el-form-item>
          </template>
          <template v-if="form.scheduleType === 'ONCE'">
            <el-form-item label="触发模式">
              <el-radio-group v-model="form.onceMode">
                <el-radio value="IMMEDIATE">立刻</el-radio>
                <el-radio value="SCHEDULED">指定时刻</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="form.onceMode === 'SCHEDULED'" label="触发时刻">
              <el-date-picker v-model="form.onceAt" type="datetime" placeholder="选择触发时刻"
                value-format="YYYY-MM-DDTHH:mm:ss[Z]" />
            </el-form-item>
          </template>
          <el-form-item label="有效期起">
            <el-date-picker v-model="form.planStartTime" type="datetime" placeholder="留空 = 立即生效"
              value-format="YYYY-MM-DDTHH:mm:ss[Z]" />
          </el-form-item>
          <el-form-item label="有效期止">
            <el-date-picker v-model="form.planEndTime" type="datetime" placeholder="留空 = 长期有效"
              value-format="YYYY-MM-DDTHH:mm:ss[Z]" />
          </el-form-item>
        </el-form>
      </div>

      <!-- 步骤 2：类型 + 仪器 + 计划名称（FR-01-27..28） -->
      <div v-show="step === 1">
        <el-form label-width="120px">
          <el-form-item label="计划名称">
            <el-input v-model="form.planName" placeholder="请输入计划名称" maxlength="100" show-word-limit />
          </el-form-item>
          <el-form-item label="质控类型">
            <el-select v-model="form.qcType" placeholder="请选择质控类型" @change="onQcTypeChange">
              <el-option v-for="t in QC_TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="仪器">
            <el-checkbox-group v-model="instrumentSelection" @change="onInstrumentChange">
              <el-checkbox v-for="i in INSTRUMENT_OPTIONS" :key="i.value" :value="i.value"
                :disabled="instrumentDisabled(i.value)">{{ i.label }}</el-checkbox>
            </el-checkbox-group>
            <div class="qc-plan-hint">{{ instrumentHint }}</div>
          </el-form-item>
        </el-form>
      </div>

      <!-- 步骤 3：浓度（FR-01-29） -->
      <div v-show="step === 2">
        <el-form label-width="160px">
          <template v-if="form.qcType === 'zero_check' || form.qcType === 'multi_zero_check'">
            <el-form-item label="标气浓度" required>
              <el-input model-value="0" disabled style="width: 180px" />
              <span class="qc-plan-unit-tag">{{ concentrationUnit }}</span>
              <div class="qc-plan-hint qc-plan-hint-block">零点检查固定为零气（0 浓度）</div>
            </el-form-item>
          </template>
          <template v-else-if="form.qcType === 'span_check' || form.qcType === 'audit_span_check'">
            <el-form-item :label="'标气浓度 (' + concentrationUnit + ')'" required>
              <el-input-number v-model="concentrationInput" :min="0.1" :precision="3" :step="10" />
              <span class="qc-plan-hint">{{ isCoInstrument ? 'CO 的浓度以 ppm 计，提交时自动换算为 ppb' : '必填，大于 0' }}</span>
            </el-form-item>
          </template>
          <template v-else-if="pointPercentTypes.includes(form.qcType)">
            <el-form-item label="量程百分比序列" required>
              <el-input v-model="pointPercentsText" placeholder="如 0,10,20,40,60,80（百分比，升序，至少 2 项）" style="width: 360px" />
              <div class="qc-plan-hint">按百分比显示（存储为 0~1 小数）；须严格升序且至少 2 项</div>
            </el-form-item>
          </template>
          <template v-else>
            <el-alert title="本类型无浓度设置（按规程执行）" type="info" :closable="false" show-icon />
          </template>
        </el-form>
        <!-- 浓度点预估表（multi / accuracy 按量程百分比展开；precision / conversion 仅 SPAN 单点时展示） -->
        <div v-if="pointPercentTypes.includes(form.qcType) || form.qcType === 'precision_check' || form.qcType === 'conversion_check'">
          <h5 class="qc-plan-points-title">浓度点预估</h5>
          <el-table v-if="estimatePoints && estimatePoints.length" :data="estimatePoints" size="small" border
            v-loading="pointsLoading" style="max-width: 420px">
            <el-table-column label="阶段序号" type="index" width="90" align="center" />
            <el-table-column label="量程占比" prop="percent" align="center">
              <template #default="{ row }">{{ Math.round(row.percent * 100) + '%' }}</template>
            </el-table-column>
            <el-table-column label="浓度" align="center">
              <template #default="{ row }">{{ row.displayValue }} {{ row.unit }}</template>
            </el-table-column>
          </el-table>
          <div v-else class="qc-plan-hint qc-plan-hint-block">{{ pointsLoading ? '浓度点估算中…' : '暂无预估' }}</div>
        </div>
      </div>

      <!-- 步骤 4：流量（FR-01-31；D19 全类型可设） -->
      <div v-show="step === 3">
        <el-alert class="qc-plan-calibrator-alert" type="info" :closable="false" show-icon
          title="当前校准仪：执行时自动选择" />
        <el-form label-width="160px">
          <el-form-item label="标气流量 (L/min)" required>
            <el-input-number v-model="form.flowRateLpm" :min="0.1" :max="50" :step="0.5" :precision="1" />
            <span class="qc-plan-hint">范围 (0, 50]，默认 4.0</span>
            <div class="qc-plan-hint qc-plan-hint-block">标气/零气发生流量，默认 4.0 L/min</div>
          </el-form-item>
        </el-form>
      </div>

      <!-- 步骤 5：时长参数 + 预估（FR-01-32..33） -->
      <div v-show="step === 4">
        <el-row :gutter="16">
          <el-col :span="14">
            <div class="qc-plan-duration-header">
              <span>时长参数</span>
              <el-button size="small" @click="resetDurationDefaults">恢复默认值</el-button>
            </div>
            <el-form label-width="180px">
              <el-form-item v-for="p in applicableDurationParams" :key="p.key" :label="p.label + ' (' + p.unit + ')'">
                <el-input-number v-model="durationValues[p.key]" :min="1" :step="1" :precision="0" />
                <span class="qc-plan-hint">默认 {{ p.defaultValue }}</span>
              </el-form-item>
            </el-form>
          </el-col>
          <el-col :span="10">
            <div class="qc-plan-estimate">
              <h5>执行时长预估</h5>
              <div v-if="form.qcType === 'multi_zero_check'" class="qc-plan-hint">composer flow 未接线，暂无预估</div>
              <div v-else-if="estimateLoading" class="qc-plan-hint">估算中…</div>
              <template v-else-if="estimateResult">
                <div class="qc-plan-estimate-item" v-for="ph in estimateResult.phases" :key="ph.id">
                  <span>{{ ph.displayName }}</span>
                  <span>{{ formatSeconds(ph.estimatedSeconds) }}</span>
                </div>
                <div class="qc-plan-estimate-item qc-plan-estimate-total">
                  <span>总预估</span>
                  <span>{{ formatSeconds(estimateResult.totalEstimatedSeconds) }}</span>
                </div>
                <div class="qc-plan-hint">恢复阶段固定 {{ estimateResult.recoverySeconds }} 秒</div>
              </template>
              <div v-else class="qc-plan-hint">{{ estimateError || '选择类型与仪器后自动估算' }}</div>
            </div>
          </el-col>
        </el-row>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button v-if="step > 0" @click="step--">上一步</el-button>
        <el-button v-if="step < 4" type="primary" @click="nextStep">下一步</el-button>
        <el-button v-if="step === 4" type="primary" :loading="submitting" @click="submitForm">提 交</el-button>
        <el-button @click="visible = false">取 消</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, getCurrentInstance, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { addPlan, updatePlan, estimatePlan } from '@/api/quality_control/plan';

defineOptions({ name: 'QcmPlanEditDialog' });

const { proxy } = getCurrentInstance();

const emit = defineEmits(['success']);

const visible = ref(false);
const step = ref(0);
const submitting = ref(false);

/** 质控类型闭集（与 QualityControlTypeEnum name 对齐） */
const QC_TYPE_OPTIONS = [
  { value: 'zero_check', label: '零点检查' },
  { value: 'span_check', label: '跨度检查' },
  { value: 'multi_check', label: '多点检查' },
  { value: 'precision_check', label: '精密度检查' },
  { value: 'accuracy_check', label: '准确度检查' },
  { value: 'conversion_check', label: '转换率检查' },
  { value: 'audit_span_check', label: '人工核查' },
  { value: 'multi_zero_check', label: '多仪器零点质控' }
];

/** 仪器候选闭集（代码键 NO2 ↔ 展示名 NOx） */
const INSTRUMENT_OPTIONS = [
  { value: 'SO2', label: 'SO2' },
  { value: 'NO2', label: 'NOx' },
  { value: 'CO', label: 'CO' },
  { value: 'O3', label: 'O3' }
];

/** 需要绝对浓度 / 支持量程百分比序列的类型（与 PlanParamValidator 同源规则） */
const CONCENTRATION_REQUIRED_TYPES = ['span_check', 'audit_span_check'];
const pointPercentTypes = ['multi_check', 'accuracy_check'];

/** 多仪器多选类型（FR-01-27：1~4 台；与 PlanParamValidator.MULTI_INSTRUMENT_TYPES 同源） */
const multiInstrumentTypes = ['multi_zero_check'];

/** 量程百分比默认序列（composer 默认，01 §6 FR-01-33：multi / accuracy） */
const DEFAULT_POINT_PERCENTS = {
  multi_check: [0, 0.1, 0.2, 0.4, 0.6, 0.8],
  accuracy_check: [0.1, 0.2, 0.4, 0.6, 0.8]
};

/**
 * 时长参数默认值常量表（01 §6 FR-01-33；key = durationOverrides 白名单）。
 * applicableTypes 用函数表达「适用类型」列；序列类 2 键由步骤 3 的
 * pointPercents 一等字段承载（composer 语义同源），不在本表重复编辑。
 */
const DURATION_PARAM_DEFS = [
  { key: 'commandDelaySeconds', label: '命令延迟', unit: '秒', defaultValue: 2, applicableTypes: null },
  { key: 'stableTimeSeconds', label: '稳定时间', unit: '秒', defaultValue: 1280, applicableTypes: t => t !== 'conversion_check' },
  { key: 'sampleCount', label: '采样次数', unit: '次', defaultValue: 3, applicableTypes: t => t !== 'conversion_check' },
  { key: 'sampleIntervalSeconds', label: '采样间隔', unit: '秒', defaultValue: 30, applicableTypes: t => t !== 'conversion_check' },
  { key: 'calibrationTimeSeconds', label: '校准时间', unit: '秒', defaultValue: 61, applicableTypes: t => t === 'zero_check' || t === 'span_check' || t === 'multi_zero_check' },
  { key: 'verificationStableTimeSeconds', label: '核查稳定时间', unit: '秒', defaultValue: 30, applicableTypes: t => t === 'zero_check' || t === 'span_check' || t === 'multi_zero_check' },
  { key: 'verificationSampleCount', label: '核查采样次数', unit: '次', defaultValue: 3, applicableTypes: t => t === 'zero_check' || t === 'span_check' || t === 'multi_zero_check' },
  { key: 'zeroGasOpenDelaySeconds', label: '零气开阀延迟', unit: '秒', defaultValue: 3, applicableTypes: t => t === 'zero_check' || t === 'multi_zero_check' },
  { key: 'recoveryDelaySeconds', label: '恢复延迟', unit: '秒', defaultValue: 60, applicableTypes: null },
  { key: 'precisionRounds', label: '精密度轮数', unit: '轮', defaultValue: 6, applicableTypes: t => t === 'precision_check' },
  { key: 'conversionRounds', label: '转换效率轮数', unit: '轮', defaultValue: 3, applicableTypes: t => t === 'conversion_check' },
  { key: 'gptTimeSeconds', label: 'GPT 时长', unit: '秒', defaultValue: 60, applicableTypes: t => t === 'conversion_check' }
];

const WEEKDAY_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

/** 表单状态（key 与 PlanSaveDto 完全一致；步间回退保留） */
const form = reactive({
  id: null,
  planName: '',
  scheduleType: 'DAILY',
  hour: 2,
  minute: 0,
  weekdays: [],
  monthDays: [],
  onceMode: 'IMMEDIATE',
  onceAt: null,
  qcType: null,
  instruments: [],
  concentrationPpb: null,
  pointPercents: null,
  flowRateLpm: 4.0,
  durationOverrides: null,
  planStartTime: null,
  planEndTime: null
});

const dayTime = ref('02:00');
const instrumentSelection = ref([]);
const pointPercentsText = ref('');
const durationValues = reactive({});

const estimateResult = ref(null);
const estimateLoading = ref(false);
const estimateError = ref('');

/** 步骤 3 浓度点表格：来自 estimate 接口 points（后端统一格式化 displayValue + unit） */
const estimatePoints = ref(null);
const pointsLoading = ref(false);
let estimateTimer = null;
let pointsTimer = null;

const applicableDurationParams = ref([]);

/** 供预览/提交的量程百分比（0~1 小数序列） */
function parsePointPercentsText(text) {
  return String(text || '')
    .split(',')
    .map(s => s.trim())
    .filter(s => s !== '')
    .map(s => Number(s) / 100);
}

function pointPercentsToText(percents) {
  if (!Array.isArray(percents)) {
    return '';
  }
  return percents.map(p => Math.round(p * 100)).join(',');
}

function applicableParamsFor(type) {
  return DURATION_PARAM_DEFS.filter(d => d.applicableTypes === null || d.applicableTypes(type));
}

/** 仪器步骤提示：多仪器类型多选 1~4 台，其余单选一台；转换效率锁定 NOx */
const instrumentHint = computed(() => {
  if (form.qcType === 'conversion_check') {
    return '当前类型单选一台仪器；转换效率检查锁定 NOx，不可更改';
  }
  if (multiInstrumentTypes.includes(form.qcType)) {
    return '多仪器零点质控：可多选 1~4 台仪器（触发后由一次 flow 并行驱动）';
  }
  return '当前类型单选一台仪器';
});

/** 类型切换：重置仪器互斥锁定、浓度/序列形态、时长参数默认值 */
function onQcTypeChange() {
  if (form.qcType === 'conversion_check') {
    instrumentSelection.value = ['NO2'];
    form.instruments = ['NO2'];
  } else {
    instrumentSelection.value = [];
    form.instruments = [];
  }
  form.concentrationPpb = null;
  if (pointPercentTypes.includes(form.qcType)) {
    if (form.pointPercents == null) {
      form.pointPercents = [...DEFAULT_POINT_PERCENTS[form.qcType]];
    }
    pointPercentsText.value = pointPercentsToText(form.pointPercents);
  } else {
    form.pointPercents = null;
    pointPercentsText.value = '';
  }
  form.flowRateLpm = form.flowRateLpm ?? 4.0;
  estimatePoints.value = null;
  initDurationValues();
}

function initDurationValues(savedOverrides) {
  for (const p of DURATION_PARAM_DEFS) {
    const saved = savedOverrides ? savedOverrides[p.key] : undefined;
    durationValues[p.key] = saved != null ? saved : p.defaultValue;
  }
  applicableDurationParams.value = applicableParamsFor(form.qcType);
}

/** 多仪器类型（multi_zero_check 等）保留多选；其余类型单选语义：只保留最后勾选的一台 */
function onInstrumentChange(vals) {
  if (form.qcType === 'conversion_check') {
    instrumentSelection.value = ['NO2'];
    form.instruments = ['NO2'];
    return;
  }
  if (vals.length > 1 && !multiInstrumentTypes.includes(form.qcType)) {
    instrumentSelection.value = [vals[vals.length - 1]];
  }
  form.instruments = [...instrumentSelection.value];
}

function instrumentDisabled(value) {
  if (form.qcType === 'conversion_check') {
    return value !== 'NO2';
  }
  return false;
}

/** 浓度展示单位：CO 输入/回显按 ppm，多仪器默认 ppb（提交仍统一 ppb，经 proxy 换算） */
const isCoInstrument = computed(() => form.instruments.includes('CO'));
const concentrationUnit = computed(() => (isCoInstrument.value && form.instruments.length === 1 ? 'ppm' : 'ppb'));

/**
 * 浓度输入代理：表单始终持有 ppb（存储/提交口径）；CO 仪器在输入框按 ppm 展示，
 * 写入时 ×1000、读取（含编辑回填）时 ÷1000 并保留 4 位小数去除浮点尾差。
 */
const concentrationInput = computed({
  get() {
    if (form.concentrationPpb == null) {
      return null;
    }
    return isCoInstrument.value && form.instruments.length === 1
      ? Math.round((form.concentrationPpb / 1000) * 10000) / 10000
      : form.concentrationPpb;
  },
  set(v) {
    if (v == null) {
      form.concentrationPpb = null;
      return;
    }
    form.concentrationPpb = isCoInstrument.value && form.instruments.length === 1 ? v * 1000 : v;
  }
});

function nextStep() {
  if (step.value === 0 && !validateScheduleStep()) {
    return;
  }
  if (step.value === 1 && !validateTypeStep()) {
    return;
  }
  if (step.value === 2 && !validateConcentrationStep()) {
    return;
  }
  if (step.value === 3 && !validateFlowStep()) {
    return;
  }
  step.value++;
  if (step.value === 4) {
    scheduleEstimate();
  }
}

function validateScheduleStep() {
  if (!form.scheduleType) {
    ElMessage.warning('请选择调度方式');
    return false;
  }
  if (form.scheduleType !== 'ONCE') {
    if (!dayTime.value) {
      ElMessage.warning('请选择触发时刻');
      return false;
    }
    const parts = String(dayTime.value).split(':');
    form.hour = Number(parts[0]);
    form.minute = Number(parts[1]);
    if (form.scheduleType === 'WEEKLY' && form.weekdays.length === 0) {
      ElMessage.warning('每周调度至少选择一个星期');
      return false;
    }
    if (form.scheduleType === 'MONTHLY' && form.monthDays.length === 0) {
      ElMessage.warning('每月调度至少选择一个日期');
      return false;
    }
  } else if (form.onceMode === 'SCHEDULED' && !form.onceAt) {
    ElMessage.warning('请选择一次性触发时刻');
    return false;
  }
  return true;
}

function validateTypeStep() {
  if (!form.planName || !form.planName.trim()) {
    ElMessage.warning('请输入计划名称');
    return false;
  }
  if (!form.qcType) {
    ElMessage.warning('请选择质控类型');
    return false;
  }
  if (multiInstrumentTypes.includes(form.qcType)) {
    if (form.instruments.length < 1 || form.instruments.length > 4) {
      ElMessage.warning('请选择 1~4 台仪器');
      return false;
    }
    return true;
  }
  if (form.instruments.length !== 1) {
    ElMessage.warning('请选择 1 台仪器');
    return false;
  }
  return true;
}

function validateConcentrationStep() {
  if (CONCENTRATION_REQUIRED_TYPES.includes(form.qcType)) {
    if (form.concentrationPpb == null || form.concentrationPpb <= 0) {
      ElMessage.warning('请填写大于 0 的标气浓度（ppb）');
      return false;
    }
  }
  if (pointPercentTypes.includes(form.qcType)) {
    const percents = parsePointPercentsText(pointPercentsText.value);
    if (percents.length < 2) {
      ElMessage.warning('量程百分比序列至少 2 项');
      return false;
    }
    for (const p of percents) {
      if (!Number.isFinite(p) || p < 0 || p > 1) {
        ElMessage.warning('量程百分比每项必须在 0~100 之间');
        return false;
      }
    }
    for (let i = 1; i < percents.length; i++) {
      if (percents[i] <= percents[i - 1]) {
        ElMessage.warning('量程百分比序列必须严格升序');
        return false;
      }
    }
    form.pointPercents = percents.map(p => Math.round(p * 1000) / 1000);
  }
  return true;
}

function validateFlowStep() {
  if (form.flowRateLpm == null || form.flowRateLpm <= 0 || form.flowRateLpm > 50) {
    ElMessage.warning('标气流量必须在 (0, 50] L/min 之间');
    return false;
  }
  return true;
}

/** 阶段预估：参数变更防抖 300ms 刷新（FR-01-32，只读不触发） */
function scheduleEstimate() {
  if (estimateTimer != null) {
    clearTimeout(estimateTimer);
  }
  estimateTimer = setTimeout(refreshEstimate, 300);
}

function refreshEstimate() {
  // multi_zero_check 的 composer flow 未接线：预估接口不可用，展示占位不发起请求
  if (form.qcType === 'multi_zero_check') {
    estimateResult.value = null;
    estimateError.value = '';
    return;
  }
  if (!form.qcType || form.instruments.length !== 1) {
    estimateResult.value = null;
    estimateError.value = '';
    return;
  }
  estimateLoading.value = true;
  estimateError.value = '';
  estimatePlan(buildEstimateDto()).then(response => {
    estimateLoading.value = false;
    if (response.code === 200) {
      estimateResult.value = response.data;
    } else {
      estimateResult.value = null;
      estimateError.value = response.msg || '预估失败';
    }
  }).catch(() => {
    estimateLoading.value = false;
    estimateResult.value = null;
    estimateError.value = '预估请求失败';
  });
}

function buildEstimateDto() {
  const dto = {
    qcType: form.qcType,
    instruments: [...form.instruments],
    durationOverrides: collectDurationOverrides()
  };
  if (form.concentrationPpb != null && form.concentrationPpb > 0) {
    dto.concentrationPpb = form.concentrationPpb;
  }
  if (form.flowRateLpm != null) {
    dto.flowRateLpm = form.flowRateLpm;
  }
  if (pointPercentTypes.includes(form.qcType)) {
    const percents = parsePointPercentsText(pointPercentsText.value);
    if (percents.length >= 2) {
      dto.pointPercents = percents.map(p => Math.round(p * 1000) / 1000);
    }
  }
  return dto;
}

/** 步骤 3 浓度点表格：百分比/仪器变更防抖 500ms 刷新（复用 estimate 接口，只读） */
function schedulePointsEstimate() {
  if (pointsTimer != null) {
    clearTimeout(pointsTimer);
  }
  pointsTimer = setTimeout(refreshPoints, 500);
}

function refreshPoints() {
  const pointsTypes = ['multi_check', 'accuracy_check', 'precision_check', 'conversion_check'];
  if (!pointsTypes.includes(form.qcType) || form.instruments.length !== 1) {
    estimatePoints.value = null;
    return;
  }
  pointsLoading.value = true;
  estimatePlan(buildEstimateDto()).then(response => {
    pointsLoading.value = false;
    if (response.code === 200) {
      estimatePoints.value = Array.isArray(response.data.points) ? response.data.points : [];
    } else {
      estimatePoints.value = [];
    }
  }).catch(() => {
    pointsLoading.value = false;
    estimatePoints.value = [];
  });
}

/** Fix-4：恢复默认值——时长表单重置为 DEFAULT 常量、清空 overrides 差量并触发预估刷新 */
function resetDurationDefaults() {
  for (const p of DURATION_PARAM_DEFS) {
    durationValues[p.key] = p.defaultValue;
  }
  if (step.value === 4) {
    scheduleEstimate();
  }
}

/** 稀疏覆盖：仅收集偏离默认值的键（duration_overrides 只存改过项） */
function collectDurationOverrides() {
  const overrides = {};
  for (const p of DURATION_PARAM_DEFS) {
    const v = durationValues[p.key];
    if (v != null && Number.isFinite(Number(v)) && Number(v) !== p.defaultValue) {
      overrides[p.key] = Math.round(Number(v));
    }
  }
  return Object.keys(overrides).length ? overrides : null;
}

function formatSeconds(sec) {
  const s = Number(sec);
  if (!Number.isFinite(s) || s < 0) {
    return '—';
  }
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = Math.floor(s % 60);
  const parts = [];
  if (h > 0) parts.push(h + '小时');
  if (m > 0) parts.push(m + '分钟');
  if (r > 0 || parts.length === 0) parts.push(r + '秒');
  return parts.join('');
}

/** 提交（校验失败后端逐条错误全量展示，FR-01-30 不静默修正） */
function submitForm() {
  if (!validateScheduleStep() || !validateTypeStep() || !validateConcentrationStep() || !validateFlowStep()) {
    return;
  }
  const dto = buildSaveDto();
  submitting.value = true;
  const req = dto.id != null ? updatePlan(dto) : addPlan(dto);
  req.then(response => {
    submitting.value = false;
    if (response.code === 200) {
      proxy.$modal.msgSuccess(dto.id != null ? '修改成功' : '创建成功');
      visible.value = false;
      emit('success');
    } else {
      ElMessage.error(response.msg || '保存失败');
    }
  }).catch(() => {
    submitting.value = false;
  });
}

function buildSaveDto() {
  const dto = {
    id: form.id,
    planName: form.planName.trim(),
    scheduleType: form.scheduleType,
    qcType: form.qcType,
    instruments: [...form.instruments],
    flowRateLpm: form.flowRateLpm,
    durationOverrides: collectDurationOverrides(),
    planStartTime: form.planStartTime || null,
    planEndTime: form.planEndTime || null
  };
  if (form.scheduleType === 'ONCE') {
    dto.hour = 0;
    dto.minute = 0;
    dto.onceMode = form.onceMode;
    dto.onceAt = form.onceMode === 'SCHEDULED' ? form.onceAt : null;
  } else {
    const parts = String(dayTime.value).split(':');
    dto.hour = Number(parts[0]);
    dto.minute = Number(parts[1]);
    if (form.scheduleType === 'WEEKLY') {
      dto.weekdays = [...form.weekdays].sort((a, b) => a - b);
    }
    if (form.scheduleType === 'MONTHLY') {
      dto.monthDays = [...form.monthDays].sort((a, b) => a - b);
    }
  }
  if (CONCENTRATION_REQUIRED_TYPES.includes(form.qcType)) {
    dto.concentrationPpb = form.concentrationPpb;
  }
  if (pointPercentTypes.includes(form.qcType)) {
    dto.pointPercents = parsePointPercentsText(pointPercentsText.value).map(p => Math.round(p * 1000) / 1000);
  }
  return dto;
}

/** 打开弹窗：plan 为 null = 新建；否则用详情行回填（含 jsonb 字段解析） */
function open(plan) {
  visible.value = true;
  step.value = 0;
  estimateResult.value = null;
  estimateError.value = '';
  estimatePoints.value = null;
  if (plan == null) {
    form.id = null;
    form.planName = '';
    form.scheduleType = 'DAILY';
    dayTime.value = '02:00';
    form.weekdays = [];
    form.monthDays = [];
    form.onceMode = 'IMMEDIATE';
    form.onceAt = null;
    form.qcType = null;
    form.instruments = [];
    instrumentSelection.value = [];
    form.concentrationPpb = null;
    form.pointPercents = null;
    pointPercentsText.value = '';
    form.flowRateLpm = 4.0;
    form.planStartTime = null;
    form.planEndTime = null;
    initDurationValues(null);
    return;
  }
  form.id = plan.id;
  form.planName = plan.planName || '';
  form.scheduleType = plan.scheduleType || 'DAILY';
  const config = parseJson(plan.scheduleConfig, {});
  form.hour = config.hour != null ? config.hour : 2;
  form.minute = config.minute != null ? config.minute : 0;
  dayTime.value = pad2(form.hour) + ':' + pad2(form.minute);
  form.weekdays = Array.isArray(config.weekdays) ? [...config.weekdays] : [];
  form.monthDays = Array.isArray(config.monthDays) ? [...config.monthDays] : [];
  form.onceMode = config.onceMode || 'IMMEDIATE';
  form.onceAt = config.onceAt || null;
  form.qcType = plan.qcType;
  const instruments = parseJson(plan.instruments, []);
  form.instruments = Array.isArray(instruments) ? [...instruments] : [];
  instrumentSelection.value = [...form.instruments];
  form.concentrationPpb = plan.concentrationPpb != null ? Number(plan.concentrationPpb) : null;
  const percents = parseJson(plan.pointPercents, null);
  form.pointPercents = Array.isArray(percents) ? [...percents] : null;
  pointPercentsText.value = pointPercentsToText(form.pointPercents);
  form.flowRateLpm = plan.flowRateLpm != null ? Number(plan.flowRateLpm) : 4.0;
  form.planStartTime = plan.planStartTime || null;
  form.planEndTime = plan.planEndTime || null;
  const overrides = parseJson(plan.durationOverrides, null);
  initDurationValues(overrides || null);
}

function pad2(n) {
  return String(n).padStart(2, '0');
}

function parseJson(text, fallback) {
  if (text == null || text === '') {
    return fallback;
  }
  try {
    return JSON.parse(text);
  } catch (e) {
    return fallback;
  }
}

function resetAll() {
  step.value = 0;
  estimateResult.value = null;
  estimateError.value = '';
  estimatePoints.value = null;
}

/** 进入步骤 5 后：时长参数 / 浓度 / 流量任何变更即防抖刷新预估 */
watch(() => [form.qcType, form.instruments.length, form.concentrationPpb, form.flowRateLpm, pointPercentsText.value,
  ...DURATION_PARAM_DEFS.map(p => durationValues[p.key])],
  () => {
    if (visible.value && step.value === 4) {
      scheduleEstimate();
    }
  }
);

/** 位于步骤 3 时：百分比序列 / 仪器变更即防抖刷新浓度点表格 */
watch(() => [pointPercentsText.value, form.instruments.join(',')],
  () => {
    if (visible.value && step.value === 2) {
      schedulePointsEstimate();
    }
  }
);

/** 进入步骤 3 即主动拉一次浓度点：类型/仪器在步骤 2 已选好，不构成本步"变更"（回归发现） */
watch(step, s => {
  if (visible.value && s === 2) {
    schedulePointsEstimate();
  }
});

defineExpose({ open });
</script>

<style>
.qc-plan-step-body {
  min-height: 260px;
  padding: 16px 8px 4px 8px;
}

.qc-plan-hint {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}

.qc-plan-month-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 2px 8px;
}

.qc-plan-unit-tag {
  margin-left: 8px;
  font-size: 13px;
  color: #606266;
}

.qc-plan-hint-block {
  display: block;
  margin-left: 0;
  line-height: 1.6;
}

.qc-plan-points-title {
  margin: 8px 0;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  border-left: 4px solid #409eff;
  padding-left: 8px;
}

.qc-plan-duration-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.qc-plan-calibrator-alert {
  margin-bottom: 12px;
}

.qc-plan-estimate {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 10px 14px;
}

.qc-plan-estimate h5 {
  margin: 0 0 10px 0;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  border-left: 4px solid #409eff;
  padding-left: 8px;
}

.qc-plan-estimate-item {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: #606266;
  padding: 3px 0;
}

.qc-plan-estimate-total {
  border-top: 1px dashed #dcdfe6;
  margin-top: 6px;
  padding-top: 8px;
  font-weight: 600;
  color: #303133;
}
</style>
