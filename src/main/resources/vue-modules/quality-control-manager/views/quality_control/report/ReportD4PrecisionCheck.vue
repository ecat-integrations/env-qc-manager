<!--仪器精密度审核记录表-->
<template>
  <div class="report-d4">
    <h2 style="text-align: center;">{{ reportData.title }}</h2>
    <table class="report-table">
      <tbody>
      <tr>
        <td>仪器名称：{{ reportData.instrument_info.instrument_name }}</td>
        <td>仪器编号：{{ reportData.instrument_info.instrument_no }}</td>
        <td>审核日期：{{ reportData.instrument_info.report_date }}</td>
      </tr>
      <tr>
        <td>标气来源：{{ reportData.instrument_info.gas_source }}</td>
        <td>标气编号：{{ reportData.instrument_info.gas_no }}</td>
        <td>标气浓度：{{ reportData.instrument_info.gas_concentration }}</td>
      </tr>
      <tr>
        <td>通入仪器标气浓度</td>
        <td>{{ reportData.gas_concentrations_input }}</td>
      </tr>
      <tr>
        <th>序号</th>
        <th>仪器响应值</th>
      </tr>
      <tr v-for="(response, index) in reportData.instrument_responses" :key="index">
        <td>{{ index + 1 }}</td>
        <td>{{ response }}</td>
      </tr>
      <tr>
        <td>相对标准偏差：{{ reportData.relative_standard_deviation }}</td>
      </tr>
      <tr>
        <td>审核结果：{{ reportData.audit_result }}</td>
      </tr>
      <tr>
        <td colspan="4">备注：{{ reportData.remark }}</td>
      </tr>
      <tr>
        <td>填表人：{{ reportData.filer }}</td>
        <td>复核人：{{ reportData.reviewer }}</td>
      </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import { defineProps } from 'vue';

const props = defineProps({
  reportData: {
    type: Object,
    required: true
  }
});
</script>

<style scoped>
.report-d4 {
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
  text-align: left;
}
.report-table th {
  background-color: #f4f4f4;
}
</style>
