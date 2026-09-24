<template>
  <el-dialog
    :title="dialogTitle"
    v-model="visible"
    width="880px"
    append-to-body
    destroy-on-close
    @closed="resetAll"
  >
    <!-- 创建方式（新建首行）：集合模板为主推路径，自定义走单计划五步向导；随时切换保留已填内容 -->
    <div v-if="isCreate" class="qc-plan-mode-picker">
      <div v-for="opt in MODE_OPTIONS" :key="opt.value" class="qc-plan-mode-card"
        :class="{ 'is-active': quickTemplate === opt.value }" @click="quickTemplate = opt.value">
        <div class="qc-plan-mode-card-title">{{ opt.title }}</div>
        <div class="qc-plan-mode-card-desc">{{ opt.desc }}</div>
      </div>
    </div>

    <!-- 集合批量模式：单页一次创建「零点 + 逐气跨度」多行计划（原独立向导合并入此） -->
    <div v-if="batchMode" class="qc-plan-step-body" v-loading="submitting">
      <el-alert type="info" :closable="false" show-icon class="qc-plan-batch-intro"
        :title="batchTemplate === 'WEEKLY'
          ? '周核查集合：预填按周·间隔 1 周·周一·各行同日优先级高'
          : '日常核查集合：预填按天·间隔 1 天·各行同日优先级低'" />

      <el-form label-width="110px">
        <el-row :gutter="16">
          <el-col :span="9">
            <el-form-item label="集合名" required>
              <el-input v-model="collectionName" maxlength="100" show-word-limit placeholder="作为每行计划名前缀" />
            </el-form-item>
          </el-col>
          <el-col :span="15">
            <el-form-item label="核查气种" required>
              <el-checkbox-group v-model="gasSelection">
                <el-checkbox v-for="g in SLOT_ORDER" :key="g.gas" :value="g.gas">{{ g.display }}</el-checkbox>
              </el-checkbox-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="校准策略">
          <el-radio-group v-model="batchPolicy">
            <el-radio value="STANDARD">标准判定（0–5% 合格不校准）</el-radio>
            <el-radio value="CALIBRATE_LOW_DRIFT">低偏差也校准（0–校准限全部自动校准）</el-radio>
          </el-radio-group>
          <div class="qc-plan-hint qc-plan-hint-block">统一预填到本集合每行计划（日常默认标准判定、周核查默认低偏差也校准），创建后可逐行修改</div>
        </el-form-item>
      </el-form>

      <ScheduleForm ref="batchScheduleFormRef" :model="batchSchedule" date-only />

      <h5 class="qc-plan-points-title">模板任务（{{ batchPreviewRows.length }} 行计划）</h5>
      <el-table :data="batchPreviewRows" size="small" border>
        <el-table-column label="计划名称预览" prop="name" min-width="230" show-overflow-tooltip />
        <el-table-column label="类型" width="130" align="center">
          <template #default="{ row }">{{ row.typeLabel }}</template>
        </el-table-column>
        <el-table-column label="气种" width="100" align="center">
          <template #default="{ row }">{{ row.gasLabel }}</template>
        </el-table-column>
        <el-table-column label="时刻" width="140" align="center">
          <template #default="{ row }">
            <el-time-picker v-model="row.row.time" format="HH:mm" value-format="HH:mm" placeholder="时刻" :clearable="false" />
          </template>
        </el-table-column>
        <el-table-column label="同日优先级" width="150" align="center">
          <template #default="{ row }">
            <!-- 作用域插槽变量 row = batchPreviewRows 顶层对象；时刻/优先级嵌套在其 row 槽位行上（slotRows），穿透绑定才能双向同步 -->
            <el-select v-model="row.row.priority">
              <el-option label="不参与" value="NONE" />
              <el-option label="低优先级" value="LOW" />
              <el-option label="高优先级" value="HIGH" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
      <div class="qc-plan-hint qc-plan-hint-block">
        槽位时刻默认错开整点（零点 0:45 起、跨度每小时一格），可修改；同日优先级：低让高（同检查类别且用气重叠时低优先级整日让位留痕）
      </div>

      <el-alert v-if="blocked" type="warning" :closable="false" show-icon class="qc-plan-batch-warn">
        <template #title>间隔预警：以下行预估时长 + 15 分钟缓冲将覆盖下一行开始时刻，未落库</template>
        <div v-for="(w, i) in blockedWarnings" :key="i" class="qc-plan-batch-warn-line">{{ w }}</div>
        <div v-for="(f, i) in blockedEstimateFailures" :key="'f' + i" class="qc-plan-batch-warn-line qc-plan-batch-warn-line--info">{{ f }}</div>
      </el-alert>
    </div>

    <!-- 单计划五步向导 -->
    <template v-else>
      <el-steps :active="step" finish-status="success" simple>
        <el-step title="调度方式" />
        <el-step title="类型与仪器" />
        <el-step title="浓度" />
        <el-step title="流量" />
        <el-step title="时长参数" />
      </el-steps>

      <div class="qc-plan-step-body" v-loading="submitting">
        <!-- 步骤 1：调度方式（统一 ScheduleForm：开始时间兼锚点与触发时刻；FR-01-42 有效期止） -->
        <div v-show="step === 0">
          <ScheduleForm ref="scheduleFormRef" :model="schedule" />
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
            <el-form-item v-if="policyApplicable" label="校准策略">
              <el-radio-group v-model="form.calibrationPolicy">
                <el-radio value="STANDARD">标准判定</el-radio>
                <el-radio value="CALIBRATE_LOW_DRIFT">低偏差也校准</el-radio>
              </el-radio-group>
              <div class="qc-plan-hint qc-plan-hint-block">
                标准判定：0–5% 偏差合格不校准、5–10% 自动校准、超限不通过；低偏差也校准：0–校准限全区间均触发自动校准
              </div>
            </el-form-item>
            <el-form-item label="同日优先级">
              <el-radio-group v-model="form.sameDayPriority">
                <el-radio value="NONE">不参与</el-radio>
                <el-radio value="LOW">低优先级</el-radio>
                <el-radio value="HIGH">高优先级</el-radio>
              </el-radio-group>
              <div class="qc-plan-hint qc-plan-hint-block">
                不参与：不压制别人也不让位（时间错开就都执行）；低优先级：当日只要有同检查类别（同为零点类或同为跨度类）且用气重叠的高优先级行应触发，本行整日不执行并留让位痕；高优先级：本行当日存在时压制同类的低优先级行（不压其他类别）
              </div>
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
                  <el-input-number v-model="durationValues[p.key]" :min="p.min != null ? p.min : 1" :step="1" :precision="0" />
                  <span class="qc-plan-hint">默认 {{ p.defaultValue }}</span>
                </el-form-item>
              </el-form>
            </el-col>
            <el-col :span="10">
              <div class="qc-plan-estimate">
                <h5>执行时长预估</h5>
                <div v-if="estimateLoading" class="qc-plan-hint">估算中…</div>
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
    </template>

    <template #footer>
      <div class="dialog-footer">
        <template v-if="batchMode">
          <el-button v-if="blocked" type="warning" :loading="submitting" @click="submitCollection(true)">强制保存</el-button>
          <el-button type="primary" :loading="submitting" @click="submitCollection(false)">创建 {{ batchPreviewRows.length }} 行计划</el-button>
        </template>
        <template v-else>
          <el-button v-if="step > 0" @click="step--">上一步</el-button>
          <el-button v-if="step < 4" type="primary" @click="nextStep">下一步</el-button>
          <el-button v-if="step === 4" type="primary" :loading="submitting" @click="submitForm">提 交</el-button>
        </template>
        <el-button @click="visible = false">取 消</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, getCurrentInstance, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { addPlan, updatePlan, estimatePlan, createCollection } from '@/api/quality_control/plan';
import ScheduleForm from './components/ScheduleForm.vue';

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

/** 可设校准策略的类型（与 PlanParamValidator.POLICY_APPLICABLE_TYPES 同源）：人工核查与其余类型不适用 */
const POLICY_APPLICABLE_TYPES = ['zero_check', 'span_check', 'multi_zero_check'];

/** 量程百分比默认序列（composer 默认，01 §6 FR-01-33：multi / accuracy） */
const DEFAULT_POINT_PERCENTS = {
  multi_check: [0, 0.1, 0.2, 0.4, 0.6, 0.8],
  accuracy_check: [0.1, 0.2, 0.4, 0.6, 0.8]
};

/**
 * 时长参数默认值常量表（01 §6 FR-01-33；key = durationOverrides 白名单）。
 * applicableTypes 用函数表达「适用类型」列；序列类 2 键由步骤 3 的
 * pointPercents 一等字段承载（composer 语义同源），不在本表重复编辑。
 * min 缺省 1；零气开阀延迟显式 min: 0——composer 默认 0=未配置不等待，0 是合法业务值，
 * 输入框下限必须放行到 0：否则预填 0 被钳成 1 展示，且 1≠默认会被稀疏覆盖误存进 duration_overrides。
 */
const DURATION_PARAM_DEFS = [
  { key: 'commandDelaySeconds', label: '命令延迟', unit: '秒', defaultValue: 2, applicableTypes: null },
  { key: 'stableTimeSeconds', label: '稳定时间', unit: '秒', defaultValue: 1280, applicableTypes: t => t !== 'conversion_check' },
  { key: 'sampleCount', label: '采样次数', unit: '次', defaultValue: 3, applicableTypes: t => t !== 'conversion_check' },
  { key: 'sampleIntervalSeconds', label: '采样间隔', unit: '秒', defaultValue: 30, applicableTypes: t => t !== 'conversion_check' },
  { key: 'calibrationTimeSeconds', label: '校准时间', unit: '秒', defaultValue: 61, applicableTypes: t => t === 'zero_check' || t === 'span_check' || t === 'multi_zero_check' },
  { key: 'verificationStableTimeSeconds', label: '核查稳定时间', unit: '秒', defaultValue: 30, applicableTypes: t => t === 'zero_check' || t === 'span_check' || t === 'multi_zero_check' },
  { key: 'verificationSampleCount', label: '核查采样次数', unit: '次', defaultValue: 3, applicableTypes: t => t === 'zero_check' || t === 'span_check' || t === 'multi_zero_check' },
  { key: 'zeroGasOpenDelaySeconds', label: '零气开阀延迟', unit: '秒', defaultValue: 0, min: 0, applicableTypes: t => t === 'zero_check' || t === 'multi_zero_check' },
  { key: 'recoveryDelaySeconds', label: '恢复延迟', unit: '秒', defaultValue: 60, applicableTypes: null },
  { key: 'precisionRounds', label: '精密度轮数', unit: '轮', defaultValue: 6, applicableTypes: t => t === 'precision_check' },
  { key: 'conversionRounds', label: '转换效率轮数', unit: '轮', defaultValue: 3, applicableTypes: t => t === 'conversion_check' },
  { key: 'gptTimeSeconds', label: 'GPT 时长', unit: '秒', defaultValue: 60, applicableTypes: t => t === 'conversion_check' }
];

/** 表单状态（key 与 PlanSaveDto 一致；调度字段移入 schedule 对象由 ScheduleForm 承载；步间回退保留） */
const form = reactive({
  id: null,
  planName: '',
  qcType: null,
  instruments: [],
  concentrationPpb: null,
  pointPercents: null,
  flowRateLpm: 4.0,
  durationOverrides: null,
  // 校准策略/同日优先级：界面恒有选中值（缺省 STANDARD/NONE），提交时缺省映射 null 落库
  calibrationPolicy: 'STANDARD',
  sameDayPriority: 'NONE'
});

/**
 * 单计划调度模型（与 PlanSaveDto 调度字段同名同型）：planStartTime 日期 = 间隔/周轮锚点，
 * 时间 = 每日触发时刻（提交时拆为 hour/minute），ONCE 时整体即触发时刻（提交为 onceAt）。
 */
const schedule = reactive({
  scheduleType: 'INTERVAL',
  intervalDays: 2,
  intervalWeeks: 1,
  weekdays: [],
  monthDays: [],
  planStartTime: null,
  planEndTime: null
});

/** 集合批量模式的调度模型（date-only：planStartTime 只取日期，逐行时刻由模板任务表提供） */
const batchSchedule = reactive({
  scheduleType: 'INTERVAL',
  intervalDays: 1,
  intervalWeeks: 1,
  weekdays: [1],
  monthDays: [],
  planStartTime: null,
  planEndTime: null
});

const scheduleFormRef = ref(null);
const batchScheduleFormRef = ref(null);

/** 快捷模板选择：CUSTOM = 单计划向导；DAILY/WEEKLY = 集合批量模式（仅新建可选，编辑恒自定义） */
const quickTemplate = ref('CUSTOM');
const batchMode = ref(false);
const batchTemplate = ref('DAILY');
const isCreate = computed(() => form.id == null);

/** 创建方式卡片（新建首行）：集合模板为主推路径，自定义走单计划五步向导 */
const MODE_OPTIONS = [
  { value: 'DAILY', title: '日常核查集合', desc: '一次创建「同时零点 + 逐气跨度」全套，每 N 天执行' },
  { value: 'WEEKLY', title: '周核查集合', desc: '一次创建「同时零点 + 逐气跨度」全套，按周执行' },
  { value: 'CUSTOM', title: '自定义', desc: '单计划五步向导：类型、调度、浓度自由配置' }
];

const dialogTitle = computed(() => (form.id != null ? '编辑质控计划' : '新建质控计划'));

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

/**
 * 槽位表（与服务端工厂同源约定）：canonical 槽位序 O3/CO/NOx/SO2，
 * 零点 0:45 + 跨度每小时一格；勾选子集只裁行不移位。展示名 NO2 ↔ NOx。
 */
const SLOT_ORDER = [
  { gas: 'O3', display: 'O3', slot: '01:45' },
  { gas: 'CO', display: 'CO', slot: '02:45' },
  { gas: 'NO2', display: 'NOx', slot: '03:45' },
  { gas: 'SO2', display: 'SO2', slot: '04:45' }
];
const ZERO_SLOT = '00:45';

const collectionName = ref('');
const gasSelection = ref([]);
/** 集合校准策略单选：界面恒有值；STANDARD 提交 null（与工厂 NULL=STANDARD 落库语义一致） */
const batchPolicy = ref('STANDARD');
/** 逐槽行状态（时刻 + 同日优先级）：取消勾选不丢已改值，重勾即回显 */
const slotRows = reactive({});
/** 间隔预警拦截态：created=false 时置位，展示预警并放开「强制保存」 */
const blocked = ref(false);
const blockedWarnings = ref([]);
const blockedEstimateFailures = ref([]);

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
  // 策略随类型重置回缺省（类型切换即适用域变化，不跨类型携带旧选择）
  form.calibrationPolicy = 'STANDARD';
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

/** 调度步校验委托共享组件（开始时间必填、按模式必填集合、有效期止晚于开始） */
function validateScheduleStep() {
  return scheduleFormRef.value != null && scheduleFormRef.value.validate();
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
  // multi_zero_check 已放开多仪器预估（1~4 台）；其余类型保持单仪器语义
  const instrumentCountOk = multiInstrumentTypes.includes(form.qcType)
    ? form.instruments.length >= 1
    : form.instruments.length === 1;
  if (!form.qcType || !instrumentCountOk) {
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
  const st = schedule.scheduleType;
  const dto = {
    id: form.id,
    planName: form.planName.trim(),
    scheduleType: st,
    qcType: form.qcType,
    instruments: [...form.instruments],
    flowRateLpm: form.flowRateLpm,
    durationOverrides: collectDurationOverrides(),
    planStartTime: schedule.planStartTime ? ensureRfc3339(schedule.planStartTime) : null,
    planEndTime: schedule.planEndTime ? ensureRfc3339(schedule.planEndTime) : null
  };
  if (st === 'ONCE') {
    dto.hour = 0;
    dto.minute = 0;
    // 统一调度模型：一次性无独立触发时刻 picker，开始时间即触发时刻（指定时刻模式）
    dto.onceMode = 'SCHEDULED';
    dto.onceAt = ensureRfc3339(schedule.planStartTime);
  } else {
    // 触发时刻从 planStartTime 时间部分提取（偏移后缀在尾，时分位次不受影响）
    const time = String(schedule.planStartTime);
    dto.hour = Number(time.slice(11, 13));
    dto.minute = Number(time.slice(14, 16));
    if (st === 'WEEKLY') {
      dto.intervalWeeks = schedule.intervalWeeks;
      dto.weekdays = [...schedule.weekdays].sort((a, b) => a - b);
    }
    if (st === 'MONTHLY') {
      dto.monthDays = [...schedule.monthDays].sort((a, b) => a - b);
    }
    if (st === 'INTERVAL') {
      dto.intervalDays = schedule.intervalDays;
    }
  }
  if (CONCENTRATION_REQUIRED_TYPES.includes(form.qcType)) {
    dto.concentrationPpb = form.concentrationPpb;
  }
  if (pointPercentTypes.includes(form.qcType)) {
    dto.pointPercents = parsePointPercentsText(pointPercentsText.value).map(p => Math.round(p * 1000) / 1000);
  }
  // 落库缺省口径：STANDARD/NONE 存 NULL（NULL=STANDARD / NULL=NONE 列语义）；
  // 策略仅适用类型携带（校验器对不适用类型拒绝非空值），优先级为类型无关通用字段恒携带
  if (POLICY_APPLICABLE_TYPES.includes(form.qcType)) {
    dto.calibrationPolicy = form.calibrationPolicy === 'STANDARD' ? null : form.calibrationPolicy;
  }
  dto.sameDayPriority = form.sameDayPriority === 'NONE' ? null : form.sameDayPriority;
  return dto;
}

/**
 * 集合批量提交：created=false = 间隔预警拦截（零落库），确认后携 force 重提。
 * 调度字段与单计划同名同型（date-only：planStartTime 仅日期，时刻由 rows 逐行承载）。
 */
function submitCollection(force) {
  if (!batchScheduleFormRef.value.validate()) {
    return;
  }
  if (!collectionName.value || !collectionName.value.trim()) {
    ElMessage.warning('请填写集合名');
    return;
  }
  if (canonicalGases.value.length === 0) {
    ElMessage.warning('请至少勾选一种气');
    return;
  }
  const dto = {
    template: batchTemplate.value,
    collectionName: collectionName.value.trim(),
    instruments: [...canonicalGases.value],
    calibrationPolicy: batchPolicy.value === 'STANDARD' ? null : batchPolicy.value,
    scheduleType: batchSchedule.scheduleType,
    planStartTime: batchSchedule.planStartTime ? ensureRfc3339(batchSchedule.planStartTime) : null,
    planEndTime: batchSchedule.planEndTime ? ensureRfc3339(batchSchedule.planEndTime) : null,
    rows: batchPreviewRows.value.map(p => {
      const parts = p.row.time.split(':');
      return {
        rowKey: p.key,
        hour: Number(parts[0]),
        minute: Number(parts[1]),
        sameDayPriority: p.row.priority
      };
    })
  };
  if (batchSchedule.scheduleType === 'INTERVAL') {
    dto.intervalDays = batchSchedule.intervalDays;
  }
  if (batchSchedule.scheduleType === 'WEEKLY') {
    dto.intervalWeeks = batchSchedule.intervalWeeks;
    dto.weekdays = [...batchSchedule.weekdays].sort((a, b) => a - b);
  }
  if (batchSchedule.scheduleType === 'MONTHLY') {
    dto.monthDays = [...batchSchedule.monthDays].sort((a, b) => a - b);
  }
  if (force) {
    dto.force = true;
  }
  submitting.value = true;
  createCollection(dto).then(response => {
    submitting.value = false;
    if (response.code !== 200) {
      ElMessage.error(response.msg || '创建失败');
      return;
    }
    const result = response.data || {};
    if (result.created === false) {
      blocked.value = true;
      blockedWarnings.value = result.warnings || [];
      blockedEstimateFailures.value = result.estimateFailures || [];
      return;
    }
    const created = (result.plans || []).length;
    proxy.$modal.msgSuccess('已创建 ' + created + ' 行计划');
    if ((result.warnings || []).length > 0) {
      ElMessage.warning('间隔预警（已强制保存）：' + result.warnings.join('；'));
    }
    if ((result.estimateFailures || []).length > 0) {
      ElMessage.info('部分行时长预估不可用：' + result.estimateFailures.join('；'));
    }
    visible.value = false;
    emit('success');
  }).catch(() => {
    submitting.value = false;
  });
}

/** canonical 槽位序过滤勾选集：勾选点击序不影响行序与 instruments 序（服务端同规整） */
const canonicalGases = computed(() => SLOT_ORDER.filter(s => gasSelection.value.includes(s.gas)).map(s => s.gas));

/** 零点行气种段：四气全勾=「四气」，部分勾选=槽位序展示名「、」拼接（与服务端命名同源） */
const zeroGasLabel = computed(() => {
  if (canonicalGases.value.length === SLOT_ORDER.length) {
    return '四气';
  }
  return SLOT_ORDER.filter(s => gasSelection.value.includes(s.gas)).map(s => s.display).join('、');
});

const batchPreviewRows = computed(() => {
  const rows = [];
  if (canonicalGases.value.length > 0) {
    rows.push({
      key: 'zero',
      name: collectionName.value + '-' + zeroGasLabel.value + '-零点',
      typeLabel: '多仪器零点质控',
      gasLabel: zeroGasLabel.value,
      row: slotRows.zero
    });
  }
  for (const s of SLOT_ORDER) {
    if (gasSelection.value.includes(s.gas)) {
      rows.push({
        key: s.gas,
        name: collectionName.value + '-' + s.display + '-跨度',
        typeLabel: '跨度检查',
        gasLabel: s.display,
        row: slotRows[s.gas]
      });
    }
  }
  return rows;
});

/**
 * 进入集合批量模式并按模板预填（预填不是限制，全部可改）：
 * 日常 = 按天·间隔 1·低优先级·标准判定；周 = 按周·间隔 1·周一·高优先级·低偏差也校准
 * （周频次低、单次执行收益高，默认把小偏差也校掉——2026-09-23 用户裁决）。
 */
function enterBatch(tpl) {
  batchTemplate.value = tpl;
  batchMode.value = true;
  collectionName.value = tpl === 'WEEKLY' ? '周核查' : '日常核查';
  if (tpl === 'WEEKLY') {
    batchSchedule.scheduleType = 'WEEKLY';
    batchSchedule.intervalWeeks = 1;
    batchSchedule.weekdays = [1];
  } else {
    batchSchedule.scheduleType = 'INTERVAL';
    batchSchedule.intervalDays = 1;
  }
  batchSchedule.monthDays = [];
  batchSchedule.planStartTime = todayIsoDate();
  batchSchedule.planEndTime = null;
  gasSelection.value = SLOT_ORDER.map(s => s.gas);
  batchPolicy.value = tpl === 'WEEKLY' ? 'CALIBRATE_LOW_DRIFT' : 'STANDARD';
  const defaultPriority = tpl === 'WEEKLY' ? 'HIGH' : 'LOW';
  slotRows.zero = { time: ZERO_SLOT, priority: defaultPriority };
  for (const s of SLOT_ORDER) {
    slotRows[s.gas] = { time: s.slot, priority: defaultPriority };
  }
  clearBlocked();
}

function clearBlocked() {
  blocked.value = false;
  blockedWarnings.value = [];
  blockedEstimateFailures.value = [];
}

/** 快捷模板切换即模式切换：向导/批量两套状态独立保留，互切不丢已填内容 */
watch(quickTemplate, v => {
  if (v === 'CUSTOM') {
    batchMode.value = false;
    return;
  }
  enterBatch(v);
});

/**
 * 打开弹窗：plan 为 null = 新建（创建方式默认「日常核查集合」，第一行卡片可切换）；
 * 否则用详情行回填（编辑恒为自定义单计划，不显示创建方式卡片）。
 */
function open(plan) {
  visible.value = true;
  step.value = 0;
  estimateResult.value = null;
  estimateError.value = '';
  estimatePoints.value = null;
  clearBlocked();
  if (plan == null) {
    initCreateState();
    quickTemplate.value = 'DAILY';
    return;
  }
  quickTemplate.value = 'CUSTOM';
  form.id = plan.id;
  form.planName = plan.planName || '';
  form.qcType = plan.qcType;
  const instruments = parseJson(plan.instruments, []);
  form.instruments = Array.isArray(instruments) ? [...instruments] : [];
  instrumentSelection.value = [...form.instruments];
  form.concentrationPpb = plan.concentrationPpb != null ? Number(plan.concentrationPpb) : null;
  const percents = parseJson(plan.pointPercents, null);
  form.pointPercents = Array.isArray(percents) ? [...percents] : null;
  pointPercentsText.value = pointPercentsToText(form.pointPercents);
  form.flowRateLpm = plan.flowRateLpm != null ? Number(plan.flowRateLpm) : 4.0;
  // 编辑回填（整行覆盖语义：不带全两列会在保存时被清空）；NULL 列回显为缺省选项
  form.calibrationPolicy = plan.calibrationPolicy || 'STANDARD';
  form.sameDayPriority = plan.sameDayPriority || 'NONE';
  initDurationValues(parseJson(plan.durationOverrides, null) || null);
  backfillSchedule(plan);
}

/** 新建初始态（调度模型重置 + 开始时间预填当前时刻，必填） */
function initCreateState() {
  form.id = null;
  form.planName = '';
  form.qcType = null;
  form.instruments = [];
  instrumentSelection.value = [];
  form.concentrationPpb = null;
  form.pointPercents = null;
  pointPercentsText.value = '';
  form.flowRateLpm = 4.0;
  form.calibrationPolicy = 'STANDARD';
  form.sameDayPriority = 'NONE';
  initDurationValues(null);
  schedule.scheduleType = 'INTERVAL';
  schedule.intervalDays = 2;
  schedule.intervalWeeks = 1;
  schedule.weekdays = [];
  schedule.monthDays = [];
  schedule.planStartTime = nowIsoRfc3339();
  schedule.planEndTime = null;
}

/**
 * 编辑回填调度模型（统一调度语义）：
 * - 存量 DAILY（每天）= 按天·间隔 1 天；
 * - 存量 WEEKLY 无 intervalWeeks 键 = 1（与后端存量读取同规则）；
 * - 开始时间日期取 planStartTime 列（RFC 3339 偏移串日期段=墙钟日）、缺时回退存量
 *   INTERVAL 锚点 anchorDate（UTC 串，按浏览器时区取墙钟日——部署时区与浏览器同区约定）；
 *   时间取调度时刻（新模型与 planStartTime 时间同值，存量行 config 时刻为真相源）；
 * - ONCE 回退序：onceAt（存储侧转换的 UTC 串）→ planStartTime 列 → 当前时刻。
 */
function backfillSchedule(plan) {
  const config = parseJson(plan.scheduleConfig, {});
  const hour = config.hour != null ? Number(config.hour) : 2;
  const minute = config.minute != null ? Number(config.minute) : 0;
  const legacyType = plan.scheduleType || 'INTERVAL';
  schedule.scheduleType = legacyType === 'DAILY' ? 'INTERVAL' : legacyType;
  schedule.intervalDays = config.intervalDays != null
    ? Number(config.intervalDays)
    : (legacyType === 'DAILY' ? 1 : 2);
  schedule.intervalWeeks = config.intervalWeeks != null ? Number(config.intervalWeeks) : 1;
  schedule.weekdays = Array.isArray(config.weekdays) ? [...config.weekdays] : [];
  schedule.monthDays = Array.isArray(config.monthDays) ? [...config.monthDays] : [];
  const trigger = pad2(hour) + ':' + pad2(minute);
  if (schedule.scheduleType === 'ONCE') {
    schedule.planStartTime = config.onceAt || ensureRfc3339(String(plan.planStartTime || '')) || nowIsoRfc3339();
  } else {
    let startDate = String(plan.planStartTime || '').slice(0, 10);
    if (!startDate && config.anchorDate) {
      startDate = localDateOf(String(config.anchorDate));
    }
    // 组合即带本地偏移（RFC 3339 完整形态）：picker 按 value-format 严格解析，无偏移串会解析失败显示空；
    // 无锚点日期（存量行 planStartTime 列与 config.anchorDate 均缺）仅日期回退今日，时刻恒取调度时刻
    // （config 真相源）——回退当前时刻会在「编辑直接保存」时把触发时刻静默改写成打开时刻
    schedule.planStartTime = (startDate || todayIsoDate().slice(0, 10)) + 'T' + trigger + ':00' + localOffsetSuffix();
  }
  schedule.planEndTime = plan.planEndTime ? ensureRfc3339(String(plan.planEndTime)) : null;
}

/**
 * 当前时刻（RFC 3339 本地偏移形态）：串的日期时间段即本地墙钟——预览/触发时刻提取
 * 均读串字面段，用 UTC Z 串会在本地 0-8 点时差一天（后端按绝对时刻算不受影响，纯展示缺陷）。
 */
function nowIsoRfc3339() {
  const d = new Date();
  return pad2(d.getFullYear()) + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
    + 'T' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds())
    + localOffsetSuffix();
}

/** 调度起算日（dateOnly 集合用）：当日本地零点的本地偏移串——串日期段=当日墙钟日 */
function todayIsoDate() {
  const d = new Date();
  return pad2(d.getFullYear()) + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate())
    + 'T00:00:00' + localOffsetSuffix();
}

/** UTC instant 串 → 本地墙钟日期（YYYY-MM-DD）：存量锚点串回填取墙钟日（同区约定下不漂日） */
function localDateOf(iso) {
  const d = new Date(iso);
  return isNaN(d.getTime())
    ? ''
    : pad2(d.getFullYear()) + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
}

/** 本地时区偏移后缀（RFC 3339 ±HH:mm） */
function localOffsetSuffix() {
  const off = -new Date().getTimezoneOffset();
  const sign = off >= 0 ? '+' : '-';
  const abs = Math.abs(off);
  return sign + pad2(Math.floor(abs / 60)) + ':' + pad2(abs % 60);
}

/**
 * 提交出口统一防线：时刻串缺时区后缀（回填组合串/旧值）即补本地偏移，使其成为
 * RFC 3339 带偏移绝对时刻；已带（Z / ±HH:mm）原样通过。
 * 无冒号偏移（±HHmm，ISO 8601 基本格式如 +0800）非 RFC 3339 numoffset，规范化补冒号。
 */
function ensureRfc3339(iso) {
  const normalized = String(iso || '').replace(/([+-]\d{2})(\d{2})$/, '$1:$2');
  return /[Zz]$|[+-]\d{2}:\d{2}$/.test(normalized) ? normalized : normalized + localOffsetSuffix();
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
  batchMode.value = false;
  quickTemplate.value = 'CUSTOM';
  clearBlocked();
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

/* 创建方式卡片（新建首行）：三卡横排等宽，选中卡描边+浅底高亮 */
.qc-plan-mode-picker {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.qc-plan-mode-card {
  flex: 1;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  padding: 10px 14px;
  cursor: pointer;
  transition: border-color 0.2s, background-color 0.2s;
}

.qc-plan-mode-card:hover {
  border-color: #409eff;
}

.qc-plan-mode-card.is-active {
  border-color: #409eff;
  background-color: #ecf5ff;
}

.qc-plan-mode-card-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.qc-plan-mode-card.is-active .qc-plan-mode-card-title {
  color: #409eff;
}

.qc-plan-mode-card-desc {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: #909399;
}

.qc-plan-hint {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
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

.qc-plan-batch-intro {
  margin-bottom: 12px;
}

.qc-plan-batch-warn {
  margin-top: 12px;
}

.qc-plan-batch-warn-line {
  font-size: 12px;
  line-height: 1.7;
  color: #b88230;
}

.qc-plan-batch-warn-line--info {
  color: #909399;
}
</style>
