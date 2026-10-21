import { ref } from 'vue'
import { businessCommunityService } from '../services/businessCommunity'
import { auth } from '../stores/auth'

/**
 * 本商户的入驻是否审核通过：线上卖货、发布信息、提供联盟券都要求通过。
 * 只用来提前提示，真正的限制在后端；读取失败时 approved 为 null，不拦用户。
 */
export function useMerchantApproval() {
  const approved = ref(null)
  async function load() {
    if (!auth.isAuthenticated) { approved.value = null; return }
    try {
      const applications = await businessCommunityService.applications()
      approved.value = (applications || []).some(item => item.status === 'APPROVED')
    } catch {
      approved.value = null
    }
  }
  load()
  return { approved, reload: load }
}
