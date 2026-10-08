// 页面是不是开在一方志桌面端里。桌面端会在页面里放一座桥（desktop/src/preload.cjs），浏览器里没有。
// 桌面端只做直播这一件事：外壳、路由和直播页都据此收窄。
export const desktopBridge = typeof window === 'undefined' ? null : window.yifangzhiDesktop || null
export const inDesktop = Boolean(desktopBridge)
