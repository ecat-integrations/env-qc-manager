<template>
  <div class="qc:min-h-screen qc:bg-gray-50">

    <!-- Header Section -->
    <div class="qc:bg-gradient-to-r qc:from-blue-300 qc:to-blue-400 qc:text-white qc:py-0 qc:px-6 qc:shadow-md qc:mb-6">
      <div class="qc:max-w-6xl qc:mx-auto qc:flex qc:justify-between qc:items-center">
        <h1 class="qc:text-2xl qc:font-bold qc:flex qc:items-center gap-2">
          <el-icon name="GasStation" class="qc:mr-2" />
          钢瓶气管理
        </h1>
        <button
          class="!rounded-button whitespace-nowrap qc:px-4 qc:py-2 qc:flex qc:items-center qc:gap-2 qc:bg-white qc:text-blue-600 qc:hover:bg-blue-50 qc:transition-colors qc:rounded-lg qc:shadow-sm qc:hover:shadow"
          @click="refreshGas"
        >
          <el-icon><Search /></el-icon>
          <span>刷新状态</span>
        </button>
      </div>
    </div>

    <!-- Main Content -->
    <div class="qc:max-w-6xl qc:mx-auto qc:py-4 qc:px-6">
      <!-- Empty State -->
      <div
        v-if="gasCylinders.length === 0"
        class="qc:flex qc:flex-col qc:items-center qc:justify-center qc:h-96 qc:bg-white qc:rounded-lg qc:shadow-sm qc:border qc:border-gray-200 qc:transition-all qc:hover:shadow-md"
      >
        <el-icon class="qc:text-gray-400 qc:mb-4" size="60"><Box /></el-icon>
        <p class="qc:text-gray-500 qc:mb-6">暂无钢瓶气数据，请点击添加</p>
        <button
          class="!rounded-button whitespace-nowrap qc:bg-gradient-to-r qc:from-blue-500 qc:to-blue-600 qc:text-white qc:px-6 qc:py-3 qc:flex qc:items-center gap-2 qc:hover:from-blue-600 qc:hover:to-blue-700 qc:transition-all"
          @click="addGasCylinder"
        >
          <el-icon><Plus /></el-icon>
          <span>添加钢瓶气</span>
        </button>
      </div>

      <!-- Gas Cylinder List -->
      <div v-else class="qc:space-y-4">
        <div
          v-for="(cylinder, index) in gasCylinders"
          :key="index"
          class="qc:bg-white qc:rounded-lg qc:shadow-sm qc:border qc:border-gray-200 qc:p-6 qc:transition-all qc:duration-300 qc:hover:shadow-md qc:hover:transform qc:hover:-translate-y-1"
        >
          <div class="qc:flex qc:justify-between qc:items-center">
            <div class="qc:flex qc:items-center qc:gap-4">
                             <!-- 钢瓶图标 -->
               <div class="qc:flex-shrink-0">
                 <GasCylinderIcon
                   :size="48" 
                   :color="getCylinderColor(cylinder.concentration)"
                   :gasType="getGasType(cylinder.name)"
                   class="qc:drop-shadow-sm"
                 />
               </div>
              
              <div>
                <h3 class="qc:text-lg qc:font-semibold qc:text-gray-800 qc:flex qc:items-center gap-2">
                  {{ cylinder.name }}
                  <!--<span class="qc:text-xs qc:px-2 qc:py-0.5 qc:rounded qc:bg-blue-100 qc:text-blue-600">{{ cylinder.id }}</span>-->
                </h3>
              </div>
            </div>

            <div class="qc:flex qc:items-center qc:gap-10">
              <div class="qc:text-center">
                <p class="qc:text-sm qc:text-gray-500">当前浓度</p>
                <p 
                  class="qc:text-2xl qc:font-bold qc:text-blue-600"
                  :class="{
                    'qc:text-yellow-500': cylinder.concentration >= 50 && cylinder.concentration < 70,
                    'qc:text-red-500': cylinder.concentration < 50
                  }"
                >
                  {{ cylinder.concentration }} {{ cylinder.unit || 'ppm' }}
                </p>
              </div>


              <button
                class="!rounded-button whitespace-nowrap qc:bg-gradient-to-r qc:from-gray-100 qc:to-gray-200 qc:text-gray-700 qc:px-4 qc:py-2 qc:flex qc:items-center qc:gap-2 qc:hover:from-gray-200 qc:hover:to-gray-300 qc:transition-all qc:rounded-lg"
                @click="openConcentrationDialog(index)"
              >
                <el-icon><Edit /></el-icon>
                <span>设置浓度</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Concentration Setting Dialog -->
    <el-dialog
      v-model="showConcentrationDialog"
      :title="`设置 ${editingCylinder?.name}`"
      width="400px"
      :close-on-click-modal="false"
    >
      <div class="qc:space-y-4">
        <div>
          <label class="qc:block qc:text-sm qc:font-medium qc:text-gray-700 qc:mb-1">
            浓度值
          </label>
          <el-input
            v-model.number="editingConcentration"
            type="number"
            :placeholder="`请输入浓度值，单位：${editingCylinder?.unit || 'ppm'}`"
            :min="0"
            class="qc:w-full"
          >
            <template #suffix>
              <span class="qc:text-gray-500 qc:pr-2">{{ editingCylinder?.unit || 'ppm' }}</span>
            </template>
          </el-input>
          <p class="qc:text-xs qc:text-gray-500 qc:mt-1">
            请输入浓度值，单位：{{ editingCylinder?.unit || 'ppm' }}
          </p>
        </div>
      </div>

      <template #footer>
        <div class="qc:flex qc:justify-end qc:gap-4">
          <button
            class="!rounded-button whitespace-nowrap qc:px-4 qc:py-2 qc:bg-gradient-to-r qc:from-gray-100 qc:to-gray-200 qc:text-gray-700 qc:hover:from-gray-200 qc:hover:to-gray-300 qc:transition-all qc:rounded-lg"
            @click="showConcentrationDialog = false"
          >
            取消
          </button>
          <button
            class="!rounded-button whitespace-nowrap qc:px-4 qc:py-2 qc:bg-gradient-to-r qc:from-blue-500 qc:to-blue-600 qc:text-white qc:hover:from-blue-600 qc:hover:to-blue-700 qc:transition-all qc:rounded-lg"
            @click="saveConcentration"
          >
            确认
          </button>
        </div>
      </template>
    </el-dialog>

    <!-- Success Toast -->
    <div
      v-if="showSuccessToast"
      class="fixed top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2 qc:bg-green-500 qc:text-white qc:px-6 qc:py-3 qc:rounded-md qc:shadow-lg z-50 qc:animate-pulse"
    >
      <div class="qc:flex qc:items-center gap-2">
        <el-icon><CircleCheck /></el-icon>
        <span>浓度设置成功</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { listGasSetting, updateGasSetting } from "@/api/quality_control/gas_setting";
import { ref, reactive } from 'vue';
import { Search, Edit, CircleCheck, Box, Plus } from '@element-plus/icons-vue';
import GasCylinderIcon from '../components/GasCylinderIcon.vue';

const gasCylinders = reactive([
  {
    id: 'gas_co_tank_conc',
    deviceId: 'sms-calib',
    name: '一氧化碳钢气瓶',
    concentration: 78,
    unit: 'ppb',
    gases: [
      "CO"
    ]
  },
  {
    id: 'gas_no_tank_conc',
    deviceId: 'sms-calib',
    name: '氮氧化物钢气瓶',
    concentration: 65,
    unit: 'ppb',
    gases: [
      "NO2",
      "NO"
    ]
  },
  {
    id: 'gas_so2_tank_conc',
    deviceId: 'sms-calib',
    name: '二氧化硫钢气瓶',
    concentration: 92,
    unit: 'ppb',
    gases: [
      "SO2"
    ]
  }
]);

const showConcentrationDialog = ref(false);
const editingCylinderIndex = ref(-1);
const editingCylinder = ref(null);
const editingConcentration = ref(0);
const showSuccessToast = ref(false);
const { proxy } = getCurrentInstance();
getList()
/** 查询质控记录列表 */
function getList() {
  listGasSetting().then(response => {
    console.log(response)
    gasCylinders.splice(0, gasCylinders.length, ...response.rows);
  });
}
// 设置每30秒执行一次
setInterval(() => {
  getList();
}, 30000); // 30000毫秒 = 30秒
const refreshGas = () => {
  // 模拟刷新设备状态
  console.log('刷新浓度状况');
  getList()
  proxy.$modal.msgSuccess("刷新浓度状况");
};

// 根据浓度值返回钢瓶颜色
const getCylinderColor = (concentration) => {
  if (concentration >= 70) {
    return 'green'; // 正常 - 绿色
  } else if (concentration >= 40) {
    return 'yellow'; // 警告 - 黄色
  } else {
    return 'red'; // 危险 - 红色
  }
};

// 根据钢瓶名称提取气体类型
const getGasType = (cylinderName) => {
  if (cylinderName.includes('一氧化碳') || cylinderName.includes('CO')) {
    return 'CO';
  } else if (cylinderName.includes('氮氧化物') || cylinderName.includes('NO')) {
    return 'NO₂';
  } else if (cylinderName.includes('二氧化硫') || cylinderName.includes('SO')) {
    return 'SO₂';
  } else if (cylinderName.includes('臭氧') || cylinderName.includes('O₃')) {
    return 'O₃';
  } else if (cylinderName.includes('甲烷') || cylinderName.includes('CH₄')) {
    return 'CH₄';
  } else {
    // 如果没有匹配到，尝试从名称中提取前两个字符
    return cylinderName.substring(0, 2).toUpperCase();
  }
};
const openConcentrationDialog = (index) => {
  editingCylinderIndex.value = index;
  editingCylinder.value = { ...gasCylinders[index] };
  if(gasCylinders[index].concentration){
  editingConcentration.value = gasCylinders[index].concentration;
  }else{
    editingConcentration.value=0;
  }
  showConcentrationDialog.value = true;
};

const saveConcentration = () => {
  if (editingCylinderIndex.value >= 0 && editingCylinder.value) {
    // gasCylinders[editingCylinderIndex.value].concentration = editingConcentration.value;
    let updateData = {
      id: editingCylinder.value.id,
      value: editingConcentration.value
    }
    updateGasSetting(updateData).then(response => {
      proxy.$modal.msgSuccess(response.msg);
      getList();
    })

  }

  showConcentrationDialog.value = false;
};

const addGasCylinder = () => {
  const newId = `GC-${new Date().getFullYear()}-${(gasCylinders.length + 1).toString().padStart(3, '0')}`;
  gasCylinders.push({
    id: newId,
    name: `新钢瓶 ${gasCylinders.length + 1}`,
    concentration: 50,
    status: 'normal'
  });
};
</script>

<style scoped>
/* Custom styles for transitions */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.slide-enter-active,
.slide-leave-active {
  transition: all 0.3s ease;
}

.slide-enter-from,
.slide-leave-to {
  transform: translateX(20px);
  opacity: 0;
}

:deep(.el-button) {
  border-radius: 20px;
  padding: 10px 20px;
  transition: all 0.2s ease-in-out;
}
</style>
