// 桌面端安装包在哪里下载。
// 安装包放在网站自己的 /downloads/ 下，用固定的文件名。页面打开时看一眼它在不在：在就给出入口，不在就不给。
// 这样入口不依赖构建时带没带参数；换了托管位置时，才需要用 VITE_DESKTOP_DOWNLOAD_URL 指过去。

const DEFAULT_PATH = '/downloads/yifangzhi-setup.exe'

/** 返回安装包的下载地址；没有就返回空串。 */
export async function findDesktopDownload({ configured = import.meta.env?.VITE_DESKTOP_DOWNLOAD_URL || '', fetch = globalThis.fetch } = {}) {
  if (configured) return configured
  try {
    const response = await fetch(DEFAULT_PATH, { method: 'HEAD' })
    // 没放安装包的环境里，这个地址会落回网页本身（比如开发服务器），那不算有。
    const type = response.headers.get('content-type') || ''
    return response.ok && !type.startsWith('text/html') ? DEFAULT_PATH : ''
  } catch {
    return ''
  }
}
