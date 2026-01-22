<!--分析仪转换效率测试记录表 - 表 D.6-->
<template>
  <div class="report-d6">
    <h2 style="text-align: center;">{{ reportData.title }}</h2>
    <table class="report-table">
      <tbody>
      <!-- 表头信息 -->
      <tr>
        <td colspan="4">仪器名称及编号：{{ reportData.instrument_info.instrument_name_and_no }}</td>
        <td colspan="4">测试时间：{{ reportData.instrument_info.report_date }}</td>
      </tr>
      <tr>
        <td colspan="4">标气来源及编号：{{ reportData.instrument_info.gas_source }}</td>
        <td colspan="4">
          标气浓度：<br>
          NO₂：{{ reportData.instrument_info.no2_concentration || '-' }}<br>
          NO：{{ reportData.instrument_info.no_concentration || '-' }}
        </td>
      </tr>

      <!-- 使用NO2标气进行转换效率测试 -->
      <tr>
        <th rowspan="4" class="section-header">使用NO₂标气进行转换效率测试</th>
        <th colspan="3"></th>
        <th>第一次</th>
        <th>第二次</th>
        <th>第三次</th>
        <th>平均值</th>
      </tr>
      <tr>
        <td colspan="3">氮氧化物分析仪读数</td>
        <td>{{ getArrayValue(reportData.orig_no2_datas, 0) }}</td>
        <td>{{ getArrayValue(reportData.orig_no2_datas, 1) }}</td>
        <td>{{ getArrayValue(reportData.orig_no2_datas, 2) }}</td>
        <td>{{ reportData.orig_no2_avg || '-' }}</td>
      </tr>
      <tr>
        <td colspan="3">NO₂读数</td>
        <td>-</td>
        <td>-</td>
        <td>-</td>
        <td>-</td>
      </tr>
      <tr>
        <td colspan="3">转换效率(%)</td>
        <td>-</td>
        <td>-</td>
        <td>-</td>
        <td>{{ reportData.no2_efficiency || '-' }}</td>
      </tr>

      <!-- 使用NO标气进行转换效率测试 -->
      <tr>
        <th rowspan="6" class="section-header">使用NO标气进行转换效率测试</th>
        <th colspan="2">校准仪中 O₃ 开/关</th>
        <th>氮氧化物分析仪读数</th>
        <th>第一次</th>
        <th>第二次</th>
        <th>第三次</th>
        <th>平均值</th>
      </tr>
      <!-- O3关 -->
      <tr>
        <td rowspan="2" class="o3-status">关</td>
        <td colspan="2">[NO]<sub>orig</sub></td>
        <td>{{ getArrayValue(reportData.orig_no_datas, 0) }}</td>
        <td>{{ getArrayValue(reportData.orig_no_datas, 1) }}</td>
        <td>{{ getArrayValue(reportData.orig_no_datas, 2) }}</td>
        <td>{{ reportData.orig_no_avg || '-' }}</td>
      </tr>
      <tr>
        <td colspan="2">[NOx]<sub>orig</sub></td>
        <td>{{ getArrayValue(reportData.orig_nox_datas, 0) }}</td>
        <td>{{ getArrayValue(reportData.orig_nox_datas, 1) }}</td>
        <td>{{ getArrayValue(reportData.orig_nox_datas, 2) }}</td>
        <td>{{ reportData.orig_nox_avg || '-' }}</td>
      </tr>
      <!-- O3开 -->
      <tr>
        <td rowspan="2" class="o3-status">开</td>
        <td colspan="2">[NO]<sub>rem</sub></td>
        <td>{{ getArrayValue(reportData.rem_no_datas, 0) }}</td>
        <td>{{ getArrayValue(reportData.rem_no_datas, 1) }}</td>
        <td>{{ getArrayValue(reportData.rem_no_datas, 2) }}</td>
        <td>{{ reportData.rem_no_avg || '-' }}</td>
      </tr>
      <tr>
        <td colspan="2">[NOx]<sub>rem</sub></td>
        <td>{{ getArrayValue(reportData.rem_nox_datas, 0) }}</td>
        <td>{{ getArrayValue(reportData.rem_nox_datas, 1) }}</td>
        <td>{{ getArrayValue(reportData.rem_nox_datas, 2) }}</td>
        <td>{{ reportData.rem_nox_avg || '-' }}</td>
      </tr>
      <!-- 转换效率 -->
      <tr>
        <td colspan="3">转换效率(%)</td>
        <td>-</td>
        <td>-</td>
        <td>-</td>
        <td>{{ reportData.efficiency || '-' }}</td>
      </tr>

      <!-- 结果评价 -->
      <tr>
        <td colspan="8">
          结果评价：
          <label><input type="checkbox" :checked="isPass" disabled> 合格</label>
          <label><input type="checkbox" :checked="!isPass" disabled> 不合格</label>
        </td>
      </tr>
      <tr>
        <td colspan="8">备注：{{ reportData.result_evaluation || '' }}</td>
      </tr>
      <tr>
        <td colspan="4">填表人：{{ reportData.filer || '' }}</td>
        <td colspan="4">复核人：{{ reportData.reviewer || '' }}</td>
      </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import { defineProps, computed } from 'vue';

const props = defineProps({
  reportData: {
    type: Object,
    required: true
  }
});

// 辅助函数：安全获取数组值
const getArrayValue = (arr, index) => {
  if (Array.isArray(arr) && arr.length > index) {
    return arr[index] !== null && arr[index] !== undefined ? arr[index] : '-';
  }
  return '-';
};

// 判断是否合格
const isPass = computed(() => {
  const evaluation = props.reportData.result_evaluation || '';
  return evaluation.includes('合格') || evaluation.includes('通过') || evaluation.includes('成功');
});
</script>

<style scoped>
.report-d6 {
  font-family: "SimSun", "宋体", Arial, sans-serif;
  padding: 20px;
}

.report-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 10px;
}

.report-table th,
.report-table td {
  border: 1px solid #000;
  padding: 8px;
  text-align: center;
  vertical-align: middle;
}

.report-table th {
  background-color: #f0f0f0;
  font-weight: bold;
}

.report-table td {
  text-align: center;
}

/* 左对齐的单元格 */
.report-table tr:first-child td,
.report-table tr:nth-child(2) td {
  text-align: left;
}

/* 章节标题 */
.section-header {
  background-color: #e8e8e8;
  font-weight: bold;
  writing-mode: vertical-rl;
  text-orientation: upright;
  letter-spacing: 2px;
  padding: 10px 5px;
}

/* O3状态单元格 */
.o3-status {
  font-weight: bold;
  background-color: #f8f8f8;
}

/* 结果评价部分 */
.report-table tr:nth-last-child(3) td,
.report-table tr:nth-last-child(2) td,
.report-table tr:last-child td {
  text-align: left;
}

/* 复选框样式 */
.report-table label {
  margin-right: 20px;
  cursor: default;
}

.report-table input[type="checkbox"] {
  margin-right: 5px;
}

/* 下标样式 */
sub {
  font-size: 0.8em;
  vertical-align: sub;
}
</style>
