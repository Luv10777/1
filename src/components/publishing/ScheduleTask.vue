<script setup>
import { computed } from 'vue'
import { Check, CircleAlert, MapPin, Pencil, Send, X } from 'lucide-vue-next'
import PlatformMark from '../accounts/PlatformMark.vue'
import { planAccounts } from '../../data/publishingPlans'
const props = defineProps({ task: { type: Object, required: true }, paused: Boolean })
defineEmits(['publish', 'remove', 'edit'])
const platform = computed(() => planAccounts.find(a => a.id === props.task.accountId)?.platform || '抖音')
const state = computed(() => props.task.status === 'published' ? '已发布' : props.task.status === 'blocked' ? '待处理' : props.paused ? '已暂停' : '待发布')
</script>

<template>
  <article :draggable="task.status === 'pending'" class="group relative min-w-0 rounded-xl border border-solid bg-white p-2.5 shadow-[0_2px_6px_rgba(15,23,42,0.025)] transition hover:-translate-y-0.5 hover:shadow-md motion-reduce:transform-none" :class="task.status === 'blocked' ? 'border-rose-200' : 'border-slate-200/80'" @dragstart="$event.dataTransfer.setData('text/plain', task.id)">
    <div class="mb-2.5 flex items-center justify-between gap-1"><time class="font-mono text-[13px] font-semibold text-slate-900">{{ task.time }}</time><span class="flex items-center gap-1 text-[10px]" :class="task.status === 'published' ? 'text-emerald-600' : task.status === 'blocked' ? 'text-rose-500' : paused ? 'text-slate-400' : 'text-amber-600'"><Check v-if="task.status === 'published'" :size="11" /><CircleAlert v-else-if="task.status === 'blocked'" :size="11" /><span v-else class="size-1 rounded-full bg-current"></span>{{ state }}</span></div>
    <div class="flex items-start gap-2"><div class="relative shrink-0"><img :src="task.image" alt="" class="size-10 rounded-lg object-cover" loading="lazy" /><PlatformMark :platform="platform" class="absolute -bottom-1 -right-1 size-4! rounded-[5px]! ring-2 ring-white [&_img]:size-2.5! [&_svg]:size-2.5!" /></div><div class="min-w-0"><p class="m-0 line-clamp-2 text-[11px] font-medium leading-[1.6] text-slate-700" :title="task.title">{{ task.title }}</p><span class="mt-1 block text-[9px] text-slate-400">{{ platform }} · {{ task.kind }}</span></div></div>
    <span class="mt-3 inline-flex max-w-full items-center gap-0.5 truncate rounded-md bg-amber-50 px-1.5 py-1 text-[9px] text-amber-700"><MapPin :size="10" class="shrink-0" />{{ task.store }}</span>
    <p v-if="task.status === 'blocked'" class="mb-0 mt-2 text-[10px] leading-relaxed text-rose-500">{{ task.error }}</p>
    <div v-if="task.status !== 'published'" class="mt-2 flex items-center justify-between gap-1 border-0 border-t border-solid border-slate-100 pt-2 opacity-100 transition-opacity xl:opacity-0 xl:group-hover:opacity-100 xl:group-focus-within:opacity-100 [@media(hover:none)]:opacity-100">
      <button :disabled="task.status === 'blocked'" class="flex items-center gap-1 rounded border-0 bg-transparent p-0.5 text-[10px]! text-blue-600! hover:bg-blue-50 disabled:cursor-not-allowed disabled:text-slate-300!" :aria-label="`立即发布 ${task.title}`" @click="$emit('publish', task)"><Send :size="10" />立即发布</button>
      <div class="flex"><button class="rounded border-0 bg-transparent p-1 text-slate-400! hover:bg-blue-50 hover:text-blue-600!" :aria-label="`微调作品 ${task.title}`" title="微调作品" @click="$emit('edit', task)"><Pencil :size="12" /></button><button class="rounded border-0 bg-transparent p-1 text-slate-400! hover:bg-rose-50 hover:text-rose-500!" :aria-label="`移出排期 ${task.title}`" title="移出排期" @click="$emit('remove', task)"><X :size="12" /></button></div>
    </div>
  </article>
</template>
