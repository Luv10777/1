<script setup>
// 商家联盟的介绍页。目前只有展示：权益和入驻步骤是产品说明，不是业务数据；
// 入驻申请的后端还没做，按钮暂时点了没有反应。

const benefits = [
  { mark: '客', title: '客流互通', text: '同一商圈里不抢生意的店互相带客：奶茶店的顾客看得到隔壁烘焙店的券，反过来也一样。' },
  { mark: '联', title: '联合活动', text: '几家店一起做一场活动，海报、短视频和直播话术由一方志按各家品牌口径分别生成。' },
  { mark: '享', title: '资源共享', text: '联盟内可以共用活动模板和选品经验；每家店自己的顾客数据和经营数据不会互相可见。' },
]
const steps = [
  { title: '提交入驻申请', text: '填写门店、行业和所在商圈。' },
  { title: '平台审核', text: '核对门店信息和经营资质。' },
  { title: '加入商圈联盟', text: '按商圈和行业匹配到合适的联盟。' },
  { title: '参加联合活动', text: '报名或发起活动，开始互相带客。' },
]
</script>

<template>
  <div class="alliance-page">
    <div class="page-heading"><div><p class="eyebrow">PLANS &amp; BENEFITS / ALLIANCE</p><h1>商家联盟</h1><p class="page-intro">同一个商圈里的店，结成联盟一起做活动、互相带客。</p></div><span class="coming-badge">预览 / 尚未开放</span></div>

    <section class="alliance-hero panel-dark">
      <div>
        <p class="eyebrow accent">JOIN THE ALLIANCE</p>
        <h2>一家店的客流有限，<br />一条街的客流可以互通。</h2>
        <p>入驻后，你的门店会按商圈和行业匹配到联盟，和不构成竞争的邻居一起做联合活动。</p>
        <div class="alliance-actions">
          <button class="primary-button" type="button">商家入驻 <span>→</span></button>
        </div>
      </div>
      <div class="alliance-seal" aria-hidden="true">盟</div>
    </section>

    <section class="alliance-benefits">
      <article v-for="item in benefits" :key="item.title" class="panel">
        <span class="alliance-mark">{{ item.mark }}</span>
        <h3>{{ item.title }}</h3>
        <p>{{ item.text }}</p>
      </article>
    </section>

    <section class="panel alliance-steps">
      <div class="panel-heading"><div><p class="eyebrow">HOW TO JOIN</p><h3>入驻流程</h3></div><span class="mono muted-text">04 STEPS</span></div>
      <ol>
        <li v-for="(step, index) in steps" :key="step.title">
          <b>{{ String(index + 1).padStart(2, '0') }}</b>
          <strong>{{ step.title }}</strong>
          <span>{{ step.text }}</span>
        </li>
      </ol>
    </section>
  </div>
</template>

<style scoped>
.alliance-hero { display: grid; grid-template-columns: 1fr auto; align-items: center; gap: 32px; padding: 36px; }
.alliance-hero h2 { margin: 14px 0 12px; font: 600 26px/1.5 var(--font-serif); letter-spacing: .01em; }
.alliance-hero p:not(.eyebrow) { max-width: 520px; margin: 0; color: var(--ink-muted); font-size: 13px; line-height: 1.8; }
.alliance-actions { display: flex; align-items: center; gap: 16px; margin-top: 24px; }
.alliance-seal { display: grid; place-items: center; width: 128px; height: 128px; border: 2px solid var(--color-accent); border-radius: 8px; color: var(--color-accent-text); font: 600 64px var(--font-serif); }
.alliance-benefits { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; margin-top: 20px; }
.alliance-mark { display: grid; place-items: center; width: 36px; height: 36px; border: 1px solid var(--color-accent); border-radius: 4px; color: var(--color-accent-text); font: 600 17px var(--font-serif); }
.alliance-benefits h3 { margin: 16px 0 8px; font-size: 16px; font-weight: 500; }
.alliance-benefits p { margin: 0; color: var(--ink-muted); font-size: 13px; line-height: 1.8; }
.alliance-steps { margin-top: 20px; }
.alliance-steps ol { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin: 18px 0 0; padding: 0; list-style: none; }
.alliance-steps li { display: grid; gap: 6px; padding-top: 14px; border-top: 1px solid var(--line); }
.alliance-steps b { color: var(--color-accent-text); font: 500 11px var(--font-mono); }
.alliance-steps strong { font-size: 14px; font-weight: 500; }
.alliance-steps li span { color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
@media (max-width: 900px) {
  .alliance-benefits { grid-template-columns: 1fr; }
  .alliance-steps ol { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 640px) {
  .alliance-hero { grid-template-columns: 1fr; padding: 24px; }
  .alliance-seal { display: none; }
  .alliance-actions { flex-wrap: wrap; }
}
</style>
