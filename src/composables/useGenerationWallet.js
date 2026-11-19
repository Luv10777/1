import { computed, ref, watch } from 'vue'
import { auth } from '../stores/auth'
import { billing, generationBalance } from '../stores/billing'

/**
 * 扣费模块共用的「用哪份额度」：服务商负责人可以在个人额度和团队额度之间选，其他账号只有个人额度，
 * 选择器不显示，请求也不带 wallet。换账号回到个人额度。
 * shortage(required) 在余额读得到且不够时返回弹窗要的数；余额没读到时返回 null，交给后端判断。
 */
export function useGenerationWallet() {
  const wallet = ref('PERSONAL')
  watch(() => [auth.user?.id, auth.tenantId], () => { wallet.value = 'PERSONAL' })
  // 团队额度消失（被移出服务商）时自动回到个人额度，不再发 TEAM。
  watch(() => billing.summary?.generationTeam, team => { if (!team) wallet.value = 'PERSONAL' })
  const available = computed(() => generationBalance(wallet.value))
  const label = computed(() => (wallet.value === 'TEAM' ? '团队额度' : '个人额度'))
  const shortage = required => {
    if (typeof billing.summary?.available !== 'number' || required == null) return null
    const need = Number(required)
    return available.value < need ? { required: need, available: available.value } : null
  }
  return { wallet, available, label, shortage }
}
