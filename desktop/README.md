# 一方志桌面端（试用阶段）

在商家自己的电脑上读取直播间弹幕，送进控制台的 AI 回复。**现在还不是能发给商家的安装包**：只能在开发环境里运行，没有打包、签名和自动更新。

## 它怎么读弹幕

网页版控制台做不到这件事：浏览器不允许网页以别的网站的身份去连抖音。桌面端的做法是在一个不显示的窗口里打开商家自己的直播间网页（`live.douyin.com/<直播间号>`），旁听这个网页收到的数据：

- 连接和签名都由抖音自己的网页完成，这里没有任何签名算法，抖音改签名不影响这里。
- 只读弹幕的文字和编号，以及“是否在播”“已下播”；不读观众的昵称、头像和账号。
- 不需要登录抖音。抖音网页的数据和控制台的分开存放。

这不是抖音开放平台的接口。能不能用于正式产品是另一个问题，见 `docs/live-browser-audio-integration.md`。

## 运行

```bash
cd desktop
npm install
# 这台机器的 npm 不会自动执行依赖的安装脚本，Electron 本体要手动取一次。
# 国内直连 GitHub 取不到时加上镜像；安装包会和 npm 包里自带的校验值比对。
ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/" node node_modules/electron/install.js

npm run dist:win   # 打 Windows 安装包，见下面“打包”
npm run dist:mac   # 打 macOS 安装包（Intel 和苹果芯片通用），见下面“打包”
npm start          # 打开桌面端窗口，里面是本机开发服务器上的控制台（http://localhost:4173）
npm test           # 解码器和消息格式的测试
npm run probe -- <抖音分享链接或直播间号> [分钟]   # 不开控制台，只把读到的弹幕打印出来
```

`npm start` 之前要先照常启动后端和前端开发服务器。开发时连 `http://localhost:4173`，打包后连 `https://yifangzhi.com`，都可以用环境变量 `YIFANGZHI_CONSOLE_URL` 改。

桌面端只做直播：打开就是直播页，没有平台的其余导航（页面这边由 `src/utils/desktop.js`、`src/layouts/DesktopShell.vue` 和路由守卫负责）。直播工作台里、自动讲解下面的“直播间弹幕”就是入口：粘贴抖音 App 里“分享 → 复制链接”的内容，点“连接直播间”。这一块只有在桌面端里才出现，浏览器里打开控制台看不到。

## 打包

Windows 安装包可以直接在 macOS 上打，不需要 Windows 电脑，也不需要 wine：

```bash
# 国内直连 GitHub 取不到 Electron 和 NSIS 时加上这两个镜像
export ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/"
export ELECTRON_BUILDER_BINARIES_MIRROR="https://npmmirror.com/mirrors/electron-builder-binaries/"

npm run dist:win                                        # 正式包：软件连 https://yifangzhi.com
npm run dist:win -- --test http://192.168.x.x:4173      # 测试包：软件连给定的地址
```

安装包出在 `dist/`（不进 git）。测试包的名字是“一方志测试版”，安装位置、数据目录都和正式包分开，可以同时装在一台电脑上；它保留了“视图 → 开发者工具”，正式包没有。测试包里的地址是打包时写死的，开发服务器所在电脑的局域网 IP 变了就要重打。

安装包**没有代码签名**，Windows 会提示“未知发布者”，需要点“更多信息 → 仍要运行”。

**没有自动更新，只有提醒。** 软件显示的是网站上的页面，所以页面上的改动发布网页后就生效，不用重装；改到软件本身（`desktop/` 下的代码）才需要新安装包。有新安装包时，软件顶上会出现一条“桌面端有新版本”的提醒，带下载按钮，点了交给系统浏览器去下载。提醒是页面给的（`src/services/desktopUpdate.js`、`src/layouts/DesktopShell.vue`）：页面从浏览器标识里看出装的是哪一版，再去看网站的 `/downloads/latest.json`，所以已经装出去的旧版本也收得到。

出新版本时，除了换掉服务器上的安装包，还要把 `latest.json` 一起换掉，提醒才会出现；两个系统的版本号各写各的，没有这个文件就不提醒：

```json
{
  "windows": { "version": "0.1.3", "url": "/downloads/yifangzhi-setup.exe" },
  "mac": { "version": "0.1.3", "url": "/downloads/yifangzhi-mac.dmg" }
}
```

软件名是中文，Electron 默认会把它写进浏览器标识（User-Agent），后端因此拒绝建立播报的长连接。`src/userAgent.js` 在打开任何窗口之前把它换成英文名；0.1.0 没有这一步，装上后播报会一直“重连中”。

正式包会登记 `yifangzhi://` 协议：网页版“启动直播”一步里的“打开桌面端”就是一条 `yifangzhi://live?store=<门店编号>` 链接，点了把已安装的软件唤到前面，并换到网页上正在配置的那家门店（0.1.3 起）。`src/link.js` 只从链接里读一个纯数字的门店编号，换不换由页面决定，经过和界限见设计文档。测试包不登记，免得抢走正式版的链接。

开发时可以把链接当作启动参数来试：`npm start -- "yifangzhi://live?store=4"`；软件已经开着时再运行一次，等于它又被唤起了一次。网页上的入口看网站的 `/downloads/` 下有哪个安装包：有 `yifangzhi-setup.exe` 就给“下载 Windows 版”，有 `yifangzhi-mac.dmg` 就给“下载 Mac 版”，都没有就整块不显示。出新版本时把服务器上的这两个文件换掉，各另留一份带版本号的。

### macOS

```bash
npm run dist:mac    # 出 dist/yifangzhi-<版本>-mac.dmg，软件连 https://yifangzhi.com
```

打出来的是“通用版”：一个安装包里同时带 Intel 芯片和苹果芯片（M 系列）两份程序，两种 Mac 都能装，代价是体积大一倍（0.1.2 是 216 MB；只带苹果芯片那一份时是 114 MB）。要求 macOS 13 及以上。两份程序都在一台 M2 的 Mac 上跑过（Intel 那一份靠系统的转译运行）：能打开线上的登录页、播报的长连接能建立、直播间号能解析。**没有在真正的 Intel Mac 上试过。**

安装包**没有苹果开发者签名，也没有经过苹果公证**（`codesign` 显示 `Signature=adhoc`）。本机打出来的包可以直接打开；从网上下载的副本第一次打开会被 macOS 拦下。在 macOS 26.6 上模拟过一次下载后打开：系统弹出提示并结束了程序，同时把它记成一次“被拦下的打开”——“系统设置 → 隐私与安全性”里的“仍要打开”放行的就是这种记录。**放行这一步本身没有点过。** 这种包只适合给愿意自己放行的人试用；要让商家下载后双击就能装，需要 Apple Developer 账号：把 `package.json` 里 `build.mac` 的 `identity: "-"` 换成 Developer ID 证书、`hardenedRuntime` 改回 `true`，再加上公证。

本机试 Mac 包之前先退出 `npm start` 开着的窗口：两边共用一份数据目录，软件只允许开一个，后开的会把先开的唤到前面然后自己退出。

不要在 `desktop/` 目录里对打出来的 `app.asar` 运行 `asar extract-file … package.json`：它会把解出来的文件写到当前目录，盖掉这里的 `package.json`。

## 文件

| 文件 | 作用 |
|---|---|
| `src/main.js` | 软件入口：打开控制台窗口，接收页面的请求，管理采集 |
| `src/preload.cjs` | 页面能用到的全部桌面端能力（`window.yifangzhiDesktop`） |
| `src/danmakuCollector.js` | 在隐藏窗口里打开直播间网页，判断是否在播，旁听弹幕 |
| `src/douyinFrames.js` | 把网页收到的二进制数据解成弹幕 |
| `src/roomLookup.js` | 把分享链接或直播间网址换成直播间号 |
| `src/link.js` | 读“打开桌面端”那条链接带来的门店编号 |
| `src/wire.js` | 采集到的事件交给页面时的格式 |
| `src/probe.js` | 可行性验证用的命令行入口（不打进安装包） |
| `scripts/dist-win.js` | 打 Windows 安装包 |
| `build/icon.png` | 软件图标 |

页面这一侧的代码在 `src/components/LiveDanmakuPanel.vue` 和 `src/services/liveDanmakuRelay.js`；后端入口是 `POST /api/live-sessions/{id}/comments`。
