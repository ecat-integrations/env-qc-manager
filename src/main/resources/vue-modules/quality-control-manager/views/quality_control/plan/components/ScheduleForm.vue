<template>
  <el-form label-width="110px">
    <el-form-item label="执行频率">
      <el-radio-group v-model="model.scheduleType">
        <el-radio value="INTERVAL">按天</el-radio>
        <el-radio value="WEEKLY">按周</el-radio>
        <el-radio value="MONTHLY">按月</el-radio>
        <!-- 一次性仅单计划模式提供：集合是共享气路的错峰套件，单时刻触发无法承载逐行错峰（同时通气=气路冲突）；
           「某天跑一次全套」的规范表达 = 按天（间隔 1）+ 开始日期 = 当天 + 有效期止 = 当天（服务端对集合 ONCE 同口径显式拒绝） -->
        <el-radio v-if="!dateOnly" value="ONCE">一次性</el-radio>
      </el-radio-group>
    </el-form-item>
    <el-form-item v-if="model.scheduleType === 'INTERVAL'" label="间隔天数" required>
      <el-input-number v-model="model.intervalDays" :min="1" :max="31" :step="1" :precision="0" />
      <span class="qc-plan-hint">每 N 天执行一次（1 = 每天）</span>
    </el-form-item>
    <template v-if="model.scheduleType === 'WEEKLY'">
      <el-form-item label="间隔周数" required>
        <el-input-number v-model="model.intervalWeeks" :min="1" :max="52" :step="1" :precision="0" />
        <span class="qc-plan-hint">从开始日期起每 N 周一轮（1 = 每周）</span>
      </el-form-item>
      <el-form-item label="星期">
        <el-checkbox-group v-model="model.weekdays">
          <el-checkbox v-for="(name, i) in WEEKDAY_NAMES" :key="name" :value="i + 1">{{ name }}</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
    </template>
    <el-form-item v-if="model.scheduleType === 'MONTHLY'" label="每月几号">
      <el-checkbox-group v-model="model.monthDays" class="qc-plan-month-grid">
        <el-checkbox v-for="d in 31" :key="d" :value="d">{{ d }}</el-checkbox>
      </el-checkbox-group>
    </el-form-item>
    <el-form-item :label="dateOnly ? '开始日期' : '开始时间'" required>
      <el-date-picker
        v-model="model.planStartTime"
        :type="dateOnly ? 'date' : 'datetime'"
        :value-format="dateOnly ? 'YYYY-MM-DDT00:00:00Z' : 'YYYY-MM-DDTHH:mm:ssZ'"
        :placeholder="dateOnly ? '调度起算日' : '日期为调度锚点，时间为触发时刻'"
      />
      <span v-if="!dateOnly" class="qc-plan-hint">
        {{ model.scheduleType === 'ONCE' ? '该时刻即唯一触发时刻（须晚于当前时刻）' : '从该时刻起往后调度；时间部分即每日触发时刻' }}
      </span>
      <span v-else class="qc-plan-hint">从该日起往后调度，逐行触发时刻见模板任务表</span>
    </el-form-item>
    <el-form-item label="有效期止">
      <el-date-picker
        v-model="model.planEndTime"
        :type="dateOnly ? 'date' : 'datetime'"
        :value-format="dateOnly ? 'YYYY-MM-DDT00:00:00Z' : 'YYYY-MM-DDTHH:mm:ssZ'"
        placeholder="留空 = 长期有效"
      />
    </el-form-item>
    <el-form-item label="预览">
      <span class="qc-schedule-preview">{{ previewText }}</span>
    </el-form-item>
  </el-form>
</template>

<script setup>
import { computed } from 'vue';
import { ElMessage } from 'element-plus';

defineOptions({ name: 'QcmScheduleForm' });

/**
 * 单计划与集合批量共用的调度表单（model prop 传入调用方持有的调度对象，字段就地读写）：
 * scheduleType = INTERVAL（按天）/ WEEKLY / MONTHLY / ONCE；intervalDays、intervalWeeks、
 * weekdays、monthDays、planStartTime、planEndTime（RFC 3339 带时区偏移串，与 PlanSaveDto 同名同型：
 * picker 的 Z token 输出冒号偏移（+08:00，RFC 3339 numoffset 形态；ZZ 是无冒号 +0800 非 RFC 3339），
 * 绝对时刻无歧义，杜绝墙钟冒充 UTC 的漂移）。
 * 语义：planStartTime 日期部分 = 调度起算锚点，时间部分 = 每日触发时刻（ONCE 时整体即触发时刻）。
 * dateOnly = 集合批量模式：开始/截止降为日期选择（触发时刻由模板任务表逐行提供），不提供一次性
 * （集合为共享气路错峰套件，单时刻触发无法逐行错峰，物理上不成立）。
 */
const props = defineProps({
  model: { type: Object, required: true },
  dateOnly: { type: Boolean, default: false }
});

const WEEKDAY_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

/** ISO 串（YYYY-MM-DDTHH:mm:ss±HH:mm）取 "MM-DD HH:mm" 段做预览；dateOnly 只取日期段 */
const ISO_PATTERN = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/;

function previewPart(iso) {
  const m = ISO_PATTERN.exec(String(iso || ''));
  if (!m) {
    return '';
  }
  return props.dateOnly ? m[1].slice(5) : m[1].slice(5) + ' ' + m[2];
}

/** 实时人话预览：只描述规则不计算具体下次触发（next_fire_time 由服务端下发） */
const previewText = computed(() => {
  const m = props.model;
  if (!m.planStartTime) {
    return '请先选择' + (props.dateOnly ? '开始日期' : '开始时间');
  }
  const start = previewPart(m.planStartTime);
  let text;
  switch (m.scheduleType) {
    case 'INTERVAL':
      text = '从 ' + start + ' 起，' + (m.intervalDays === 1 ? '每天执行一次' : '每 ' + m.intervalDays + ' 天执行一次');
      break;
    case 'WEEKLY': {
      const names = [...(m.weekdays || [])].sort((a, b) => a - b).map(d => WEEKDAY_NAMES[d - 1]).join('、');
      const prefix = m.intervalWeeks === 1 ? '每周' : '每 ' + m.intervalWeeks + ' 周的';
      text = '从 ' + start + ' 起，' + prefix + (names || '（未选星期）') + '执行';
      break;
    }
    case 'MONTHLY': {
      const days = [...(m.monthDays || [])].sort((a, b) => a - b).join('、');
      text = '从 ' + start + ' 起，每月 ' + (days || '（未选日期）') + ' 日执行';
      break;
    }
    case 'ONCE':
      text = '将于 ' + start + ' 执行一次';
      break;
    default:
      return '';
  }
  if (m.planEndTime) {
    text += '；有效期至 ' + previewPart(m.planEndTime);
  }
  return text;
});

/** 提交前校验（服务端仍全量校验兜底，此处只为少跑一趟网络）；提示语按模式具体化 */
function validate() {
  const m = props.model;
  const startLabel = props.dateOnly ? '开始日期' : '开始时间';
  if (!m.planStartTime) {
    ElMessage.warning('请选择' + startLabel);
    return false;
  }
  if (m.scheduleType === 'INTERVAL' && (m.intervalDays == null || m.intervalDays < 1 || m.intervalDays > 31)) {
    ElMessage.warning('间隔天数必须是 1-31 的整数');
    return false;
  }
  if (m.scheduleType === 'WEEKLY') {
    if (m.intervalWeeks == null || m.intervalWeeks < 1 || m.intervalWeeks > 52) {
      ElMessage.warning('间隔周数必须是 1-52 的整数');
      return false;
    }
    if (!m.weekdays || m.weekdays.length === 0) {
      ElMessage.warning('每周至少选择一个星期');
      return false;
    }
  }
  if (m.scheduleType === 'MONTHLY' && (!m.monthDays || m.monthDays.length === 0)) {
    ElMessage.warning('每月至少选择一个日期');
    return false;
  }
  // 转绝对时刻比较（带偏移串跨形态亦正确；回填组合出的无偏移串按浏览器本地时区解析）
  if (m.planEndTime && new Date(m.planStartTime).getTime() >= new Date(m.planEndTime).getTime()) {
    ElMessage.warning('有效期止必须晚于' + startLabel);
    return false;
  }
  return true;
}

defineExpose({ validate });
</script>

<style>
.qc-plan-month-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 2px 8px;
}

.qc-schedule-preview {
  display: inline-block;
  font-size: 13px;
  color: #606266;
  background: #f5f7fa;
  border-radius: 4px;
  padding: 4px 10px;
}
</style>
