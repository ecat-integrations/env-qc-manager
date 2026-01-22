<template>
  <svg 
    :width="size" 
    :height="size" 
    viewBox="0 0 24 32" 
    fill="none" 
    xmlns="http://www.w3.org/2000/svg"
    :class="colorClass"
  >
    <!-- 钢瓶主体 -->
    <rect x="6" y="6" width="12" height="25" rx="2" fill="currentColor" opacity="0.8"/>
    
    <!-- 钢瓶顶部 -->
    <rect x="8" y="4" width="8" height="4" rx="1" fill="currentColor" opacity="0.9"/>
    
    <!-- 钢瓶底部 -->
    <rect x="7" y="24" width="10" height="2" rx="1" fill="currentColor" opacity="0.7"/>
    
    <!-- 钢瓶阀门 -->
    <circle cx="12" cy="6" r="1" fill="currentColor" opacity="0.6"/>
    
    <!-- 钢瓶标识线 -->
    <rect x="8" y="9" width="8" height="1" fill="currentColor" opacity="0.4"/>
    <rect x="8" y="12" width="8" height="1" fill="currentColor" opacity="0.4"/>
    <rect x="8" y="15" width="8" height="1" fill="currentColor" opacity="0.4"/>
    <rect x="8" y="18" width="8" height="1" fill="currentColor" opacity="0.4"/>
    <rect x="8" y="21" width="8" height="1" fill="currentColor" opacity="0.4"/>
    
    <!-- 气体类型标签 -->
    <rect x="7" y="11" width="10" height="6" rx="1" fill="currentColor" opacity="0.1"/>
    <text 
      x="12" 
      y="15" 
      text-anchor="middle" 
      font-family="Arial, sans-serif" 
      font-size="3" 
      font-weight="bold"
      fill="currentColor"
      opacity="0.8"
    >
      {{ gasType }}
    </text>
  </svg>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  size: {
    type: [String, Number],
    default: 48
  },
  color: {
    type: String,
    default: 'blue' // blue, green, red, gray, yellow
  },
  gasType: {
    type: String,
    default: 'CO' // 气体类型：CO, NO2, SO2, O3等
  }
})

const colorClass = computed(() => {
  const colorMap = {
    blue: 'qc:text-blue-500',
    green: 'qc:text-green-500', 
    red: 'qc:text-red-500',
    gray: 'qc:text-gray-500',
    yellow: 'qc:text-yellow-500'
  }
  return colorMap[props.color] || colorMap.blue
})
</script> 
