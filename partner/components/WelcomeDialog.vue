<script setup>
import { computed, ref } from 'vue'
import { Dialog, DialogPanel, DialogTitle } from '@headlessui/vue'
import { ArrowRight, BarChart3, CalendarClock, Sparkles, TicketCheck, Users } from 'lucide-vue-next'
import { greeting, number } from '../../src/domain/partnerOps.js'
import YanhuoIcon from '../../src/components/YanhuoIcon.vue'

/**
 * 进入工作台时的欢迎弹窗：公司名（服务商名称），「称呼，下午好」，今天的要点（待跟进、即将到期、团队烟火、本月激活码），两个去处。
 * 每个账号每天只弹一次，填好团队称呼之后才弹，见 OverviewView。
 */
const props = defineProps({
  open: { type: Boolean, default: false },
  partnerName: { type: String, default: '' },
  personName: { type: String, default: '' },
  roleName: { type: String, default: '' },
  todos: { type: Number, default: 0 },
  expiring: { type: Number, default: 0 },
  tenants: { type: Number, default: 0 },
  balance: { type: Number, default: null },
  monthCodes: { type: Number, default: 0 },
  canDashboard: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'go'])
const primary = ref(null)
const hello = greeting(new Date().getHours())
const today = new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })
const headline = computed(() => props.todos ? `今天有 ${props.todos} 个商户要跟进` : '名下商户都在正常使用')
const tip = computed(() => props.expiring ? `其中 ${props.expiring} 个 7 天内到期，可以在商户详情里直接续期。`
  : props.todos ? '点开工作台的待办，逐个处理即可。' : '可以去经营看板看看最近的使用情况。')
</script>

<template>
  <Dialog :open="open" class="management-dialog" :initial-focus="primary" @close="emit('close')">
    <div class="mg-backdrop pwl-backdrop" aria-hidden="true" />
    <div class="partner-modal-position">
      <DialogPanel class="pwl management-page">
        <div class="pwl-hero">
          <div class="pwl-glow" aria-hidden="true" />
          <span class="pwl-badge"><Sparkles :size="13" />{{ partnerName || '服务商' }}</span>
          <DialogTitle as="h2">{{ personName ? `${personName}，${hello}` : hello }}</DialogTitle>
          <p class="pwl-date">{{ today }}<template v-if="roleName"> · {{ roleName }}</template></p>
        </div>
        <div class="pwl-body">
          <p class="pwl-headline">{{ headline }}</p>
          <p class="pwl-tip">{{ tip }}</p>
          <ul class="pwl-facts">
            <li><Users :size="16" /><span>名下商户</span><b class="mg-mono">{{ number(tenants) }}</b></li>
            <li><CalendarClock :size="16" /><span>7 天内到期</span><b class="mg-mono" :class="{ warn: expiring }">{{ number(expiring) }}</b></li>
            <li v-if="balance != null"><YanhuoIcon :size="16" /><span>团队烟火</span><b class="mg-mono">{{ number(balance) }}</b></li>
            <li><TicketCheck :size="16" /><span>本月生成激活码</span><b class="mg-mono">{{ number(monthCodes) }}</b></li>
          </ul>
        </div>
        <footer class="pwl-actions">
          <button v-if="canDashboard" class="mg-button" type="button" @click="emit('go', 'partner-dashboard')"><BarChart3 :size="15" />经营看板</button>
          <button ref="primary" class="mg-button mg-primary" type="button" @click="emit('close')">{{ todos ? '开始处理待办' : '进入工作台' }}<ArrowRight :size="15" /></button>
        </footer>
      </DialogPanel>
    </div>
  </Dialog>
</template>
