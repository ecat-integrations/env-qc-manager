<template>
  <div class="qc:min-h-screen qc:bg-gray-50">
    <!-- 主内容区 -->
    <main class="qc:max-w-7xl qc:mx-auto qc:px-4 qc:sm:px-6 qc:lg:px-8 qc:py-6">
      <!-- 周期设置 -->
      <div class="qc:bg-white qc:rounded-xl qc:shadow-md qc:p-8 qc:mb-8">
        <h2 class="qc:text-xl qc:font-semibold qc:text-gray-800 qc:mb-6">巡检周期设置</h2>
        <div class="qc:grid qc:grid-cols-1 qc:md:grid-cols-2 qc:gap-8">
          <div>
            <label class="qc:block qc:text-sm qc:font-medium qc:text-gray-700 qc:mb-2">周期起始日</label>
            <el-date-picker
              v-model="cycleSettings.startDate"
              type="date"
              placeholder="选择日期"
              class="w-full"
              format="YYYY-MM-DD"
              value-format="YYYY-MM-DD"
            />
          </div>
          <div>
            <label class="qc:block qc:text-sm qc:font-medium qc:text-gray-700 qc:mb-2">周期长度</label>
            <div class="qc:flex qc:items-center">
              <el-input-number
                v-model="cycleSettings.duration"
                :min="1"
                :max="14"
                controls-position="right"
                class="w-32"
              />
              <span class="qc:ml-2 qc:text-sm qc:text-gray-500">天</span>
            </div>
          </div>
        </div>
        <div class="qc:mt-8">
          <el-button type="primary" class="!rounded-button whitespace-nowrap" @click="saveCycleSettings">
            <el-icon class="qc:mr-2"><Check /></el-icon>
            保存周期设置
          </el-button>
        </div>
      </div>
      <!-- 巡检类型设置 -->
      <div class="qc:space-y-6">
        <div
          v-for="setting in inspectionSettings"
          :key="setting.type"
          class="qc:bg-white qc:rounded-xl qc:shadow-md qc:p-6 qc:hover:shadow-lg transition-shadow duration-300"
        >
          <div class="qc:flex qc:justify-between qc:items-start qc:mb-6">
            <div>
              <h3 class="qc:text-lg qc:font-semibold qc:text-gray-800">{{ setting.label }}</h3>
              <p class="qc:text-sm qc:text-gray-600 qc:mt-2">
                当前周期内检查次数: <span class="qc:font-medium">{{ setting.timesPerCycle || 0 }}</span>
              </p>
            </div>
          </div>
          <div class="qc:mt-4">
            <label class="qc:block qc:text-sm qc:font-medium qc:text-gray-700 qc:mb-2">每周期检查次数</label>
            <el-input-number
              v-model="setting.timesPerCycle"
              :min="0"
              :max="10"
              controls-position="right"
              class="w-32"
            />
          </div>
        </div>
      </div>
      <!-- 保存按钮 -->
      <div class="qc:mt-8 qc:bg-white qc:rounded-xl qc:shadow-md qc:p-6">
        <el-button type="primary" size="large" class="!rounded-button whitespace-nowrap" @click="saveAllSettings">
          <el-icon class="qc:mr-2"><Check /></el-icon>
          保存所有设置
        </el-button>
      </div>
    </main>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { Check } from '@element-plus/icons-vue';

// 星期选项
const weekDays = [
  { value: 0, label: '星期日' },
  { value: 1, label: '星期一' },
  { value: 2, label: '星期二' },
  { value: 3, label: '星期三' },
  { value: 4, label: '星期四' },
  { value: 5, label: '星期五' },
  { value: 6, label: '星期六' }
];

// 周期设置
const cycleSettings = reactive({
  startDate: '', // 默认空日期
  duration: 7  // 默认7天一个周期
});

// 巡检类型设置
const inspectionSettings = ref([
  {
    type: 'station',
    label: '子站巡检',
    timesPerCycle: 1
  },
  {
    type: 'instrument_status',
    label: '仪器运行状况',
    timesPerCycle: 2
  },
  {
    type: 'multi_point',
    label: '仪器多点校准',
    timesPerCycle: 1
  },
  {
    type: 'precision',
    label: '仪器精密度审核',
    timesPerCycle: 1
  },
  {
    type: 'accuracy',
    label: '仪器准确度审核',
    timesPerCycle: 0
  },
  {
    type: 'nox_efficiency',
    label: '氮氧化物分析仪转换效率测试',
    timesPerCycle: 1
  },
  {
    type: 'ozone_calibration',
    label: '臭氧校准设备量值传递',
    timesPerCycle: 0
  }
]);

// 保存周期设置
const saveCycleSettings = () => {
  ElMessage.success(`周期设置已保存: 从${weekDays.find(d => d.value === cycleSettings.startDay)?.label}开始，${cycleSettings.duration}天为一个周期`);
  // 实际项目中这里应该是调用API保存设置
};

// 保存所有设置
const saveAllSettings = () => {
  const settings = {
    cycle: { ...cycleSettings },
    inspections: inspectionSettings.value.map(s => ({
      type: s.type,
      timesPerCycle: s.timesPerCycle
    }))
  };
  console.log('所有设置:', settings);
  ElMessage.success('所有巡检设置已保存');
  // 实际项目中这里应该是调用API保存所有设置
};

// 初始化数据
onMounted(() => {
  // 可以在这里加载已有的设置
});
</script>
<style scoped>
/* 自定义样式 */
:deep(.el-input-number) {
  width: 100%;
}
:deep(.el-input-number .el-input__wrapper) {
  padding: 8px 12px;
}
:deep(.el-date-editor) {
  width: 100%;
}
:deep(.el-button) {
  font-weight: 500;
  letter-spacing: 0.5px;
}
</style>
