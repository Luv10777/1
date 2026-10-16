<script setup>
// 安卓端的下载页，不用登录就能看。下载那一块和实景直播里的弹窗共用 AppDownloadPanel。
import AppDownloadPanel from '../components/AppDownloadPanel.vue'
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
    <p class="app-lead">只用一台手机开播：抖音在前台直播，一方志在后台读弹幕，用声音回答观众。</p>

    <AppDownloadPanel class="app-panel" />

    <section class="app-steps">
      <h2>第一次使用</h2>
      <ol>
        <li><strong>安装。</strong>提示“来源不明”或有风险时选择继续安装；荣耀、华为手机被“纯净模式”拦住时，按提示选择仍然安装。</li>
        <li><strong>允许在后台运行。</strong>第一次打开会问你，请选允许。不允许的话，切到抖音之后一方志会被手机暂停，播报到一半就没有声音了。</li>
        <li><strong>登录、选门店、开始本场。</strong>账号和网页版是同一个。商品、话术、问答和音色仍在网页版里配置。</li>
        <li><strong>连接直播间。</strong>抖音开播后，在直播间点“分享 → 复制链接”，回到一方志粘贴，点“连接直播间”。连过一次会记住，下次不用再粘贴。</li>
        <li><strong>把媒体音量调到一半以上。</strong>声音是从手机喇叭放出来、再被抖音收进直播间的，音量太小时每段开头的几个字会听不清。</li>
      </ol>
    </section>

    <section class="app-steps">
      <h2>需要知道的</h2>
      <ul>
        <li>这是试用版。目前只在荣耀 X50 上完整地播过，别的手机上遇到声音中断、读不到弹幕等问题，请告诉我们机型。</li>
        <li>读取弹幕用的不是抖音官方接口，抖音改版时可能暂时失效；失效时自动讲解不受影响，只是不再回答弹幕。</li>
        <li>只读取弹幕的文字，不读取观众的昵称和账号，也不需要登录你的抖音账号。</li>
        <li>请按抖音当前的规则，在抖音 App 里标识合成的声音。</li>
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
.app-panel { margin-top: 24px; }
.app-steps { margin-top: 30px; }
.app-steps h2 { margin: 0 0 10px; font-size: 16px; }
.app-steps ol, .app-steps ul { margin: 0; padding-left: 22px; }
.app-steps li { margin-top: 10px; color: var(--ink-muted); font-size: 14px; line-height: 1.8; }
.app-steps li strong { color: var(--ink); font-weight: 600; }
</style>
