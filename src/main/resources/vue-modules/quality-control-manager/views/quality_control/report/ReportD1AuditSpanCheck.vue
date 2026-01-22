<!--仪器运行状况检查记录表-->
<template>
  <div class="report-d1">
    <h2 style="text-align: center;">{{ reportData.title }}</h2>
    <table class="report-table">
      <!-- 表头部分 -->
      <tbody>
      <tr>
        <td>仪器名称及编号</td>
        <td colspan="2">{{ reportData.instrument_info.instrument_name + " " + reportData.instrument_info.instrument_no }}</td>
        <td>校准日期</td>
        <td colspan="2">{{reportData.instrument_info.report_date }}</td>
      </tr>
      <tr>
        <td>标气来源及编号</td>
        <td colspan="2">{{ reportData.instrument_info.gas_source + " " + reportData.instrument_info.gas_no }}</td>
        <td>标气浓度</td>
        <td colspan="2">{{ reportData.instrument_info.gas_concentration }}</td>
      </tr>
      <tr>
        <td>使用满量程</td>
        <td colspan="5">{{ reportData.full_span }}</td>
      </tr>

      <!-- 校准点部分 -->
      <tr>
        <th rowspan="2">校准点</th>
        <th rowspan="2">取数时间</th>
        <th rowspan="2">核查浓度</th>
        <th rowspan="2">标准浓度</th>
        <th>显示值</th>
        <th>标定值</th>
      </tr>
      <tr>
        <th>响应浓度</th>
        <th>响应浓度</th>
      </tr>
      <tr v-for="(point, index) in reportData.calibration_points" :key="index">
        <td>{{ point.point_name }}</td>
        <td>{{ point.check_time }}</td>
        <td>{{ point.check_concentration }}</td>
        <td>{{ point.standard_concentration }}</td>
        <td>{{ point.check_data }}</td>
        <td>{{ point.calibration_value }}</td>
      </tr>

      <!-- 漂移部分 -->
      <tr>
        <td>跨度漂移(%)</td>
        <td colspan="5">{{reportData.span_drift_result}}</td>
      </tr>

      <!-- 关键参数部分 -->
      <tr>
        <th>关键参数列表</th>
        <th>检查值</th>
        <th>正常范围</th>
        <th colspan="3">处理记录</th>
      </tr>
      <tr v-for="(param, index) in reportData.key_parameters" :key="index">
        <td>{{ param.tName }}</td>
        <td>{{ param.tValue }}</td>
        <td>{{ param.tRange }}</td>
        <td colspan="3">{{ param.tRemark }}</td>
      </tr>
      <tr>
        <td></td>
        <td></td>
        <td></td>
        <td colspan="3"></td>
      </tr>

      <!-- 备注部分 -->
      <tr>
        <td colspan="6">备注：{{ reportData.remark }}</td>
      </tr>
      <!-- 填表人和复核人部分 -->
      <tr>
        <td>填表人：</td>
        <td>{{ reportData.filler }}</td>
        <td>复核人：</td>
        <td>{{ reportData.reviewer }}</td>
      </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import {defineProps} from 'vue';

const props = defineProps({
  reportData: {
    type: Object,
    required: true,

    default: () => ({
      // title: '默认标题',
      // calibration_points: [],
      // 其他默认值...
    })
  }
});
</script>

<style scoped>
tr {
  height: 40px;
}
.report-d1 {
  font-family: Arial, sans-serif;
}

.report-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 10px;
}

.report-table th,
.report-table td {
  border: 1px solid #ccc;
  padding: 8px;
  text-align: center; /* 内容居中 */
}

.report-table th {
  background-color: #f4f4f4;
}

/* 调整特定单元格的宽度 */
.report-table td:first-child,
.report-table th:first-child {
  width: 20%;
}

/* 单元格内容垂直居中 */
.report-table td, .report-table th {
  vertical-align: middle;
}

/* 调整边框样式 */
.report-table {
  border: 2px solid #000; /* 外边框加粗 */
}
.report-table th, .report-table td {
  border: 1px solid #000; /* 内边框细化 */
}

/* 设置表格宽度和高度 */
.report-table {
  width: 100%; /* 或者指定具体的宽度，如 800px */
  max-width: 100%;
}

/* 左对齐 */
.left-align {
  text-align: left;
}
</style>
