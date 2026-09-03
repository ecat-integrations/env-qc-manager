<template>
  <div class="qc-process-panel">
    <!-- 首屏解析中 / 请求失败 -->
    <div v-if="loading" v-loading="true" class="qc-process-loading"></div>
    <el-alert v-else-if="loadError" type="error" :closable="false" :title="loadError" />

    <!-- 分析仪解析失败（含存量 instrument_name=null 的旧记录）：显式提示，不静默空白 -->
    <el-empty v-else-if="!analyzer" class="qc-process-empty" :image-size="64"
              :description="analyzerReason || '该记录未冻结分析仪识别信息，无法回放过程数据'" />

    <template v-else>
      <!-- 头部：分析仪标识（「在 ADM 中打开」外链已按需求移除——回放数据本面板自足展示） -->
      <div class="qc-process-head">
        <div class="qc-process-title">
          <span>分析仪：{{ analyzerDisplayText }}</span>
          <small v-if="analyzer.sn || row.instrumentNo">{{ analyzer.sn || row.instrumentNo }}</small>
        </div>
      </div>

      <!-- ===== 实时态（执行中：0 等待 / 1 执行中）===== -->
      <template v-if="mode === 'live'">
        <!-- 参数目录不可用（后端 attrsReason）：如实提示，两段表都不渲染 -->
        <el-alert v-if="!attrRows.length && attrsReason" type="warning" :closable="false"
                  class="qc-process-catalog-alert" :title="attrsReason" />

        <div v-if="monitorAttrs.length" class="qc-process-section">
          <h5>污染参数</h5>
          <el-table :data="monitorAttrs" size="small" border stripe max-height="220">
            <el-table-column label="参数" min-width="150" show-overflow-tooltip>
              <template #default="scope">
                <span :class="{ 'qc-process-main-attr': scope.row.isMain }">{{ scope.row.name }}</span>
                <el-tag v-if="scope.row.isMain" size="small" effect="plain" class="qc-process-main-tag">主浓度</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最新值" min-width="130" align="right">
              <template #default="scope">
                <span :class="{ 'qc-process-main-attr': scope.row.isMain }">{{ formatAttrValue(scope.row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" min-width="110" align="center">
              <template #default="scope">
                <span v-if="!scope.row.statuses || !scope.row.statuses.length" class="qc-status-muted">—</span>
                <span v-else-if="isNormalStatuses(scope.row.statuses)" class="qc-status-ok">数据有效</span>
                <template v-else>
                  <el-tag v-for="s in nonNormalStatuses(scope.row.statuses)" :key="s" size="small"
                          :type="liveStatusTagType(s)" class="qc-process-status-tag">{{ liveStatusLabel(s) }}</el-tag>
                </template>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" min-width="150" align="center">
              <template #default="scope">{{ formatMillis(scope.row.updateTime) }}</template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="statusAttrs.length" class="qc-process-section">
          <h5>状态参数</h5>
          <el-table :data="statusAttrs" size="small" border stripe max-height="200">
            <el-table-column label="参数" min-width="150" show-overflow-tooltip>
              <template #default="scope">{{ scope.row.name }}</template>
            </el-table-column>
            <el-table-column label="最新值" min-width="130" align="right">
              <template #default="scope">{{ formatAttrValue(scope.row) }}</template>
            </el-table-column>
            <el-table-column label="状态" min-width="110" align="center">
              <template #default="scope">
                <span v-if="!scope.row.statuses || !scope.row.statuses.length" class="qc-status-muted">—</span>
                <span v-else-if="isNormalStatuses(scope.row.statuses)" class="qc-status-ok">数据有效</span>
                <template v-else>
                  <el-tag v-for="s in nonNormalStatuses(scope.row.statuses)" :key="s" size="small"
                          :type="liveStatusTagType(s)" class="qc-process-status-tag">{{ liveStatusLabel(s) }}</el-tag>
                </template>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" min-width="150" align="center">
              <template #default="scope">{{ formatMillis(scope.row.updateTime) }}</template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="seriesDefs.length" class="qc-process-section">
          <h5>浓度实时曲线（{{ curveUnit }}）</h5>
          <div class="qc-process-chart-box">
            <div ref="chartRef" class="qc-process-chart"></div>
            <div v-if="!hasCurveData" class="qc-process-chart-hint">等待分析仪首个采样点…</div>
          </div>
          <div class="qc-process-note">
            曲线自任务开始前推 5 分钟起展示：打开面板前历史段由后端缓存回补，之后按属性更新时间实时追加；
            点击图例可显隐通道；目标线：{{ markLineText }}
          </div>
        </div>
      </template>

      <!-- ===== 回放态（终态 2 成功/3 失败/4 手动中止）：ADM 历史数据回放，表格/曲线双视图 ===== -->
      <template v-else-if="mode === 'replay'">
        <div class="qc-replay-toolbar">
          <span class="qc-process-note qc-replay-toolbar-note">
            质控已结束，以下为历史数据回放（分钟粒度，时间窗＝任务起止前后各 15 分钟）：
            浓度参数与校准标准值单位相同，状态参数为原生单位；污染参数排在最前。
          </span>
          <el-radio-group v-model="replayView" size="small">
            <el-radio-button value="table">表格</el-radio-button>
            <el-radio-button value="chart">曲线</el-radio-button>
          </el-radio-group>
        </div>
        <el-alert v-if="replayFallbackNote" type="info" :closable="false"
                  class="qc-process-replay-alert" :title="replayFallbackNote" />
        <el-alert v-if="replayError" type="error" :closable="false"
                  class="qc-process-replay-alert" :title="replayError" />
        <template v-else>
          <!-- 表格视图（默认）：横表——时间列 + 参数列；污染参数列在前、状态参数列在后（列序见 orderReplayResult） -->
          <el-table v-show="replayView === 'table'" v-loading="replayLoading" :data="replayRows" size="small" border stripe
                    class="qc-process-replay-table" max-height="480" empty-text="窗口内无历史数据">
            <el-table-column label="时间" min-width="150" align="center" fixed>
              <template #default="scope">{{ formatBucket(scope.row.bucket) }}</template>
            </el-table-column>
            <el-table-column v-for="(col, ci) in replayColumns" :key="replayColKey(col, ci)"
                             min-width="130" align="right">
              <template #header>
                <div class="qc-replay-col-label">{{ col.label }}</div>
                <div v-if="col.unit" class="qc-replay-col-unit">({{ col.unit }})</div>
              </template>
              <template #default="scope">
                <div class="qc-replay-cell">
                  <span class="qc-replay-val">{{ cellValueText((scope.row.cells || [])[ci]) }}</span>
                  <el-tag v-for="s in cellAbnormalTags((scope.row.cells || [])[ci])" :key="s" size="small"
                          :type="replayTagType(s)" class="qc-process-status-tag">{{ s }}</el-tag>
                </div>
              </template>
            </el-table-column>
          </el-table>
          <!-- 曲线视图：每参数一张小图。与表格共用同一份查询结果（切视图不重查）；
               v-if 挂载保证 echarts 首次 init 时元素可见、尺寸真实，切回表格卸载即全量 dispose -->
          <div v-if="replayView === 'chart'" v-loading="replayLoading" class="qc-replay-chart-area">
            <QcReplayChartGrid v-if="replayChartSeries.length" :series="replayChartSeries" />
            <div v-else-if="!replayLoading" class="qc-replay-chart-empty">窗口内无历史数据</div>
          </div>
        </template>
      </template>
    </template>
  </div>
</template>

<script setup>
defineOptions({ name: 'QcRecordProcessPanel' });
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import * as echarts from 'echarts';
import { getAdmHistory, getLiveProcess } from '@/api/quality_control/records';
import QcReplayChartGrid from './QcReplayChartGrid.vue';

/**
 * 质控记录详情「过程展示」tab：
 * - 执行中（executionStatus 0 等待/1 执行中）→ 实时态：分析仪全属性按 kind 分「污染参数/状态参数」
 *   两段表（3s 轮询 live_process 全量刷新）+ 多参数浓度曲线（echarts 多序列：
 *   series 数组每通道一条、主通道加粗高亮挂目标浓度 markLine、历史段由后端缓存回补后增量追加）。
 * - 终态（2 成功/3 失败/4 手动中止——4 已不会再产生新数据，按回放处理）→ 回放态：
 *   直调 ADM REST /adm-monitor/history（unit=custom：浓度参数按 HISTORY 展示单位偏好换算为体积单位
 *   ppb/ppm，与校准标准值单位同口径；状态参数无偏好行时后端保底原生单位），时间窗＝任务起止 ±15 分钟，
 *   粒度 minute。表格/曲线双视图共用同一份查询结果——表格为横表、曲线为每参数一张小图（QcReplayChartGrid），
 *   污染参数（MONITOR）列序/图序均在最前（orderReplayResult）。
 * - 分析仪解析失败 → 显式提示后端 reason，不静默降级。
 *
 * 生命周期：active=false（tab 切走）暂停轮询；row.id 变化（换记录/弹窗重开）重置全部状态；
 * 组件卸载清理定时器并 dispose echarts 实例。
 */
const props = defineProps({
  /** 当前质控记录行（弹窗内响应式对象，父级轮询会就地刷新 executionStatus） */
  row: { type: Object, required: true },
  /** 所在 tab 是否激活（弹窗开着且停在「过程展示」页） */
  active: { type: Boolean, default: false }
});

const POLL_INTERVAL_MS = 3000;
/** 曲线横轴起点=任务开始前推 5 分钟（需求：默认开始时间=任务前推 5min） */
const CURVE_LEAD_MS = 5 * 60 * 1000;
/** 回放时间窗=任务起止前后各 15 分钟 */
const REPLAY_PAD_MS = 15 * 60 * 1000;
/** 记录时间串是 @JsonFormat GMT+8 序列化的上海墙钟；兜底「现在」须换到同一墙钟域（+8h） */
const SHANGHAI_OFFSET_MS = 8 * 60 * 60 * 1000;

/** 执行中 analyzer.name 未冻结（物理设备名完成时才冻结）时的本地中文名兜底；映射缺失回 uniqueId 原文，不猜 */
const ANALYZER_NAME_BY_UID = {
  'logicdevice.so2': 'SO₂分析仪',
  'logicdevice.nox': 'NOx分析仪',
  'logicdevice.o3': 'O₃分析仪',
  'logicdevice.co': 'CO分析仪'
};

/**
 * live_process attrs 的状态枚举名 → 中文（镜像后端 AttributeStatus.getDescription 与 ADM 同一词汇）；
 * 未登记枚举按原文展示，不臆造释义。
 */
const ATTR_STATUS_LABELS = {
  NORMAL: '数据有效',
  INSUFFICIENT: '有效数据不足',
  WAITING: '等待数据恢复',
  ALARM: '传感器报警',
  MALFUNCTION: '运行不良',
  ABNORMAL_CHANGE: '数据突变',
  NO_CHANGE: '数据不变',
  OVER_UPPER_LIMIT: '超上限',
  UNDER_LOWER_LIMIT: '超下限',
  MAINTENANCE: '维护',
  CALIBRATION: '校准（质控）',
  ZERO_CHECK: '零点检查',
  SPAN_CHECK: '跨度检查',
  ACCURACY_CHECK: '准确度检查',
  ZERO_CALIBRATION: '零点校准',
  SPAN_CALIBRATION: '跨度校准',
  FLOW_CHECK: '流量检查',
  QUALITY_CHECK: '质量检查',
  TEMP_PRESSURE_CALIBRATION: '温度压力校准',
  MULTI_POINT_SPAN: '检定多点跨度',
  ZERO_DRIFT: '检定零点漂移',
  SPAN_DRIFT: '检定跨度漂移',
  SPAN_REPRODUCIBILITY: '检定跨度重现性',
  PRECISION_CHECK: '精密度检查',
  CONVERSION_CHECK: '转换效率检查',
  DEVICE_REPLACEMENT: '维修更换设备',
  OFFLINE: '离线',
  EMPTY: '未设置'
};

/** 状态 tag 配色分组（镜像 ADM status-mark 分组：质控/维护类琥珀、不足/离线灰、其余异常红） */
const STATUS_TAG_WARN = new Set([
  'MAINTENANCE', 'CALIBRATION', 'ZERO_CHECK', 'SPAN_CHECK', 'ACCURACY_CHECK',
  'ZERO_CALIBRATION', 'SPAN_CALIBRATION', 'FLOW_CHECK', 'QUALITY_CHECK',
  'TEMP_PRESSURE_CALIBRATION', 'MULTI_POINT_SPAN', 'ZERO_DRIFT', 'SPAN_DRIFT',
  'SPAN_REPRODUCIBILITY', 'PRECISION_CHECK', 'CONVERSION_CHECK', 'DEVICE_REPLACEMENT'
]);
const STATUS_TAG_INFO = new Set(['INSUFFICIENT', 'WAITING', 'OFFLINE', 'EMPTY']);

/**
 * record.parameter（ParameterEnum 数字码或符号两态）→ composer 采集键
 * （后端 LogicDeviceBindingIds.composerGasKeyFromParameterName 同一映射；NO₂质控读 NO 通道）。
 * 仅在 live_process attrs 目录拿不到时兜底：给回放 REST 拼主浓度参数。
 */
function composerKeyOfParameter(parameter) {
  if (parameter === null || parameter === undefined || parameter === '') {
    return '';
  }
  const s = String(parameter).trim().toUpperCase();
  const byCode = { '1': 'so2', '2': 'no', '3': 'o3', '4': 'co' };
  const byName = { SO2: 'so2', NO2: 'no', O3: 'o3', CO: 'co' };
  return byCode[s] || byName[s] || '';
}

const loading = ref(false);
const loadError = ref('');
const payload = ref(null);
const hasCurveData = ref(false);
const chartRef = ref(null);
let chartInstance = null;
let pollTimer = null;

// 回放态（ADM 历史数据：表格/曲线双视图共用一份查询结果）
const replayView = ref('table');
const replayLoading = ref(false);
const replayError = ref('');
const replayFallbackNote = ref('');
const replayColumns = ref([]);
const replayRows = ref([]);
let replayLoadedKey = '';

/** 曲线点仓：attrId → Map<时刻, 值>。非响应式（echarts 手动重绘驱动）；同刻覆盖实现去重/就地点更新 */
const curvePoints = new Map();

const analyzer = computed(() => payload.value?.analyzer || null);
const analyzerReason = computed(() => payload.value?.analyzerReason || '');
const attrsReason = computed(() => payload.value?.attrsReason || '');
const attrRows = computed(() => Array.isArray(payload.value?.attrs) ? payload.value.attrs : []);
const monitorAttrs = computed(() => attrRows.value.filter(a => a && a.kind === 'MONITOR'));
const statusAttrs = computed(() => attrRows.value.filter(a => a && a.kind !== 'MONITOR'));
const seriesDefs = computed(() => Array.isArray(payload.value?.series) ? payload.value.series : []);
const curveUnit = computed(() => payload.value?.curveUnit || '');
const markLine = computed(() => payload.value?.markLine || null);

/**
 * 执行状态以 live_process 轮询结果为准（比 row 更新鲜：任务完成瞬间本面板先于父级轮询感知，
 * 才能及时切回放视图）；载荷缺失时回落行级 executionStatus。
 */
const executionStatus = computed(() => {
  const p = payload.value?.executionStatus;
  if (p !== null && p !== undefined && p !== '') {
    return String(p);
  }
  return String(props.row?.executionStatus ?? '');
});

/** 0 等待中 / 1 执行中算进行中；4 手动中止虽 dict 称「中止中」，但已无新数据，归终态回放 */
const isRunning = computed(() => ['0', '1'].includes(executionStatus.value));

const mode = computed(() => {
  if (!analyzer.value) {
    return 'unresolved';
  }
  return isRunning.value ? 'live' : 'replay';
});

const analyzerDisplayText = computed(() => {
  const a = analyzer.value;
  if (!a) {
    return '—';
  }
  // 优先物理设备名；执行中未冻结时按 uniqueId 本地映射中文名；映射缺失回 uniqueId 原文（不猜）
  return a.name || props.row?.instrumentName || ANALYZER_NAME_BY_UID[a.uniqueId] || a.uniqueId || '—';
});

const markLineText = computed(() => {
  if (markLine.value && typeof markLine.value.value === 'number') {
    return `${markLine.value.label} ${markLine.value.value} ${markLine.value.unit}`;
  }
  return payload.value?.markLineNote || '不可用';
});

// ---------- 时间口径（两个域，勿混用） ----------

/**
 * 记录时间串（@JsonFormat GMT+8 壁钟 "yyyy-MM-dd HH:mm:ss"）→ 真实 epoch 毫秒。
 * 显式 +08:00 解析不依赖浏览器时区；供 echarts time 轴（点的 t 是真实 epoch）使用。解析失败返回 NaN。
 */
function recordTimeMillis(value) {
  if (!value) {
    return NaN;
  }
  const s = String(value).trim().replace(' ', 'T');
  if (!/^\d{4}-\d{2}-\d{2}T/.test(s)) {
    return NaN;
  }
  return Date.parse(s.length === 19 ? s + '+08:00' : s);
}

/**
 * 记录时间串 → 「墙钟域」毫秒：把上海墙钟字段视作 UTC（'Z' 技巧），仅用于墙钟加减与格式化回串，
 * 全程不做时区换算——ADM history 的 start/end 契约是上海墙钟 ISO 秒串（无 Z），与浏览器时区无关。
 */
function wallClockMs(value) {
  if (!value) {
    return NaN;
  }
  const s = String(value).trim().replace(' ', 'T');
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s)) {
    return NaN;
  }
  return Date.parse((s.length === 16 ? s + ':00' : s) + 'Z');
}

/** 墙钟域毫秒 → 'YYYY-MM-DDTHH:mm:ss'（ADM history start/end 契约格式，与 datetime-local 同构） */
function formatWallSeconds(ms) {
  const d = new Date(ms);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
    + `T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`;
}

/** 回放时间窗（墙钟域）：任务开始−15min / 任务结束+15min；终态记录必冻结起止，缺省兜底当前时刻 */
const replayWindow = computed(() => {
  const startSrc = wallClockMs(props.row?.startTime);
  const endSrc = wallClockMs(props.row?.endTime);
  const nowWall = Date.now() + SHANGHAI_OFFSET_MS;
  const start = Number.isNaN(startSrc) ? nowWall - REPLAY_PAD_MS : startSrc - REPLAY_PAD_MS;
  const end = Number.isNaN(endSrc) ? nowWall : endSrc + REPLAY_PAD_MS;
  return { start, end };
});

// ---------- 实时态：拉取与轮询 ----------

async function fetchOnce() {
  const id = props.row?.id;
  if (id == null) {
    return;
  }
  loading.value = !payload.value;
  try {
    const res = await getLiveProcess(id);
    if (res && res.code === 200) {
      const data = res.data || {};
      // 换记录竞态防护：迟到响应属旧记录时丢弃，防污染新记录的载荷/曲线
      if (data.recordId != null && String(data.recordId) !== String(id)) {
        return;
      }
      payload.value = data;
      loadError.value = '';
      mergeCurvePayload(data);
      renderChart();
    } else {
      loadError.value = (res && res.msg) || '过程数据解析失败';
    }
  } catch (e) {
    // 瞬时网络错误不打断轮询，仅首屏失败需要显式提示
    if (!payload.value) {
      loadError.value = '过程数据请求失败：' + (e?.message || e);
    }
  } finally {
    loading.value = false;
  }
  if (!isRunning.value) {
    stopPolling();
  }
}

/**
 * 曲线合并（历史回补 + 增量追加）：
 * - series.points：初始化直接铺历史段（任务前推 5min 起的后端缓存），轮询期到达的也按同刻覆盖幂等合并；
 * - attrs 快照：仅曲线通道（series 已登记的 attrId）按 updateTime 追加点——同刻覆盖、旧刻/新刻都由
 *   渲染前统一排序兜正，值无效（null）不加点。
 */
function mergeCurvePayload(data) {
  let touched = false;
  const defs = Array.isArray(data?.series) ? data.series : [];
  for (const def of defs) {
    if (!def || !def.attrId) {
      continue;
    }
    let ch = curvePoints.get(def.attrId);
    if (!ch) {
      ch = new Map();
      curvePoints.set(def.attrId, ch);
    }
    for (const p of (Array.isArray(def.points) ? def.points : [])) {
      if (p && typeof p.t === 'number' && typeof p.v === 'number') {
        ch.set(p.t, p.v);
        touched = true;
      }
    }
  }
  for (const a of (Array.isArray(data?.attrs) ? data.attrs : [])) {
    const ch = a && curvePoints.get(a.id);
    if (!ch || typeof a.value !== 'number' || typeof a.updateTime !== 'number') {
      continue;
    }
    ch.set(a.updateTime, a.value);
    touched = true;
  }
  if (touched) {
    hasCurveData.value = true;
  }
}

/** 通道点序列（时刻升序；Map 合并可能乱序，渲染前统一排序） */
function channelData(attrId) {
  const ch = curvePoints.get(attrId);
  if (!ch || !ch.size) {
    return [];
  }
  return [...ch.entries()].sort((a, b) => a[0] - b[0]);
}

/** 全通道最早时刻（x 轴下界保护：历史段早于「任务前推 5min」时以数据为准，不裁点） */
function firstCurvePointTime() {
  let min = null;
  for (const ch of curvePoints.values()) {
    for (const t of ch.keys()) {
      if (min === null || t < min) {
        min = t;
      }
    }
  }
  return min;
}

function startPolling() {
  stopPolling();
  if (!props.active || !isRunning.value || !analyzer.value) {
    return;
  }
  pollTimer = setInterval(() => {
    if (!props.active || !isRunning.value) {
      stopPolling();
      return;
    }
    void fetchOnce();
  }, POLL_INTERVAL_MS);
}

function stopPolling() {
  if (pollTimer != null) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

function resetAll() {
  stopPolling();
  payload.value = null;
  loadError.value = '';
  curvePoints.clear();
  hasCurveData.value = false;
  replayView.value = 'table';
  replayError.value = '';
  replayFallbackNote.value = '';
  replayColumns.value = [];
  replayRows.value = [];
  replayLoadedKey = '';
  disposeChart();
  void fetchOnce();
}

// ---------- 实时态：echarts 生命周期 ----------

function ensureChart() {
  if (!chartRef.value) {
    return null;
  }
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value);
  }
  return chartInstance;
}

function disposeChart() {
  if (chartInstance) {
    chartInstance.dispose();
    chartInstance = null;
  }
}

function renderChart() {
  if (mode.value !== 'live') {
    return;
  }
  nextTick(() => {
    const inst = ensureChart();
    if (!inst) {
      return;
    }
    const defs = seriesDefs.value;
    if (!defs.length) {
      return;
    }
    const unitByName = new Map(defs.map(d => [d.name, d.unit || curveUnit.value]));
    const startSrc = recordTimeMillis(props.row?.startTime);
    let min = Number.isNaN(startSrc) ? undefined : startSrc - CURVE_LEAD_MS;
    const firstT = firstCurvePointTime();
    if (min !== undefined && firstT !== null && firstT < min) {
      min = firstT;
    }
    const option = {
      animation: false,
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const arr = Array.isArray(params) ? params : [params];
          const lines = [];
          for (const p of arr) {
            const v = Array.isArray(p.value) ? p.value[1] : p.value;
            if (v == null) {
              continue;
            }
            const u = unitByName.get(p.seriesName) || '';
            lines.push(`${p.marker}${p.seriesName}：${v}${u ? ' ' + u : ''}`);
          }
          if (!lines.length) {
            return '';
          }
          return `${formatMillis(arr[0].value[0])}<br/>${lines.join('<br/>')}`;
        }
      },
      // echarts 默认 legend：点击通道名可显隐（NOx 三通道同图时用于单独查看）
      legend: { top: 0, textStyle: { fontSize: 11 } },
      grid: { left: 56, right: 96, top: 38, bottom: 28 },
      xAxis: {
        type: 'time',
        min,
        axisLabel: { fontSize: 10, hideOverlap: true }
      },
      yAxis: {
        type: 'value',
        scale: true,
        name: curveUnit.value,
        nameTextStyle: { fontSize: 11 },
        axisLabel: { fontSize: 10 }
      },
      series: defs.map(def => {
        const main = !!def.isMain;
        const unit = def.unit || curveUnit.value;
        return {
          name: def.name,
          type: 'line',
          showSymbol: true,
          symbolSize: main ? 4 : 3,
          connectNulls: false,
          data: channelData(def.attrId),
          // 主通道加粗高亮；辅助通道细线低透明度，多通道同图主次分明
          lineStyle: { width: main ? 2.5 : 1.2, opacity: main ? 1 : 0.55 },
          itemStyle: { opacity: main ? 1 : 0.55 },
          emphasis: { focus: 'series' },
          // 最新值常显在曲线末端（仅主通道，避免多通道 endLabel 互相叠压），颜色跟随系列色
          endLabel: main ? {
            show: true,
            fontWeight: 600,
            formatter: (p) => {
              const v = Array.isArray(p.value) ? p.value[1] : p.value;
              return v != null ? (unit ? `${v} ${unit}` : String(v)) : '';
            }
          } : undefined,
          // 目标浓度辅助线（零点=0 / 跨度=标气浓度）只挂主通道：非主通道无目标语义且多通道重复
          markLine: main && markLine.value && typeof markLine.value.value === 'number'
            ? {
                silent: true,
                symbol: 'none',
                lineStyle: { type: 'dashed', color: '#E6A23C', width: 1.5 },
                label: {
                  formatter: `${markLine.value.label} ${markLine.value.value} ${markLine.value.unit}`,
                  position: 'insideStartTop',
                  color: '#E6A23C',
                  fontSize: 11
                },
                data: [{ yAxis: markLine.value.value }]
              }
            : undefined
        };
      })
    };
    inst.setOption(option, true);
  });
}

// ---------- 回放态：ADM 历史数据（表格/曲线双视图） ----------

/**
 * ADM 历史横表查询（GET /adm-monitor/history）：回放态数据源（面板私有，就近封装）。
 * 走 ruoyi 同源 request 实例（自动带鉴权），需 adm:monitor:list 权限；
 * query 契约与 ADM 历史数据页同构：granularity/start/end（上海墙钟 ISO 秒串，无 Z）/
 * params（uid:attrId 逗号串）/unit（custom=按 HISTORY 展示单位偏好换算）/pageNum（必传——
 * 缺省时后端分页校验报 500「pageNum 须 ≥1」）/pageSize，响应
 * data={columns:[{logicDeviceUniqueId,attrId,label,unit}],rows:[{bucket,cells:[{value,statuses[]}]}],total,...}。
 */
/**
 * 回放列序重排：污染参数（MONITOR）列在前、状态参数列在后，同 kind 内维持 live_process 目录序。
 * 后端按 params 请求序返回列，但以前端按目录 kind 重排为准——排序真相源唯一，不依赖后端排序实现。
 * cells 与 columns 按索引对齐，重排列须同步置换每行 cells；目录外的列（正常不出现）按响应原序排尾不丢弃。
 */
function orderReplayResult(rawColumns, rawRows) {
  const uid = analyzer.value?.uniqueId || '';
  const indexByKey = new Map();
  rawColumns.forEach((c, i) => {
    if (c && c.attrId) {
      indexByKey.set(`${c.logicDeviceUniqueId || uid}:${c.attrId}`, i);
    }
  });
  const catalog = attrRows.value.filter(a => a && a.id);
  const wantedKeys = [
    ...catalog.filter(a => a.kind === 'MONITOR').map(a => `${uid}:${a.id}`),
    ...catalog.filter(a => a.kind !== 'MONITOR').map(a => `${uid}:${a.id}`)
  ];
  const order = [];
  const used = new Set();
  for (const key of wantedKeys) {
    const idx = indexByKey.get(key);
    if (idx !== undefined && !used.has(idx)) {
      order.push(idx);
      used.add(idx);
    }
  }
  rawColumns.forEach((_, i) => {
    if (!used.has(i)) {
      order.push(i);
    }
  });
  return {
    columns: order.map(i => rawColumns[i]),
    rows: rawRows.map(r => ({ ...r, cells: order.map(i => (r.cells || [])[i]) }))
  };
}

async function loadReplay() {
  const uid = analyzer.value?.uniqueId;
  if (!uid || mode.value !== 'replay') {
    return;
  }
  const win = replayWindow.value;
  replayFallbackNote.value = '';
  const paramPairs = [];
  if (attrRows.value.length) {
    // 参数目录取 live_process attrs（分析仪全参数）→ 每参数一列横表
    for (const a of attrRows.value) {
      paramPairs.push(`${uid}:${a.id}`);
    }
  } else {
    // 目录不可用（attrsReason）：退化为质控采集通道单参数回放，并注明退化原因
    const key = composerKeyOfParameter(props.row?.parameter);
    if (!key) {
      replayError.value = attrsReason.value || '分析仪参数目录不可用且质控参数无法映射采集通道，无法构建回放查询';
      replayColumns.value = [];
      replayRows.value = [];
      return;
    }
    paramPairs.push(`${uid}:${key}`);
    replayFallbackNote.value = `分析仪参数目录不可用（${attrsReason.value || '原因未知'}），仅回放质控主浓度通道`;
  }
  const loadKey = [props.row?.id, win.start, win.end, paramPairs.join(',')].join('|');
  if (loadKey === replayLoadedKey && !replayError.value) {
    // 同记录同窗不重复拉取（mode/id 双 watch 可能重叠触发；表格/曲线切视图不触发重查）
    return;
  }
  replayLoadedKey = loadKey;
  replayLoading.value = true;
  replayError.value = '';
  try {
    // 每页桶数取足窗内桶数（±15min≈31 桶起步；长任务随窗增长），封顶后端 MAX_PAGE_SIZE=200
    const buckets = Math.ceil((win.end - win.start) / 60000);
    const pageSize = Math.min(200, Math.max(100, buckets + 2));
    const res = await getAdmHistory({
      granularity: 'minute',
      start: formatWallSeconds(win.start),
      end: formatWallSeconds(win.end),
      params: paramPairs.join(','),
      // custom=按 HISTORY 展示单位偏好换算：浓度参数得体积单位（ppb/ppm，与校准标准值同口径）；
      // 状态参数无偏好行 resolveDisplay 返 null → 后端保底原生单位（°C/L/min…，合理）
      unit: 'custom',
      pageNum: 1,
      pageSize
    });
    if (res && res.code === 200 && res.data) {
      const ordered = orderReplayResult(
        Array.isArray(res.data.columns) ? res.data.columns : [],
        Array.isArray(res.data.rows) ? res.data.rows : []
      );
      replayColumns.value = ordered.columns;
      replayRows.value = ordered.rows;
    } else {
      replayError.value = 'ADM 历史数据查询失败：' + ((res && res.msg) || '未知错误');
      replayColumns.value = [];
      replayRows.value = [];
    }
  } catch (e) {
    // 401/403/网络/ADM 未加载：显式提示不白屏
    replayError.value = '回放数据加载失败：需要 adm:monitor:list 权限或 ADM 未就绪（' + ((e && e.message) || e) + '）';
    replayColumns.value = [];
    replayRows.value = [];
  } finally {
    replayLoading.value = false;
  }
}

function replayColKey(col, i) {
  return (col && col.logicDeviceUniqueId && col.attrId)
    ? `${col.logicDeviceUniqueId}:${col.attrId}`
    : `col-${i}`;
}

/** 桶时刻（ISO UTC 带 Z）→ 小图横轴短标签 'MM-dd HH:mm'（浏览器壁钟分钟精度，省横向空间） */
function formatBucketShort(bucket) {
  const t = bucket ? new Date(bucket).getTime() : NaN;
  if (Number.isNaN(t)) {
    return '';
  }
  const d = new Date(t);
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * 曲线视图数据：每参数一条序列（图序=表格列序，污染参数图自然排最前）。
 * 行倒序（最新在前）翻转成时间升序画线；cell.value 是字符串（如 "15.500"）→ parseFloat，
 * NaN/缺值置 null 跳点（不猜）；非 NORMAL 桶标 abnormal 供小图打红点。
 */
const replayChartSeries = computed(() => {
  const cols = replayColumns.value;
  if (!cols.length) {
    return [];
  }
  const rowsAsc = [...replayRows.value].reverse();
  return cols.map((col, ci) => ({
    key: replayColKey(col, ci),
    label: col.label || col.attrId || '',
    unit: col.unit || '',
    points: rowsAsc.map(row => {
      const cell = (row.cells || [])[ci];
      const raw = cell == null ? null : cell.value;
      const num = (raw === null || raw === undefined || raw === '') ? NaN : parseFloat(raw);
      const tags = cellAbnormalTags(cell);
      return {
        time: formatBucketShort(row.bucket),
        value: Number.isFinite(num) ? num : null,
        abnormal: tags.length > 0,
        tags
      };
    })
  }));
});

// ---------- 展示格式化 ----------

/** 属性值带单位：契约 value 为 number|null（工程值），单位已是短显示名（ppb/L/min…） */
function formatAttrValue(r) {
  if (r.value === null || r.value === undefined || r.value === '') {
    return '—';
  }
  return r.unit ? `${r.value} ${r.unit}` : String(r.value);
}

/** 状态组全 NORMAL 视为正常（含单元素 ['NORMAL']） */
function isNormalStatuses(statuses) {
  return statuses.every(s => s === 'NORMAL');
}

/** 非 NORMAL 状态子集（渲染 tag 用） */
function nonNormalStatuses(statuses) {
  return statuses.filter(s => s !== 'NORMAL');
}

function liveStatusLabel(name) {
  return ATTR_STATUS_LABELS[name] || name;
}

function liveStatusTagType(name) {
  if (STATUS_TAG_WARN.has(name)) {
    return 'warning';
  }
  if (STATUS_TAG_INFO.has(name)) {
    return 'info';
  }
  return 'danger';
}

function formatMillis(ms) {
  if (typeof ms !== 'number' || Number.isNaN(ms)) {
    return '—';
  }
  const d = new Date(ms);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** ADM 桶时刻（ISO UTC 带 Z）→ 浏览器壁钟展示（与 ADM 状态页 formatLocalDateTime 同口径） */
function formatBucket(bucket) {
  if (!bucket) {
    return '—';
  }
  return formatMillis(new Date(bucket).getTime());
}

/** 回放 cell 值：null=该序列该时刻无数据 */
function cellValueText(cell) {
  if (cell == null || cell.value === null || cell.value === undefined || cell.value === '') {
    return '—';
  }
  return String(cell.value);
}

/** 回放 cell 非 NORMAL 标记（history 契约：后端已滤 NORMAL 只剩非正常中文标记，空表=正常桶或 avg 缺失；
 *  再滤 '数据有效' 为容忍两契约形态的防御——非空即视为异常桶） */
function cellAbnormalTags(cell) {
  if (!cell || !Array.isArray(cell.statuses)) {
    return [];
  }
  return cell.statuses.filter(s => s && s !== '数据有效');
}

/** 回放标记 tag 配色（中文释义正则归类，镜像 ADM status-mark 分组语义） */
function replayTagType(label) {
  if (/维护|校准|检查|检定|更换/.test(label)) {
    return 'warning';
  }
  if (/不足|恢复|离线|未设置/.test(label)) {
    return 'info';
  }
  return 'danger';
}

// ---------- 响应式联动 ----------

// 换记录 / 弹窗重开（父组件 row 重置导致 id 变化）→ 全量重置
watch(() => props.row?.id, () => resetAll());

// 视图切换联动：进实时态渲染曲线；进回放态释放图表并拉取 ADM 历史数据
// （id 也在监听源里：换记录后 mode 先被 resetAll 打回 unresolved、拉取后再翻转，确保新记录回放被触发）
watch([() => props.row?.id, mode], ([, m]) => {
  if (m === 'live') {
    renderChart();
  } else if (m === 'replay') {
    disposeChart();
    void loadReplay();
  }
});

// tab 激活：恢复轮询 + 图表重排（隐藏期间尺寸变化）
watch(() => props.active, (active) => {
  if (active) {
    startPolling();
    nextTick(() => {
      if (chartInstance) {
        chartInstance.resize();
      }
    });
  } else {
    stopPolling();
  }
});

// 执行翻转到终态：停轮询（回放加载由 mode watch 接手）
watch(isRunning, (running) => {
  if (!running) {
    stopPolling();
  }
});

onBeforeUnmount(() => {
  stopPolling();
  disposeChart();
});

// 首次挂载即拉取（tab 是 lazy 的，挂载时必然可见）
void fetchOnce().then(() => startPolling());
</script>

<style scoped>
.qc-process-panel {
  min-height: 120px;
}

.qc-process-loading {
  height: 160px;
}

.qc-process-empty :deep(.el-empty__description p) {
  font-size: 12px;
  color: #909399;
  max-width: 420px;
  line-height: 1.6;
}

.qc-process-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 10px;
  background: #f0f9ff;
  border-radius: 4px;
  margin-bottom: 12px;
}

.qc-process-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.qc-process-title small {
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}

.qc-process-section {
  margin-bottom: 14px;
}

.qc-process-section h5 {
  margin: 0 0 8px;
  color: #303133;
  font-size: 14px;
  font-weight: 600;
  border-left: 4px solid #409eff;
  padding-left: 8px;
}

.qc-process-catalog-alert {
  margin-bottom: 14px;
}

.qc-process-main-attr {
  color: #409eff;
  font-weight: 600;
}

.qc-process-main-tag {
  margin-left: 6px;
}

.qc-process-status-tag {
  margin-left: 4px;
}

.qc-process-status-tag + .qc-process-status-tag {
  margin-left: 2px;
}

.qc-status-ok {
  color: #67c23a;
}

.qc-status-muted {
  color: #909399;
}

.qc-process-chart-box {
  position: relative;
  width: 100%;
  height: 300px;
}

.qc-process-chart {
  width: 100%;
  height: 100%;
}

.qc-process-chart-hint {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 12px;
  pointer-events: none;
}

.qc-process-note {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.qc-process-replay-alert {
  margin-bottom: 8px;
}

/* 回放头部工具行：说明文字左、表格/曲线视图切换右 */
.qc-replay-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.qc-replay-toolbar-note {
  flex: 1 1 auto;
  min-width: 0;
  margin: 0;
}

.qc-replay-chart-area {
  position: relative;
  min-height: 120px;
}

.qc-replay-chart-empty {
  padding: 40px 0;
  text-align: center;
  color: #909399;
  font-size: 12px;
}

/* 回放横表列头：参数名 + 单位两行（镜像 ADM 历史数据页表头形态） */
.qc-replay-col-label {
  font-size: 12px;
  font-weight: 600;
  color: #303133;
}

.qc-replay-col-unit {
  font-size: 11px;
  font-weight: 400;
  color: #909399;
  line-height: 1.4;
}

.qc-replay-cell {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  flex-wrap: wrap;
}

.qc-replay-val {
  font-variant-numeric: tabular-nums;
  color: #303133;
}
</style>
