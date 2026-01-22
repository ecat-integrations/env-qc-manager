<template>
  <div class="q-control-page tw:min-h-screen tw:bg-gray-50 tw:p-6">
    <div class="tw:max-w-4xl tw:mx-auto">
      <!-- 页面标题 -->
      <header class="tw:mb-8">
        <h1 class="tw:text-[clamp(1.5rem,3vw,2.5rem)] tw:font-bold tw:text-gray-800">
          <el-icon class="tw:text-blue-500 tw:mr-2">
            <Setting />
          </el-icon>
          自定义跨度检查
        </h1>
        <p class="tw:text-gray-600 mt-2">配置气体参数并执行质控检查</p>
      </header>

      <!-- 参数配置卡片 -->
      <el-card class="tw:rounded-xl tw:shadow-lg tw:border-0 tw:transition-all tw:duration-300 tw:hover:shadow-xl">
        <template #header>
          <div class="tw:flex tw:items-center">
            <el-icon class="tw:text-blue-500 tw:mr-2">
              <Edit />
            </el-icon>
            <h3 class="tw:text-lg tw:font-semibold">参数配置</h3>
          </div>
        </template>

        <el-form
          ref="formRef"
          :model="formData"
          :rules="rules"
          label-width="160px"
          class="tw:space-y-4"
        >
          <!-- 气体类型选择 -->
          <el-form-item label="气体类型" prop="gas">
            <el-select
              v-model="formData.gas"
              placeholder="请选择气体类型"
              class="tw:w-full"
              size="large"
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

          <!-- 气体时间设置 -->
          <el-form-item label="稳定气体时间(秒)" prop="genGasTime">
            <div class="tw:flex tw:items-center">
              <el-input-number
                v-model="formData.genGasTime"
                :min="0"
                :max="9600"
                :step="10"
                size="large"
                class="tw:w-full"
                @change="validateRange('genGasTime')"
              />
              <span class="tw:ml-2 tw:text-gray-500">秒</span>
            </div>
          </el-form-item>

          <!-- 读取数据次数 -->
          <el-form-item label="读取数据次数(次)" prop="readDataCount">
            <div class="tw:flex tw:items-center">
              <el-input-number
                v-model="formData.readDataCount"
                :min="0"
                :max="50"
                size="large"
                class="tw:w-full"
                @change="validateRange('readDataCount')"
              />
              <span class="tw:ml-2 tw:text-gray-500">次</span>
            </div>
          </el-form-item>

          <!-- 读取时间间隔 -->
          <el-form-item label="读取时间间隔(秒)" prop="readDataSpan">
            <div class="tw:flex tw:items-center">
              <el-input-number
                v-model="formData.readDataSpan"
                :min="0"
                :max="600"
                :step="1"
                size="large"
                class="tw:flex-1"
                @change="validateRange('readDataSpan')"
              />
              <span class="tw:ml-2 tw:text-gray-500">秒</span>
            </div>
          </el-form-item>

          <!-- 气体浓度 -->
          <el-form-item label="气体浓度(ppm)" prop="genGasConc">
            <div class="tw:flex tw:items-center">
              <el-input-number
                v-model="formData.genGasConc"
                :min="0"
                :max="getMaxConcentration()"
                :step="getConcentrationStep()"
                :precision="getConcentrationPrecision()"
                size="large"
                class="tw:w-full"
                @change="validateRange('genGasConc')"
              />
              <span class="tw:ml-2 tw:text-gray-500">ppm</span>
            </div>
          </el-form-item>

          <!-- 标气入口选择 -->
          <el-form-item label="标气入口" prop="stdGasInPortName">
            <el-radio-group v-model="formData.stdGasInPortName">
              <el-tooltip
                v-for="(item, index) in stdGasInPortOptions"
                :key="index"
                :content="item.tooltip"
                placement="top"
              >
                <el-radio :label="item.value" class="tw-mr-4 tw-py-1 tw-px-2 hover:tw-bg-gray-100 tw-rounded-md">
                  {{ item.label }}
                </el-radio>
              </el-tooltip>
            </el-radio-group>
          </el-form-item>

          <!-- 操作按钮 -->
          <el-form-item class="tw:mt-6 tw:flex tw:justify-end tw:space-x-4">
            <el-button
              type="primary"
              size="large"
              class="tw:px-8 tw:bg-blue-500 hover:tw:bg-blue-600 tw:transition-colors"
              @click="submitForm"
            >
              <el-icon class="tw:mr-2">
                <Check />
              </el-icon>
              确认配置
            </el-button>
            <el-button
              size="large"
              class="tw:px-8 tw:bg-gray-200 hover:tw:bg-gray-300 tw:transition-colors"
              @click="resetForm"
            >
              <el-icon class="tw:mr-2">
                <Refresh />
              </el-icon>
              重置
            </el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <!-- 参数预览卡片 -->
      <el-card class="tw:rounded-xl tw:shadow-lg tw:border-0 tw:mt-6" v-if="showPreview">
        <template #header>
          <div class="tw:flex tw:items-center">
            <el-icon class="tw:text-green-500 tw:mr-2">
              <Document />
            </el-icon>
            <h3 class="tw:text-lg tw:font-semibold">参数预览</h3>
          </div>
        </template>

        <!-- 参数排列布局 -->
        <div class="tw:flex tw:flex-wrap tw:-mx-3 tw:mt-2">
          <!-- 气体类型 -->
          <div class="tw:w-full sm:tw:w-1/2 md:tw:w-1/5 tw:px-3 tw:mb-4">
            <div class="tw:p-3 tw:bg-gray-50 tw:rounded-lg tw:h-full">
              <p class="tw:text-sm tw:text-gray-500">气体类型</p>
              <p class="tw:text-lg tw:font-medium tw:text-gray-800">
                {{ formData.gas }}
                <el-tag :type="getGasTagType(formData.gas)" class="tw:ml-2">
                  {{ formData.gas }}
                </el-tag>
              </p>
            </div>
          </div>

          <!-- 气体时间 -->
          <div class="tw:w-full sm:tw:w-1/2 md:tw:w-1/5 tw:px-3 tw:mb-4">
            <div class="tw:p-3 tw:bg-gray-50 tw:rounded-lg tw:h-full">
              <p class="tw:text-sm tw:text-gray-500">稳定气体时间</p>
              <p class="tw:text-lg tw:font-medium tw:text-gray-800">
                {{ formData.genGasTime }} 秒
                <el-icon class="tw:text-blue-500 tw:ml-2">
                  <Timer />
                </el-icon>
              </p>
            </div>
          </div>

          <!-- 读取数据次数 -->
          <div class="tw:w-full sm:tw:w-1/2 md:tw:w-1/5 tw:px-3 tw:mb-4">
            <div class="tw:p-3 tw:bg-gray-50 tw:rounded-lg tw:h-full">
              <p class="tw:text-sm tw:text-gray-500">读取数据次数</p>
              <p class="tw:text-lg tw:font-medium tw:text-gray-800">
                {{ formData.readDataCount }} 次
                <el-icon class="tw:text-green-500 tw:ml-2">
                  <DataAnalysis />
                </el-icon>
              </p>
            </div>
          </div>

          <!-- 读取时间间隔 -->
          <div class="tw:w-full sm:tw:w-1/2 md:tw:w-1/5 tw:px-3 tw:mb-4">
            <div class="tw:p-3 tw:bg-gray-50 tw:rounded-lg tw:h-full">
              <p class="tw:text-sm tw:text-gray-500">读取时间间隔</p>
              <p class="tw:text-lg tw:font-medium tw:text-gray-800">
                {{ formData.readDataSpan }} 秒
                <el-icon class="tw:text-purple-500 tw:ml-2">
                  <QuartzWatch />
                </el-icon>
              </p>
            </div>
          </div>

          <!-- 气体浓度 -->
          <div class="tw:w-full sm:tw:w-1/2 md:tw:w-1/5 tw:px-3 tw:mb-4">
            <div class="tw:p-3 tw:bg-gray-50 tw:rounded-lg tw:h-full">
              <p class="tw:text-sm tw:text-gray-500">气体浓度</p>
              <p class="tw:text-lg tw:font-medium tw:text-gray-800">
                {{ getDisplayConcentration() }}
                <el-progress
                  :percentage="getConcentrationPercentage()"
                  :color="getProgressColor(getConcentrationPercentage())"
                  :stroke-width="10"
                  class="tw:mt-2 tw:w-full"
                />
              </p>
            </div>
          </div>

          <!-- 标气入口 -->
          <div class="tw:w-full sm:tw:w-1/2 md:tw:w-1/5 tw:px-3 tw:mb-4">
            <div class="tw:p-3 tw:bg-gray-50 tw:rounded-lg tw:h-full">
              <p class="tw:text-sm tw:text-gray-500">标气入口</p>
              <p class="tw:text-lg tw:font-medium tw:text-gray-800">
                {{ formData.stdGasInPortName }}
              </p>
            </div>
          </div>
        </div>

        <div class="tw:p-4 tw:mt-4 tw:bg-blue-50 tw:rounded-lg">
          <el-icon class="tw:text-blue-500">
            <InfoFilled />
          </el-icon>
          <span class="tw:ml-2 tw:text-blue-700">请确认参数设置，点击下方按钮开始质控检查</span>
          <div class="tw:flex tw:justify-end tw:mt-4">
            <el-button
              type="primary"
              size="large"
              class="tw:px-8"
              @click="startCheck"
            >
              <el-icon class="tw:mr-2">
                <VideoPlay />
              </el-icon>
              确认并开始
            </el-button>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue';
import { ElMessage, ElLoading} from 'element-plus';
import {
  Setting, Edit, Check, Refresh, Document, Timer,
  QuartzWatch, DataAnalysis, InfoFilled, VideoPlay
} from '@element-plus/icons-vue';
import {executeAuditSpanCheck} from "@/api/quality_control/audit_span_check";

// 表单数据
const formData = reactive({
  gas: 'SO2',
  genGasTime: 600,
  readDataCount: 10,
  readDataSpan: 5,
  genGasConc: 0.2
});

// 表单引用
const formRef = ref(null);

// 标气入口选项
const stdGasInPortOptions = [
  { label: '跨度检查', value: '跨度检查' },
  { label: '测量', value: '测量' }
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
    { type: 'number', min: 0, message: '气体浓度不能小于 0', trigger: 'change' }
  ]
});

// 气体类型变化时更新浓度范围
const onGasTypeChange = () => {
  // 更新验证规则中的最大值
  rules.genGasConc[1].max = getMaxConcentration();
  // 重置表单验证
  formRef.value.clearValidate('genGasConc');
};

// 获取浓度单位
const getConcentrationUnit = () => {
  return formData.gas === 'CO' ? 'ppm' : 'ppb';
};

// 获取浓度最大值
const getMaxConcentration = () => {
  return formData.gas === 'CO' ? 50 : 0.5; // CO为50ppm，其他为0.5ppm(500ppb)
};

// 获取浓度显示值
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

// 表单提交
const submitForm = () => {
  formRef.value.validate((valid) => {
    if (valid) {
      showPreview.value = true;
      // 滚动到预览区域
      setTimeout(() => {
        const previewCard = document.querySelector('.el-card:nth-child(2)');
        if (previewCard) {
          previewCard.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
      }, 300);
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
  // 声明加载实例变量
  let loadingInstance;
  try {
    // 显示加载状态
    const loadingInstance = ElLoading.service({
      lock: true,
      text: '正在提交质控检查请求...',
      spinner: 'el-icon-loading',
      background: 'rgba(0, 0, 0, 0.05)'
    });

    // formData.gas,
    // formData.genGasTime,
    // formData.readDataCount,
    // formData.readDataSpan,
    // formData.genGasConc // 始终以ppm为单位传递
    // 调用实际的API接口（注意：这里传递的是ppm单位的值）
    const response = await executeAuditSpanCheck(formData);

    // 关闭加载状态
    loadingInstance.close();

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

// 手动验证数值范围
const validateRange = (field) => {
  const value = formData[field];
  const rule = rules[field][1];

  if (rule && value !== undefined && value !== null) {
    if (value < rule.min || (rule.max !== undefined && value > rule.max)) {
      formRef.value.setFieldsError({
        [field]: `${rule.message}`
      });
    } else {
      formRef.value.clearValidate(field);
    }
  }
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
/* 自定义样式 */
.q-control-page {
  background: linear-gradient(135deg, #f8fafc 0%, #e2e8f0 100%);
}

/* 表单元素样式优化 */
.el-form-item__label {
  font-weight: 500;
  color: #334155;
}

.el-input-number {
  max-width: 200px;
}

/* 卡片悬停效果 */
.el-card {
  transition: all 0.3s ease;
}

.el-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
}

/* 按钮样式优化 */
.el-button {
  border-radius: 0.5rem;
  font-weight: 500;
}

/* 响应式布局 */
@media (max-width: 768px) {
  .el-form-item__content {
    margin-left: 0 !important;
  }

  .el-input-number {
    max-width: 100%;
  }
}
</style>
