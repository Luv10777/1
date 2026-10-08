// 打 Windows 安装包。
//   npm run dist:win                       正式包：软件连 https://yifangzhi.com
//   npm run dist:win -- --test <控制台地址>   测试包：软件连给定的地址（例如局域网里的开发服务器），
//                                           名字、安装位置和数据目录都和正式包分开，可以同时装在一台电脑上
import { build, Platform, Arch } from 'electron-builder'

const args = process.argv.slice(2)
const testAt = args.indexOf('--test')
const consoleUrl = testAt >= 0 ? args[testAt + 1] : ''
if (testAt >= 0 && !/^https?:\/\/[^\s/]+/.test(consoleUrl || '')) {
  console.error('用法：npm run dist:win -- --test http://192.168.x.x:4173')
  process.exit(1)
}

const config = consoleUrl ? {
  appId: 'com.yifangzhi.desktop.test',
  productName: '一方志测试版',
  // 写进安装包里的 package.json，软件启动时读它来决定连哪里。
  extraMetadata: { name: 'yifangzhi-desktop-test', productName: '一方志测试版', consoleUrl: new URL(consoleUrl).origin },
  nsis: { artifactName: 'yifangzhi-test-setup-${version}.${ext}' },
  // 网页上的“打开桌面端”只该唤起正式版。
  protocols: [],
} : {}

const artifacts = await build({ targets: Platform.WINDOWS.createTarget('nsis', Arch.x64), config })
console.log(consoleUrl ? `测试包（连 ${new URL(consoleUrl).origin}）：` : '正式包（连 https://yifangzhi.com）：')
for (const file of artifacts) console.log('  ' + file)
