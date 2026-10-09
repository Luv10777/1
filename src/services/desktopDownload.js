// 桌面端安装包在哪里下载。
// 安装包放在网站自己的 /downloads/ 下，用固定的文件名。页面打开时看一眼它们在不在：在就给出入口，不在就不给。
// 这样入口不依赖构建时带没带参数；换了托管位置时，才需要用 VITE_DESKTOP_DOWNLOAD_URL、VITE_DESKTOP_MAC_DOWNLOAD_URL 指过去。

const DEFAULT_PATHS = {
  windows: '/downloads/yifangzhi-setup.exe',
  mac: '/downloads/yifangzhi-mac.dmg',
}

async function find(path, configured, fetch) {
  if (configured) return configured
  try {
    const response = await fetch(path, { method: 'HEAD' })
    // 没放安装包的环境里，这个地址会落回网页本身（比如开发服务器），那不算有。
    const type = response.headers.get('content-type') || ''
    return response.ok && !type.startsWith('text/html') ? path : ''
  } catch {
    return ''
  }
}

/** 返回各个系统安装包的下载地址；哪个没有，哪个就是空串。 */
export async function findDesktopDownloads({
  configured = { windows: import.meta.env?.VITE_DESKTOP_DOWNLOAD_URL || '', mac: import.meta.env?.VITE_DESKTOP_MAC_DOWNLOAD_URL || '' },
  fetch = globalThis.fetch,
} = {}) {
  const [windows, mac] = await Promise.all([
    find(DEFAULT_PATHS.windows, configured.windows, fetch),
    find(DEFAULT_PATHS.mac, configured.mac, fetch),
  ])
  return { windows, mac }
}
