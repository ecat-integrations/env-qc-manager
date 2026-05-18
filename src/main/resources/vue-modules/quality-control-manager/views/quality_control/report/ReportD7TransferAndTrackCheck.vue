<!--臭氧校准设备量值传递记录表-->
<template>
  <div class="report-d7">
    <h2 style="text-align: center;">{{ reportData.title }}</h2>
    <table class="report-table">
      <tbody>
      <tr>
        <td>臭氧校准设备：{{ reportData.equipment_info.ozone_calibration_equipment }}</td>
        <td>量值传递日期：{{ reportData.equipment_info.value_transfer_date }}</td>
      </tr>
      <tr>
        <td>臭氧传递标准：{{ reportData.equipment_info.ozone_transfer_standard }}</td>
        <td>溯源日期：{{ reportData.equipment_info.traceability_date }}</td>
      </tr>
      <tr>
        <td>监测仪器：{{ reportData.equipment_info.monitor_instrument }}</td>
      </tr>

      <tr>
        <th>校准点</th>
        <th>传递标准输出</th>
        <th>监测仪器值</th>
        <th>工作标准输出</th>
        <th>监测仪器值(再测)</th>
        <th>标准值</th>
      </tr>
      <tr v-for="(point, index) in reportData.calibration_points" :key="index">
        <td>{{ point.point_name }}</td>
        <td>{{ point.transfer_standard_output }}</td>
        <td>{{ point.monitor_instrument_value }}</td>
        <td>{{ point.working_standard_output }}</td>
        <td>{{ point.monitor_instrument_value_again }}</td>
        <td>{{ point.standard_value }}</td>
      </tr>

      <tr>
        <td>校准曲线：</td>
        <td colspan="5">
          公式：{{ reportData.calibration_curve.formula }}<br>
          a值：{{ reportData.calibration_curve.a }}<br>
          b值：{{ reportData.calibration_curve.b }}<br>
          相关系数r：{{ reportData.calibration_curve.r }}
        </td>
      </tr>

      <tr>
        <td colspan="6">备注：{{ remarkDisplay }}</td>
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
import { defineProps, computed } from 'vue';
import { formatReportRemarkDisplay } from './formatReportRemarkDisplay.js';

const props = defineProps({
  reportData: {
    type: Object,
    required: true
  }
});

const remarkDisplay = computed(() => formatReportRemarkDisplay(props.reportData && props.reportData.remark));
</script>

<style scoped>
.report-d7 {
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
