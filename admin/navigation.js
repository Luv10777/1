import { onBeforeUnmount, ref } from 'vue'

/**
 * 管理后台和服务商后台共用：切换页面要先下载页面代码、再过权限检查，慢的时候顶栏显示一条进度条。
 * 很快完成的跳转（缓存命中）不闪进度条。
 */
export function useNavigationProgress(router, delay = 120) {
  const pending = ref(false)
  let timer = null
  const finish = () => {
    clearTimeout(timer)
    pending.value = false
  }
  const removers = [
    router.beforeEach((to, from) => {
      if (to.name !== from.name) {
        clearTimeout(timer)
        timer = setTimeout(() => { pending.value = true }, delay)
      }
    }),
    router.afterEach(finish),
    router.onError(finish),
  ]
  onBeforeUnmount(() => {
    finish()
    removers.forEach(remove => remove())
  })
  return pending
}
