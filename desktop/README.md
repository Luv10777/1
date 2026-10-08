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

安装包**没有代码签名**，Windows 会提示“未知发布者”，需要点“更多信息 → 仍要运行”。没有自动更新。

软件名是中文，Electron 默认会把它写进浏览器标识（User-Agent），后端因此拒绝建立播报的长连接。`src/userAgent.js` 在打开任何窗口之前把它换成英文名；0.1.0 没有这一步，装上后播报会一直“重连中”。

正式包会登记 `yifangzhi://` 协议：网页版“启动直播”一步里的“打开桌面端”就是一条 `yifangzhi://live` 链接，点了把已安装的软件唤到前面。测试包不登记，免得抢走正式版的链接。网页上的入口在网站的 `/downloads/yifangzhi-setup.exe` 存在时出现：出新版本时把服务器上的这个文件换掉，另留一份带版本号的。

不要在 `desktop/` 目录里对打出来的 `app.asar` 运行 `asar extract-file … package.json`：它会把解出来的文件写到当前目录，盖掉这里的 `package.json`。

## 文件

| 文件 | 作用 |
|---|---|
| `src/main.js` | 软件入口：打开控制台窗口，接收页面的请求，管理采集 |
| `src/preload.cjs` | 页面能用到的全部桌面端能力（`window.yifangzhiDesktop`） |
| `src/danmakuCollector.js` | 在隐藏窗口里打开直播间网页，判断是否在播，旁听弹幕 |
| `src/douyinFrames.js` | 把网页收到的二进制数据解成弹幕 |
| `src/roomLookup.js` | 把分享链接或直播间网址换成直播间号 |
| `src/wire.js` | 采集到的事件交给页面时的格式 |
| `src/probe.js` | 可行性验证用的命令行入口（不打进安装包） |
| `scripts/dist-win.js` | 打 Windows 安装包 |
| `build/icon.png` | 软件图标 |

页面这一侧的代码在 `src/components/LiveDanmakuPanel.vue` 和 `src/services/liveDanmakuRelay.js`；后端入口是 `POST /api/live-sessions/{id}/comments`。
