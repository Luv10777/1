<script setup>
import { computed, ref } from 'vue'
import { creative } from '../stores/creative.js'
import { WORKFLOW_STATES } from '../domain/creative.js'

const examples = [
  '给梧曜咖啡店新出的 29.9 元双人下午茶做 6 张小红书风格图片，年轻情侣、温暖高级、周末到店，不改产品外观。',
  '为青岚茶事城西店做一组夏日新品内容，突出真实价格、清爽口感和下班后的放松感。',
]
const contentCount = ref(6)
const channels = ref(['小红书', '抖音'])
const brief = ref('')
const isPlanned = computed(() => creative.phase === 'planned' || creative.phase === 'confirmed' || creative.phase === 'running' || creative.phase === 'qa' || creative.phase === 'needs_review')
const completedPercent = computed(() => creative.batch ? Math.round((creative.batch.completed / creative.batch.count) * 100) : 0)

const interpret = async () => { if (brief.value.trim()) await creative.interpret(brief.value, { contentCount: contentCount.value, channels: channels.value }) }
const useExample = (value) => { brief.value = value }
const confirmPlan = () => creative.confirm(contentCount.value)
const startBatch = () => creative.start()
</script>

<template>
  <div class="creative-page">
    <div class="page-heading creative-heading"><div><p class="eyebrow">AI CREATIVE COMPILER / PHASE 2</p><h1>一句话，开始一场增长行动。</h1><p class="page-intro">描述你的目标，梧曜星枢会先理解事实和约束，再给出可确认的内容计划。没有确认，不会消耗生成额度。</p></div><span class="coming-badge">MOCK PROVIDER / SAFE PREVIEW</span></div>

    <section class="creative-layout">
      <article class="panel brief-panel"><div class="panel-heading"><div><p class="eyebrow accent">01 / CAMPAIGN BRIEF</p><h3>你想让这次内容完成什么？</h3></div><span class="mono muted-text">{{ brief.length }}/500</span></div><textarea v-model="brief" maxlength="500" placeholder="例如：给梧曜咖啡店新出的 29.9 元双人下午茶做 6 张小红书风格图片和 3 条短视频……" /><div class="example-list"><button v-for="example in examples" :key="example" class="example-chip" @click="useExample(example)">{{ example }}</button></div><div class="brief-options"><label><span class="eyebrow">输出数量</span><select v-model.number="contentCount"><option :value="3">3 个内容单元</option><option :value="6">6 个内容单元</option><option :value="12">12 个内容单元</option></select></label><div><span class="eyebrow">目标平台</span><div class="channel-row"><button v-for="channel in ['小红书','抖音','朋友圈']" :key="channel" class="channel-chip" :class="{ selected: channels.includes(channel) }" @click="channels = channels.includes(channel) ? channels.filter(item => item !== channel) : [...channels, channel]">{{ channel }}</button></div></div></div><button class="primary-button creative-submit" :disabled="creative.loading || !brief.trim()" @click="interpret">{{ creative.loading && creative.phase === 'interpreting' ? '正在理解…' : '开始理解需求' }} <span>→</span></button><p v-if="creative.error" class="form-message error">{{ creative.error }}</p></article>

      <article class="panel context-panel"><div class="panel-heading"><div><p class="eyebrow">CONTEXT SNAPSHOT</p><h3>当前工作上下文</h3></div><span class="status-pulse" /></div><div class="context-stack"><div class="context-row"><span>租户</span><strong>梧曜增长实验室</strong></div><div class="context-row"><span>商家 / 门店</span><strong>青岚茶事 · 杭州城西店</strong></div><div class="context-row"><span>品牌规则</span><strong class="context-ready">已加载 12 条</strong></div><div class="context-row"><span>真实事实</span><strong class="context-ready">价格与商品已确认</strong></div></div><div class="context-note"><span class="eyebrow">FACT BOUNDARY</span><p>价格、地址、营业时间等事实只来自已确认的商家资料；缺失的关键事实会阻断计划，不由模型猜测。</p></div></article>
    </section>

    <section v-if="isPlanned" class="plan-section"><div class="section-label"><p class="eyebrow accent">02 / AI UNDERSTANDING</p><span class="mono">{{ creative.campaign?.id }}</span></div><div class="plan-grid"><article class="panel plan-summary"><div class="plan-status"><span class="status-pulse" /> 已完成理解 <span class="mono">TEXT_PLANNER</span></div><h2>{{ creative.campaign?.plan?.intent || '本地生活增长活动' }}</h2><p>系统将围绕 <strong>{{ creative.campaign?.plan?.audience }}</strong>，在 {{ creative.campaign?.plan?.channels?.join(' / ') }} 输出 {{ creative.campaign?.plan?.contentCount }} 个内容单元。</p><div class="plan-tags"><span v-for="tag in [creative.campaign?.plan?.tone, ...(creative.campaign?.plan?.channels || [])]" :key="tag">{{ tag }}</span></div></article><article class="panel plan-facts"><p class="eyebrow">CONFIRMED FACTS</p><div v-for="fact in creative.campaign?.facts" :key="fact.id" class="fact-row"><span class="fact-check">✓</span><span>{{ fact.field }}</span><strong>{{ fact.value }}</strong></div><div v-if="creative.campaign?.missingFacts?.length" class="missing-facts"><span class="eyebrow">MISSING / NEED CONFIRMATION</span><p>{{ creative.campaign.missingFacts.join('、') }}</p></div></article></div></section>

    <section v-if="creative.phase === 'planned'" class="confirm-bar panel"><div><p class="eyebrow">03 / CONFIRM & RESERVE</p><h3>计划清晰后，再开始生成。</h3><p>预计 {{ contentCount * 8 }} credits · Mock Provider · 可逐项重试</p></div><div class="confirm-actions"><button class="text-link muted" @click="creative.reset">重新描述</button><button class="primary-button compact" @click="confirmPlan">确认计划并预占额度 <span>→</span></button></div></section>

    <section v-if="creative.batch" class="batch-section"><div class="section-label"><p class="eyebrow accent">04 / BATCH EXECUTION</p><span class="batch-state mono">{{ creative.batch.state }}</span></div><article class="panel batch-panel"><div class="batch-header"><div><h2>{{ creative.batch.count }} 个内容单元</h2><p>成功项不会因失败项重试而重复计费。</p></div><div class="batch-progress"><strong>{{ completedPercent }}%</strong><span>{{ creative.batch.completed }} / {{ creative.batch.count }} 已完成</span></div></div><div class="progress-track"><span :style="{ width: `${completedPercent}%` }" /></div><div class="batch-items"><div v-for="item in creative.items" :key="item.id" class="batch-item"><span class="item-index">{{ String(item.index + 1).padStart(2, '0') }}</span><span class="item-kind">IMAGE_PRIMARY</span><span class="item-status" :class="item.state.toLowerCase()">{{ item.state === 'SUCCEEDED' ? '生成完成' : item.state === 'FAILED' ? '需要重试' : item.state }}</span><button v-if="item.state === 'FAILED'" class="row-arrow" aria-label="重试">↻</button></div></div><button v-if="creative.phase === 'confirmed'" class="primary-button batch-start" :disabled="creative.loading" @click="startBatch">{{ creative.loading ? '队列执行中…' : '启动批量生成' }} <span>→</span></button><div v-if="creative.phase === 'qa'" class="qa-callout"><span class="status-pulse" /><div><strong>批量生成完成，等待质量检查</strong><p>下一步将检查产品一致性、文字风险、构图与平台比例。</p></div><span class="mono">QA READY</span></div></article></section>
  </div>
</template>
