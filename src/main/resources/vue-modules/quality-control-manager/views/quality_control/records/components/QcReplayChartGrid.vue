<template>
  <!--
    质控记录回放曲线视图：每参数一张独立小图（形态照 ADM 历史数据页分图风格——卡片流式网格 +
    图头标题/单位 + category 时间轴折线）。数据由父面板一次查询共用（切视图不重查），本组件纯渲染：
    series 变化整批重渲（先 dispose 旧实例再 init，实例数=参数数），卸载/重渲全量 dispose 防泄漏。
  -->
  <div ref="panelRef" class="qc-replay-charts">
    <div v-for="s in series" :key="s.key" class="qc-replay-chart-cell">
      <div class="qc-replay-chart-head" :title="s.unit ? `${s.label}（${s.unit}）` : s.label">
        {{ s.label }}<span v-if="s.unit" class="qc-replay-chart-unit">（{{ s.unit }}）</span>
      </div>
      <div :ref="el => setChartRef(s.key, el)" class="qc-replay-chart-box"></div>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'QcReplayChartGrid' });
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import * as echarts from 'echarts';

/**
 * 回放曲线网格：series 每元素一图（父面板已按「污染参数在前」排好序，图序=列序）。
 * @param {Array<{key:string,label:string,unit:string,points:Array<{time:string,value:number|null,abnormal:boolean,tags:string[]}>}>} series
 */
const props = defineProps({
  series: { type: Array, default: () => [] }
});

const panelRef = ref(null);
/** key → DOM 元素（v-for 函数 ref，卸载时以 null 回调摘除） */
const chartRefs = {};
/** key → echarts 实例；数量恒等于当前 series 参数数 */
const chartInstances = {};
/** 跨图 hover 联动组名（echarts.connect 识别键；实例 dispose 自动离组，重渲重建后重连幂等） */
const REPLAY_LINK_GROUP = 'qc-replay-charts';
let resizeObserver = null;
let renderTimer = null;

function setChartRef(key, el) {
  if (el) {
    chartRefs[key] = el;
  } else {
    delete chartRefs[key];
  }
}

/** 整批释放：重渲前与卸载时都走这里，保证磁盘上不存在无主 echarts 实例 */
function disposeAll() {
  for (const key of Object.keys(chartInstances)) {
    chartInstances[key].dispose();
    delete chartInstances[key];
  }
}

/** 非 NORMAL 桶的异常点数据项：红点 + 悬停可见标记文本；正常/缺值点保持纯值（折线不加点，干净） */
function pointData(p) {
  if (p && p.abnormal && p.value !== null && p.value !== undefined) {
    return {
      value: p.value,
      symbol: 'circle',
      symbolSize: 6,
      itemStyle: { color: '#F56C6C' },
      tags: (p.tags || []).join('、')
    };
  }
  return p ? p.value : null;
}

/** 单参数小图 option：bucket→x（category 短时刻标签）、value→y（scale 随数据起算不锁 0） */
function buildOption(s) {
  const points = s.points || [];
  const unit = s.unit || '';
  return {
    animation: false,
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const first = Array.isArray(params) ? params[0] : params;
        if (!first) {
          return '';
        }
        // 异常点是数据对象、正常点是裸值，两种形态统一取值；缺值桶显 —（跳点可见）
        const raw = first.data;
        const v = (raw && typeof raw === 'object' && !Array.isArray(raw)) ? raw.value : raw;
        const valText = (v === null || v === undefined) ? '—' : `${v}${unit ? ' ' + unit : ''}`;
        const markText = raw && typeof raw === 'object' && raw.tags ? `（标记：${raw.tags}）` : '';
        return `${first.axisValue}<br/>${first.marker}${valText}${markText}`;
      }
    },
    grid: { left: 50, right: 12, top: 28, bottom: 26 },
    xAxis: {
      type: 'category',
      data: points.map(p => (p ? p.time : '')),
      axisLabel: { fontSize: 10, hideOverlap: true }
    },
    yAxis: {
      type: 'value',
      scale: true,
      name: unit,
      nameTextStyle: { fontSize: 10 },
      axisLabel: { fontSize: 10 }
    },
    series: [{
      type: 'line',
      showSymbol: false,
      connectNulls: false,
      data: points.map(pointData)
    }]
  };
}

function renderCharts() {
  disposeAll();
  for (const s of props.series) {
    const el = chartRefs[s.key];
    if (!el) {
      continue;
    }
    const inst = echarts.init(el);
    inst.setOption(buildOption(s), true);
    // 跨图联动：同组实例 hover 一图、其余图在相同 bucket 处同步出 tooltip/十字线
    // （各图 x 轴=同一份分钟桶序列，dataIndex 天然对齐；group 机制为 echarts 原生 connect）
    inst.group = REPLAY_LINK_GROUP;
    chartInstances[s.key] = inst;
  }
  if (Object.keys(chartInstances).length > 1) {
    echarts.connect(REPLAY_LINK_GROUP);
  }
}

/** 合并抖动：series 引用变化与挂载可能连续触发，统一延迟到下一个宏任务（DOM patch 微任务之后）再渲 */
function scheduleRender() {
  if (renderTimer) {
    clearTimeout(renderTimer);
  }
  renderTimer = setTimeout(() => {
    renderTimer = null;
    renderCharts();
    bindResizeObserver();
  }, 0);
}

function resizeAll() {
  for (const inst of Object.values(chartInstances)) {
    inst.resize();
  }
}

/** 弹窗宽度变化/首次布局后重排各图尺寸；观察容器即可（网格内格子尺寸随之联动） */
function bindResizeObserver() {
  unbindResizeObserver();
  if (typeof ResizeObserver === 'undefined' || !panelRef.value) {
    return;
  }
  resizeObserver = new ResizeObserver(resizeAll);
  resizeObserver.observe(panelRef.value);
}

function unbindResizeObserver() {
  if (resizeObserver) {
    resizeObserver.disconnect();
    resizeObserver = null;
  }
}

// 数据更新（首次载入/重查/换记录共用挂载态）→ 整批重渲；父面板 v-if 挂载时 DOM 已可见，init 即得真实尺寸
watch(() => props.series, scheduleRender);
onMounted(scheduleRender);

onBeforeUnmount(() => {
  if (renderTimer) {
    clearTimeout(renderTimer);
    renderTimer = null;
  }
  unbindResizeObserver();
  disposeAll();
});
</script>

<style scoped>
/* 卡片流式网格：一行自适应 2~3 张（弹窗 72% 宽下 minmax 320px 约 3 列），行高固定小图 ~200px 量级 */
.qc-replay-charts {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  grid-auto-rows: 216px;
  gap: 10px;
}

.qc-replay-chart-cell {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  box-sizing: border-box;
  padding: 6px 8px 6px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
}

.qc-replay-chart-head {
  flex: 0 0 20px;
  display: flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  font-weight: 600;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.qc-replay-chart-unit {
  font-weight: 400;
  font-size: 11px;
  color: #909399;
}

.qc-replay-chart-box {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
}
</style>
