<script setup>
import { Video } from 'lucide-vue-next'
defineProps({ platform: { type: String, required: true } })
</script>

<template>
  <span class="inline-flex size-9 shrink-0 items-center justify-center rounded-xl" :class="{ 'bg-slate-950': platform === '抖音', 'bg-rose-500': platform === '小红书', 'bg-amber-50 text-amber-500': platform === '视频号' }" aria-hidden="true">
    <img v-if="platform === '抖音'" src="/images/publishing/platforms/tiktok.svg" alt="" class="size-5 brightness-0 invert" />
    <img v-else-if="platform === '小红书'" src="/images/publishing/platforms/xiaohongshu.svg" alt="" class="h-5 w-7 brightness-0 invert" />
    <Video v-else :size="21" :stroke-width="2.3" />
  </span>
</template>
