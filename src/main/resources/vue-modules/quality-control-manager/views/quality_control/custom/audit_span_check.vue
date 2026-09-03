<template>
  <div class="app-container qc-audit-page">
    <!-- 参数配置：ruoyi 表单执行页规范形态，数值单位一律在输入框后缀可见 -->
    <el-card>
      <template #header>
        <span>参数配置</span>
      </template>

      <el-form
        ref="formRef"
        :model="formData"
        :rules="rules"
        label-width="130px"
      >
        <!-- 气体类型选择 -->
        <el-form-item label="气体类型" prop="gas">
          <el-select
            v-model="formData.gas"
            placeholder="请选择气体类型"
            class="qc-audit-select"
            @change="onGasTypeChange"
          >
            <el-option
              v-for="(item, index) in gasOptions"
              :key="index"
              :label="item"
              :value="item"
            />
          </el-select>
        </el-form-item>

        <!-- 稳定气体时间 -->
        <el-form-item label="稳定气体时间" prop="genGasTime">
          <el-input-number
            v-model="formData.genGasTime"
            :min="0"
            :max="9600"
            :step="10"
            class="qc-audit-number"
            @change="validateRange('genGasTime')"
          />
          <span class="qc-audit-unit">秒</span>
        </el-form-item>

        <!-- 读取数据次数 -->
        <el-form-item label="读取数据次数" prop="readDataCount">
          <el-input-number
            v-model="formData.readDataCount"
            :min="0"
            :max="50"
            :step="1"
            class="qc-audit-number"
            @change="validateRange('readDataCount')"
          />
          <span class="qc-audit-unit">次</span>
        </el-form-item>

        <!-- 读取时间间隔 -->
        <el-form-item label="读取时间间隔" prop="readDataSpan">
          <el-input-number
            v-model="formData.readDataSpan"
            :min="0"
            :max="600"
            :step="1"
            class="qc-audit-number"
            @change="validateRange('readDataSpan')"
          />
          <span class="qc-audit-unit">秒</span>
        </el-form-item>

        <!-- 目标流量 -->
        <el-form-item label="目标流量" prop="targetFlowLpm">
          <el-input-number
            v-model="formData.targetFlowLpm"
            :min="0.1"
            :max="20"
            :step="0.1"
            :precision="2"
            class="qc-audit-number"
          />
          <span class="qc-audit-unit">L/min</span>
        </el-form-item>

        <!-- 气体浓度：始终以 ppm 为单位录入与提交（CO 上限 50 ppm，其余 0.5 ppm，预览区展示 ppb 折算值） -->
        <el-form-item label="气体浓度" prop="genGasConc">
          <el-input-number
            v-model="formData.genGasConc"
            :min="0"
            :max="getMaxConcentration()"
            :step="getConcentrationStep()"
            :precision="getConcentrationPrecision()"
            class="qc-audit-number"
            @change="validateRange('genGasConc')"
          />
          <span class="qc-audit-unit">ppm</span>
        </el-form-item>

        <!-- 标气入口：跨度口 / 采样口（悬停提示气路说明） -->
        <el-form-item label="标气入口" prop="stdGasInPortName">
          <el-radio-group v-model="formData.stdGasInPortName">
            <el-tooltip
              v-for="(item, index) in stdGasInPortOptions"
              :key="index"
              :content="item.tooltip"
              placement="top"
            >
              <el-radio :value="item.value">{{ item.label }}</el-radio>
            </el-tooltip>
          </el-radio-group>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" @click="submitForm">确认配置</el-button>
          <el-button @click="resetForm">
            <el-icon class="qc-audit-btn-icon"><Refresh /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 参数预览：确认配置后出现，确认无误后在此执行 -->
    <el-card v-if="showPreview" ref="previewCardRef" class="qc-audit-preview-card">
      <template #header>
        <span>参数预览</span>
      </template>

      <el-row :gutter="16">
        <!-- 气体类型 -->
        <el-col :xs="24" :sm="12" :md="8">
          <div class="qc-audit-preview-item">
            <div class="qc-audit-preview-label">气体类型</div>
            <div class="qc-audit-preview-value">
              {{ formData.gas }}
              <el-tag :type="getGasTagType(formData.gas)">{{ formData.gas }}</el-tag>
            </div>
          </div>
        </el-col>

        <!-- 稳定气体时间 -->
        <el-col :xs="24" :sm="12" :md="8">
          <div class="qc-audit-preview-item">
            <div class="qc-audit-preview-label">稳定气体时间</div>
            <div class="qc-audit-preview-value">
              {{ formData.genGasTime }} 秒
              <el-icon class="qc-audit-icon-blue"><Timer /></el-icon>
            </div>
          </div>
        </el-col>

        <!-- 读取数据次数 -->
        <el-col :xs="24" :sm="12" :md="8">
          <div class="qc-audit-preview-item">
            <div class="qc-audit-preview-label">读取数据次数</div>
            <div class="qc-audit-preview-value">
              {{ formData.readDataCount }} 次
              <el-icon class="qc-audit-icon-green"><DataAnalysis /></el-icon>
            </div>
          </div>
        </el-col>

        <!-- 读取时间间隔 -->
        <el-col :xs="24" :sm="12" :md="8">
          <div class="qc-audit-preview-item">
            <div class="qc-audit-preview-label">读取时间间隔</div>
            <div class="qc-audit-preview-value">
              {{ formData.readDataSpan }} 秒
              <el-icon class="qc-audit-icon-purple"><QuartzWatch /></el-icon>
            </div>
          </div>
        </el-col>

        <!-- 气体浓度 -->
        <el-col :xs="24" :sm="12" :md="8">
          <div class="qc-audit-preview-item">
            <div class="qc-audit-preview-label">气体浓度</div>
            <div class="qc-audit-preview-value qc-audit-preview-value--stack">
              <span>{{ getDisplayConcentration() }}</span>
              <el-progress
                :percentage="getConcentrationPercentage()"
                :color="getProgressColor(getConcentrationPercentage())"
                :stroke-width="10"
              />
            </div>
          </div>
        </el-col>

        <!-- 标气入口 -->
        <el-col :xs="24" :sm="12" :md="8">
          <div class="qc-audit-preview-item">
            <div class="qc-audit-preview-label">标气入口</div>
            <div class="qc-audit-preview-value">
              {{ formData.stdGasInPortName }}
            </div>
          </div>
        </el-col>

        <!-- 目标流量 -->
        <el-col :xs="24" :sm="12" :md="8">
          <div class="qc-audit-preview-item">
            <div class="qc-audit-preview-label">目标流量</div>
            <div class="qc-audit-preview-value">
              {{ formData.targetFlowLpm }} L/min
              <el-icon class="qc-audit-icon-cyan"><Odometer /></el-icon>
            </div>
          </div>
        </el-col>
      </el-row>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="请确认参数设置，点击下方按钮开始质控检查"
      />
      <div class="qc-audit-execute-bar">
        <el-button type="primary" @click="startCheck">
          <el-icon class="qc-audit-btn-icon"><VideoPlay /></el-icon>
          确认并开始
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick } from 'vue';
import { ElMessage, ElLoading } from 'element-plus';
import {
  Refresh, Timer, DataAnalysis, QuartzWatch, VideoPlay, Odometer
} from '@element-plus/icons-vue';
import { executeAuditSpanCheck } from '@/api/quality_control/audit_span_check';

// 表单数据
const formData = reactive({
  gas: 'SO2',
  genGasTime: 600,
  readDataCount: 10,
  readDataSpan: 5,
  genGasConc: 0.2,
  /** 与后端 execution_log.params.targetFlowLpm 一致，默认 4 L/min */
  targetFlowLpm: 4,
  /** 默认跨度口（与调度任务历史「跨度检查」同义，入库前由后端规范为「跨度口」） */
  stdGasInPortName: '跨度口'
});

// 表单引用
const formRef = ref(null);

// 预览卡片引用：确认配置后平滑滚动定位用
const previewCardRef = ref(null);

// 标气入口选项
const stdGasInPortOptions = [
  { label: '跨度口', value: '跨度口', tooltip: '标气经跨度气路通入（原「跨度检查」）' },
  { label: '采样口', value: '采样口', tooltip: '标气经采样气路通入（原「测量」）' }
];

// 气体选项
const gasOptions = ['SO2', 'NO2', 'O3', 'CO'];

// 是否显示参数预览
const showPreview = ref(false);

// 表单验证规则
const rules = reactive({
  gas: [
    { required: true, message: '请选择气体类型', trigger: 'change' }
  ],
  genGasTime: [
    { required: true, message: '请输入气体时间', trigger: 'blur' },
    { type: 'number', min: 0, max: 9600, message: '气体时间范围为 0-9600 秒', trigger: 'change' }
  ],
  readDataCount: [
    { required: true, message: '请输入读取数据次数', trigger: 'blur' },
    { type: 'number', min: 0, max: 1000, message: '读取数据次数范围为 0-50', trigger: 'change' }
  ],
  readDataSpan: [
    { required: true, message: '请输入读取时间间隔', trigger: 'blur' },
    { type: 'number', min: 0, max: 3600, message: '读取时间间隔范围为 0-600 秒', trigger: 'change' }
  ],
  genGasConc: [
    { required: true, message: '请输入气体浓度', trigger: 'blur' },
    { type: 'number', min: 0, max: 0.5, message: '气体浓度超出允许范围', trigger: 'change' }
  ],
  targetFlowLpm: [
    { required: true, message: '请输入目标流量', trigger: 'blur' },
    { type: 'number', min: 0.1, max: 20, message: '目标流量范围为 0.1～20 L/min', trigger: 'change' }
  ],
  stdGasInPortName: [
    { required: true, message: '请选择标气入口', trigger: 'change' }
  ]
});

// 气体类型变化时更新浓度范围
const onGasTypeChange = () => {
  // 更新验证规则中的最大值
  rules.genGasConc[1].max = getMaxConcentration();
  // 重置表单验证
  formRef.value.clearValidate('genGasConc');
};

// 获取浓度最大值
const getMaxConcentration = () => {
  return formData.gas === 'CO' ? 50 : 0.5; // CO为50ppm，其他为0.5ppm(500ppb)
};

// 获取浓度显示值（ppm 折算为 ppb 展示，非 CO 气体以 ppb 为惯用单位）
const getDisplayConcentration = () => {
  return formData.gas === 'CO'
    ? `${formData.genGasConc} ppm`
    : `${formData.genGasConc * 1000} ppb`;
};

// 获取浓度百分比
const getConcentrationPercentage = () => {
  const max = formData.gas === 'CO' ? 50 : 0.5;
  return Math.min((formData.genGasConc / max) * 100, 100);
};

// 获取浓度输入精度
const getConcentrationPrecision = () => {
  return formData.gas === 'CO' ? 1 : 3; // CO显示1位小数，其他显示3位
};

// 获取浓度输入步长
const getConcentrationStep = () => {
  return formData.gas === 'CO' ? 0.1 : 0.001; // CO步长0.1ppm，其他0.001ppm(1ppb)
};

// 表单提交（此处仅校验并展示预览，真正执行在预览区「确认并开始」）
const submitForm = () => {
  formRef.value.validate((valid) => {
    if (valid) {
      showPreview.value = true;
      // 预览卡片由 v-if 渲染，nextTick 后 DOM 就绪再平滑滚动到位
      nextTick(() => {
        const previewEl = previewCardRef.value && previewCardRef.value.$el;
        if (previewEl) {
          previewEl.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
      });
    } else {
      console.log('表单验证失败');
      return false;
    }
  });
};

// 重置表单
const resetForm = () => {
  formRef.value.resetFields();
  showPreview.value = false;
};

// 开始检查
const startCheck = async () => {
  let loadingInstance;
  try {
    // 须与外层共用同一变量，否则 finally 拿不到实例，reject（如业务码 500）时遮罩无法关闭
    loadingInstance = ElLoading.service({
      lock: true,
      text: '正在提交质控检查请求...',
      spinner: 'el-icon-loading',
      background: 'rgba(0, 0, 0, 0.05)'
    });

    // 调用执行接口；浓度字段始终以 ppm 为单位提交，由后端负责 ppb 折算
    const response = await executeAuditSpanCheck(formData);

    console.log('API响应:', response);
    if (response.code === 200) {
      // 处理API响应
      if (response.data) {
        ElMessage({
          type: 'success',
          message: '质控检查已成功提交',
          duration: 3000
        });
      } else {
        ElMessage({
          type: 'error',
          message: `质控检查提交失败: ${response.message || '未知错误'}`,
          duration: 5000
        });
      }
    } else {
      ElMessage({
        type: 'error',
        message: `质控检查提交失败: ${response.message || '未知错误'}`,
        duration: 5000
      });
    }
  } catch (error) {
    // 处理网络错误
    console.error('API请求错误:', error);
    ElMessage({
      type: 'error',
      message: '网络请求失败，请检查网络连接',
      duration: 5000
    });
  } finally {
    // 无论成功或失败，强制关闭加载状态
    if (loadingInstance) {
      loadingInstance.close();
    }
    // 无论成功或失败，都重置表单并隐藏预览
    showPreview.value = false;
    // 确保formRef存在且已初始化
    if (formRef.value) {
      formRef.value.resetFields();
    }
  }
};

// 手动验证数值范围：el-form 无 setFieldsError API（原实现越界时必抛 TypeError），
// 直接触发该字段已声明的规则校验——范围与提示文案与 rules 完全同源，越界自动红字，合规自动清除
const validateRange = (field) => {
  formRef.value?.validateField(field).catch(() => {
    // 校验不通过走 el-form 自身红字提示，这里只拦 Promise 拒绝避免未处理异常
  });
};

// 获取气体标签类型
const getGasTagType = (gas) => {
  const typeMap = {
    'SO2': 'success',
    'NO2': 'info',
    'O3': 'warning',
    'CO': 'danger'
  };
  return typeMap[gas] || 'default';
};

// 获取进度条颜色（7个梯度，从浅绿到深绿）
const getProgressColor = (percentage) => {
  const colorStops = [
    { percentage: 0, color: '#f0f9eb' },    // 极浅绿
    { percentage: 10, color: '#e6f7ed' },   // 浅绿
    { percentage: 20, color: '#d9f7be' },   // 浅绿偏黄
    { percentage: 40, color: '#b7eb8f' },   // 中绿
    { percentage: 60, color: '#95de64' },   // 中绿偏深
    { percentage: 80, color: '#73d13d' },   // 深绿
    { percentage: 100, color: '#52c41a' }   // 极深绿
  ];
  // 找到当前百分比所在的区间
  for (let i = 0; i < colorStops.length - 1; i++) {
    if (percentage >= colorStops[i].percentage && percentage < colorStops[i + 1].percentage) {
      return colorStops[i].color;
    }
  }
  // 超过80%的情况
  return colorStops[colorStops.length - 1].color;
};

// 页面加载完成后聚焦第一个输入框
onMounted(() => {
  setTimeout(() => {
    const firstInput = document.querySelector('.el-input__inner');
    if (firstInput) {
      firstInput.focus();
    }
  }, 300);
});
</script>

<style scoped>
/* 数值输入统一宽度，单位后缀紧随其后（ruoyi 默认尺寸表单） */
.qc-audit-select {
  width: 320px;
}

.qc-audit-number {
  width: 200px;
}

.qc-audit-unit {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}

/* 预览卡片与表单卡片的间距 */
.qc-audit-preview-card {
  margin-top: 16px;
}

/* 预览项小卡：浅底圆角（原 tailwind 装饰的 scoped CSS 等价实现） */
.qc-audit-preview-item {
  padding: 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  height: 100%;
}

.qc-audit-preview-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.qc-audit-preview-value {
  margin-top: 4px;
  font-size: 16px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  display: flex;
  align-items: center;
}

/* 浓度项值与进度条上下排布 */
.qc-audit-preview-value--stack {
  display: block;
}

.qc-audit-preview-value--stack .el-progress {
  margin-top: 8px;
}

.qc-audit-preview-value .el-tag {
  margin-left: 8px;
}

.qc-audit-preview-value .el-icon {
  margin-left: 8px;
}

/* 执行入口右对齐，按钮内图标与文字间距 */
.qc-audit-execute-bar {
  margin-top: 16px;
  text-align: right;
}

.qc-audit-btn-icon {
  margin-right: 6px;
}

/* 预览项装饰配色（沿用原 tailwind 色板值） */
.qc-audit-icon-blue {
  color: #3b82f6;
}

.qc-audit-icon-green {
  color: #22c55e;
}

.qc-audit-icon-purple {
  color: #a855f7;
}

.qc-audit-icon-cyan {
  color: #06b6d4;
}
</style>
