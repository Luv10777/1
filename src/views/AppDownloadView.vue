<script setup>
// 安卓端的下载页，不用登录就能看。电脑上给二维码，手机上给下载按钮。
// 多半是用微信扫码进来的，而微信里下载不了安装包，所以先看清楚自己是在哪儿被打开的，把该怎么做说在前面。
import { computed, onMounted, ref } from 'vue'
import QRCode from 'qrcode'
import { findDesktopDownloads } from '../services/desktopDownload'
import { readLatestVersions } from '../services/desktopUpdate'

const agent = navigator.userAgent
const inWechat = /MicroMessenger/i.test(agent)
const onApple = /iPhone|iPad|iPod/i.test(agent)
const onAndroid = /Android/i.test(agent)
const loading = ref(true)
const downloadUrl = ref('')
const version = ref('')
const qr = ref('')
const pageUrl = `${window.location.origin}/app`
// 微信里和苹果手机上都下载不了；这两种情况各有各的说明，不给下载按钮。
const canDownloadHere = computed(() => Boolean(downloadUrl.value) && onAndroid && !inWechat)

onMounted(async () => {
  const [downloads, latest] = await Promise.all([findDesktopDownloads(), readLatestVersions()])
  downloadUrl.value = downloads.android
  version.value = latest.android?.version || ''
  loading.value = false
  if (downloads.android && !onAndroid && !onApple) {
    try { qr.value = await QRCode.toDataURL(pageUrl, { width: 220, margin: 2, errorCorrectionLevel: 'M' }) } catch { /* 画不出二维码时下面还有网址 */ }
  }
})
</script>

<template>
  <main class="app-page">
    <header class="app-head">
      <span class="app-mark"><img src="/images/brand/yifangzhi-mark.png" alt=""></span>
      <div>
        <p class="eyebrow">YIFANGZHI APP · ANDROID</p>
        <h1>一方志 App<span>试用</span></h1>
      </div>
    </header>
    <p class="app-lead">只用一台手机开播：抖音在前台直播，一方志在后台读弹幕，让 AI 用声音回答观众。</p>

    <section class="app-card" :aria-busy="loading">
      <p v-if="loading" class="app-note">正在查看有没有可以下载的安装包…</p>
      <template v-else-if="!downloadUrl">
        <h2>安卓版还没有开放下载</h2>
        <p class="app-note">安装包放上来之后，这里会出现下载按钮。</p>
      </template>
      <template v-else-if="inWechat">
        <h2>请先在浏览器里打开</h2>
        <p>微信里不能直接下载安装包。点右上角的“···”，选择“在浏览器打开”，再在那里点下载。</p>
      </template>
      <template v-else-if="onApple">
        <h2>目前只有安卓版</h2>
        <p>苹果手机装不了。可以用电脑版（在电脑上打开一方志，“AI 实景直播”里有下载入口），或者另备一台安卓手机。</p>
      </template>
      <template v-else-if="canDownloadHere">
        <a class="primary-button app-download" :href="downloadUrl" download>下载安卓版<small v-if="version">版本 {{ version }}</small></a>
        <p class="app-note">下载后点安装。手机提示“来源不明”或有风险时，选择继续安装。</p>
      </template>
      <template v-else>
        <h2>用安卓手机扫码下载</h2>
        <img v-if="qr" class="app-qr" :src="qr" alt="安卓版下载页的二维码" width="220" height="220">
        <p>用手机浏览器扫这个码。用微信扫也可以，扫开后按页面上的提示在浏览器里打开。</p>
        <p class="app-note">也可以在手机浏览器里直接输入：<span class="mono">{{ pageUrl }}</span><template v-if="version"> · 当前版本 {{ version }}</template></p>
      </template>
    </section>

    <section class="app-steps">
      <h2>第一次使用</h2>
      <ol>
        <li><strong>安装。</strong>提示“来源不明”或有风险时选择继续安装；荣耀、华为手机被“纯净模式”拦住时，按提示选择仍然安装。</li>
        <li><strong>允许在后台运行。</strong>第一次打开会问你，请选允许。不允许的话，切到抖音之后一方志会被手机暂停，AI 讲到一半就没有声音了。</li>
        <li><strong>登录、选门店、开始本场。</strong>账号和网页版是同一个。商品、话术、问答和音色仍在网页版里配置。</li>
        <li><strong>连接直播间。</strong>抖音开播后，在直播间点“分享 → 复制链接”，回到一方志粘贴，点“连接直播间”。连过一次会记住，下次不用再粘贴。</li>
        <li><strong>把媒体音量调到一半以上。</strong>声音是从手机喇叭放出来、再被抖音收进直播间的，音量太小时每段开头的几个字会听不清。</li>
      </ol>
    </section>

    <section class="app-steps">
      <h2>需要知道的</h2>
      <ul>
        <li>这是试用版。目前只在荣耀 X50 上完整地播过，别的手机上遇到声音中断、读不到弹幕等问题，请告诉我们机型。</li>
        <li>读取弹幕用的不是抖音官方接口，抖音改版时可能暂时失效；失效时 AI 讲解不受影响，只是不再回答弹幕。</li>
        <li>只读取弹幕的文字，不读取观众的昵称和账号，也不需要登录你的抖音账号。</li>
        <li>请按抖音当前的规则，在抖音 App 里标识 AI 合成的声音。</li>
      </ul>
    </section>
  </main>
</template>

<style scoped>
.app-page { max-width: 640px; margin: 0 auto; padding: 32px 20px 56px; color: var(--ink); }
.app-head { display: flex; align-items: center; gap: 14px; }
.app-mark img { display: block; width: 44px; height: 44px; }
.app-head h1 { margin: 2px 0 0; font-size: 24px; line-height: 1.3; }
.app-head h1 span { margin-left: 10px; padding: 1px 8px; border: 1px solid var(--line); border-radius: 4px; color: var(--ink-muted); font-size: 12px; font-weight: 400; vertical-align: middle; }
.app-lead { margin: 18px 0 0; color: var(--ink-muted); font-size: 15px; line-height: 1.8; }
.app-card { margin-top: 24px; padding: 22px 20px; border: 1px solid var(--line); border-radius: 10px; text-align: center; }
.app-card h2 { margin: 0 0 10px; font-size: 17px; }
.app-card p { margin: 10px 0 0; font-size: 14px; line-height: 1.8; }
.app-note { color: var(--ink-muted); font-size: 13px !important; }
.app-download { display: inline-flex; flex-direction: column; align-items: center; gap: 2px; min-width: 220px; padding: 14px 28px; font-size: 17px; text-decoration: none; }
.app-download small { font-size: 12px; font-weight: 400; opacity: .85; }
.app-qr { display: block; margin: 6px auto 0; border: 1px solid var(--line); border-radius: 8px; }
.app-steps { margin-top: 30px; }
.app-steps h2 { margin: 0 0 10px; font-size: 16px; }
.app-steps ol, .app-steps ul { margin: 0; padding-left: 22px; }
.app-steps li { margin-top: 10px; color: var(--ink-muted); font-size: 14px; line-height: 1.8; }
.app-steps li strong { color: var(--ink); font-weight: 600; }
.mono { word-break: break-all; }
</style>
