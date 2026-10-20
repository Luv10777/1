<script setup>
// 烟火的货币图标：朱砂币上一簇白色火苗。纯装饰，数量旁仍保留「烟火」文字。
defineProps({ size: { type: [Number, String], default: 16 } })
</script>

<template>
  <svg class="yanhuo-coin" :width="size" :height="size" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <circle cx="12" cy="12" r="11" fill="var(--color-accent, #C84A3D)" />
    <circle cx="12" cy="12" r="9.2" fill="none" stroke="#fff" stroke-opacity=".35" stroke-width=".9" />
    <path fill="#fff" transform="translate(2.9 2.4) scale(.76)" d="M12 3.6C12.7 6.4 15.6 8.2 16.7 11.1C17.8 14 16.4 18.4 12 18.4C7.6 18.4 6.3 14.3 7.6 11.3C8.3 9.8 9.2 9.1 9.4 7.7C10.5 8.8 10.8 10.2 10.5 11.6C11.9 10.4 12.5 7.9 12 3.6Z" />
  </svg>
</template>
