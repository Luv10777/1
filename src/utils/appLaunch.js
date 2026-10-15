/**
 * 用 yifangzhi:// 链接唤起电脑上的一方志软件，并猜一猜唤没唤起来。
 * 浏览器不告诉网页电脑上装没装：装了的话，浏览器会弹出“要打开一方志吗”，或者直接切到软件，
 * 这两种情况网页都会失去焦点或被切到后台；等一会儿什么都没发生，就当没装。
 * 这只是猜：用户恰好在这几秒里点了别的窗口，也会被当成已经打开。
 */
export function launchApp(link, { timeout = 2000, win = window, doc = document } = {}) {
  return new Promise(resolve => {
    let timer = null
    const finish = (launched) => {
      clearTimeout(timer)
      win.removeEventListener('blur', onLeave)
      doc.removeEventListener('visibilitychange', onHidden)
      resolve(launched)
    }
    const onLeave = () => finish(true)
    const onHidden = () => { if (doc.hidden) finish(true) }
    win.addEventListener('blur', onLeave)
    doc.addEventListener('visibilitychange', onHidden)
    timer = setTimeout(() => finish(false), timeout)
    win.location.href = link
  })
}

/** 手机和平板上没有桌面端；安卓 App 也还不能被网页唤起，所以直接给下载入口。 */
export const onPhone = (userAgent = globalThis.navigator?.userAgent || '') => /Android|iPhone|iPad|iPod|HarmonyOS|OpenHarmony/i.test(userAgent)
