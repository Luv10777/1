// 软件（电脑上的桌面端、手机上的安卓端）有没有新版本。
// 软件本身不会自动更新。但它显示的是网站上的页面，所以由页面来提醒：从浏览器标识里看出装的是哪一版，
// 再看一眼网站 /downloads/latest.json 里登记的最新版本，旧了就在软件顶上给一条提醒。
// 这样已经装出去的旧版本也收得到提醒。latest.json 和安装包放在一起，换安装包时一起换；没有这个文件就不提醒。
//
// latest.json 的样子（三种各写各的版本号，没有的可以不写）：
//   { "windows": { "version": "0.1.3", "url": "/downloads/yifangzhi-setup.exe" },
//     "mac":     { "version": "0.1.3", "url": "/downloads/yifangzhi-mac.dmg" },
//     "android": { "version": "0.1.0", "url": "/downloads/yifangzhi-android.apk" } }

const LATEST_PATH = '/downloads/latest.json'
const VERSION = /^\d+(\.\d+){1,3}$/
const PLATFORMS = ['windows', 'mac', 'android']

/** 从浏览器标识里读出装的是哪一版、哪个系统；不是在软件里则为 null。 */
export function installedDesktop(userAgent) {
  const text = String(userAgent || '')
  // 安卓端的标识里也带着 “Linux; Android” 之类的字样，所以先认它自己的名字。
  const android = /YifangzhiAndroid\/(\d+(?:\.\d+){1,3})(?![\d.])/.exec(text)?.[1]
  if (android) return { version: android, platform: 'android' }
  const version = /YifangzhiDesktop\/(\d+(?:\.\d+){1,3})(?![\d.])/.exec(text)?.[1]
  const platform = /Windows/.test(text) ? 'windows' : /Macintosh|Mac OS X/.test(text) ? 'mac' : ''
  return version && platform ? { version, platform } : null
}

/** a 比 b 新返回正数，比 b 旧返回负数，一样返回 0。按点分开的数字一段一段比，缺的那段当 0。 */
export function compareVersions(a, b) {
  const left = String(a).split('.').map(Number)
  const right = String(b).split('.').map(Number)
  for (let index = 0; index < Math.max(left.length, right.length); index++) {
    const difference = (left[index] || 0) - (right[index] || 0)
    if (difference) return difference
  }
  return 0
}

/** 网站上登记的各个系统的最新版本：{ windows?: { version, url }, mac?: …, android?: … }。登记得不对的那一项不算。 */
export async function readLatestVersions({ fetch = globalThis.fetch } = {}) {
  try {
    const response = await fetch(LATEST_PATH, { cache: 'no-store' })
    if (!response.ok) return {}
    const published = await response.json()
    const latest = {}
    for (const platform of PLATFORMS) {
      const entry = published?.[platform]
      // 下载地址只认网站自己 /downloads/ 下的文件。
      if (entry && VERSION.test(entry.version) && typeof entry.url === 'string' && /^\/downloads\/[\w.-]+$/.test(entry.url)) {
        latest[platform] = { version: entry.version, url: entry.url }
      }
    }
    return latest
  } catch {
    return {}
  }
}

/** 有新版本时返回 { version, current, platform, url }，没有、查不到或登记得不对时返回 null。 */
export async function findDesktopUpdate({ userAgent = globalThis.navigator?.userAgent, fetch = globalThis.fetch } = {}) {
  const installed = installedDesktop(userAgent)
  if (!installed) return null
  const latest = (await readLatestVersions({ fetch }))[installed.platform]
  if (!latest || compareVersions(latest.version, installed.version) <= 0) return null
  return { version: latest.version, current: installed.version, platform: installed.platform, url: latest.url }
}
