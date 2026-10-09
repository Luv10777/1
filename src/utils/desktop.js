// 页面是不是开在一方志桌面端里。桌面端会在页面里放一座桥（desktop/src/preload.cjs），浏览器里没有。
// 桌面端只做直播这一件事：外壳、路由和直播页都据此收窄。
export const desktopBridge = typeof window === 'undefined' ? null : window.yifangzhiDesktop || null
export const inDesktop = Boolean(desktopBridge)

/**
 * 网页版里“打开桌面端”用的链接：带上这边正在配置的门店，软件被唤起后就换到这一家。
 * 老版本的软件不看链接里的内容，只把窗口拿到前面来。
 */
export const desktopLink = storeId => `yifangzhi://live${storeId ? `?store=${encodeURIComponent(storeId)}` : ''}`
