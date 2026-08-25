<script setup>
import { computed, ref } from 'vue'
import { AUTONOMY_LEVELS, createBenchmarkSnapshot, createOAuthApplication, createTemplatePackage, decideAgentToolCall, issueAccessToken } from '../domain/ecosystem.js'

const activeTab = ref('overview')
const notice = ref('')
const orgRows = [
  { level: 'HEADQUARTERS', name: '梧曜茶饮集团总部', policy: '品牌语气 / 价格事实锁定', state: '已继承' },
  { level: 'REGION', name: '华东区域', policy: '本地字段可覆盖', state: '可覆盖' },
  { level: 'STORE', name: '杭州城西店', policy: '地址、营业时间、库存', state: '本地执行' },
]
const scopes = ['store.read', 'campaign.read', 'analytics.read']
const tools = [
  { name: 'campaign.write', level: 'L2', state: '已批准可执行', tone: 'safe' },
  { name: 'campaign.publish', level: 'L1', state: '强制人工审批', tone: 'warn' },
  { name: 'payment.create', level: 'L4', state: '平台永久关闭', tone: 'blocked' },
]
const autonomy = computed(() => Object.values(AUTONOMY_LEVELS).map((level) => ({ level, active: level === 'L2', description: level === 'L0' ? '只生成洞察和行动草稿' : level === 'L1' ? '低风险动作也需审批' : level === 'L2' ? '已批准低风险 Tool 可执行' : level === 'L3' ? '仅未来低风险灰度' : '关闭，不允许启用' })))

const runSandbox = () => {
  const app = createOAuthApplication({ id: 'app_sandbox_preview', developerId: 'dev_preview', name: '梧曜沙箱应用', environment: 'SANDBOX', requestedScopes: scopes })
  const token = issueAccessToken(app, { tenantId: 'tenant_demo_001', scopes: ['store.read', 'analytics.read'] })
  const template = createTemplatePackage({ id: 'template_preview', name: '门店周报', version: '1.0.0', nodes: [{ type: 'INPUT' }, { type: 'METRIC' }, { type: 'OUTPUT' }] })
  const benchmark = createBenchmarkSnapshot({ metric: 'content_to_store_visit', participantCount: 8, aggregate: { p50: 0.16 } })
  const decision = decideAgentToolCall({ level: 'L2', tool: 'campaign.write', policy: {}, approved: true })
  notice.value = `Sandbox 运行完成：${token.scopes.length} 个 Scope · 模板 ${template.version} · Benchmark k=${benchmark.kAnonymity} · Agent ${decision.decision}`
}
</script>

<template>
  <div class="ecosystem-page">
    <div class="page-heading ecosystem-heading"><div><p class="eyebrow">OPEN ECOSYSTEM / GOVERNANCE</p><h1>把增长能力，放进可治理的边界里。</h1><p class="page-intro">组织策略、外部应用、模板和 AI 动作都先经过 Scope、版本、预算与审批。这里是第五阶段的 Sandbox 预览，不代表正式开放平台已经上线。</p></div><span class="ecosystem-pill"><span class="status-pulse" /> SANDBOX / INTERNAL</span></div>

    <div class="ecosystem-tabs"><button v-for="tab in [{ key: 'overview', label: '治理总览' }, { key: 'organization', label: '组织与策略' }, { key: 'developer', label: '应用与 Scope' }, { key: 'agent', label: 'Growth OS' }]" :key="tab.key" type="button" :class="{ active: activeTab === tab.key }" @click="activeTab = tab.key">{{ tab.label }}</button></div>

    <section v-if="activeTab === 'overview'" class="ecosystem-grid"><article class="panel governance-hero"><p class="eyebrow accent">CONTROL PLANE</p><h2>三条终局主链路<br /><span>共享同一套治理脉冲。</span></h2><p>总部策略下发、开发者 API 和 Growth OS 执行，都必须留下可验证的来源、Scope、预算和停止记录。</p><button class="primary-button compact" type="button" @click="runSandbox">运行 Sandbox 检查 <span>→</span></button></article><article class="panel chain-panel"><div class="panel-heading"><div><p class="eyebrow">GOVERNANCE CHAIN</p><h3>策略到动作</h3></div><span class="mono muted-text">4 LAYERS</span></div><div class="governance-chain"><div><b>01</b><span>组织范围</span><i>总部 → 区域 → 门店</i></div><div><b>02</b><span>策略快照</span><i>LOCKED / OVERRIDABLE</i></div><div><b>03</b><span>授权 Scope</span><i>最小权限 / 可撤销</i></div><div><b>04</b><span>Agent Policy</span><i>预算 / 审批 / 停止</i></div></div></article><article class="panel benchmark-panel"><div class="panel-heading"><div><p class="eyebrow">PRIVACY-SAFE BENCHMARK</p><h3>匿名群组水位</h3></div><span class="benchmark-state">k ≥ 5</span></div><div class="benchmark-number"><strong>8</strong><span>参与租户 / 内部模拟</span></div><div class="benchmark-bar"><span /></div><p>只展示聚合结果，不暴露任何商家原始数据或可重识别明细。</p></article><article class="panel autonomy-panel"><div class="panel-heading"><div><p class="eyebrow">CONTROLLED AUTONOMY</p><h3>Agent 自主等级</h3></div><span class="mono muted-text">L4 OFF</span></div><div class="autonomy-list"><div v-for="item in autonomy" :key="item.level" :class="{ active: item.active }"><b>{{ item.level }}</b><span>{{ item.description }}</span><i>{{ item.active ? '当前沙箱' : item.level === 'L4' ? '关闭' : '待灰度' }}</i></div></div></article></section>

    <section v-if="activeTab === 'organization'" class="panel ecosystem-detail"><div class="panel-heading"><div><p class="eyebrow">ORGANIZATION POLICY</p><h3>总部 → 区域 → 门店</h3></div><span class="mono muted-text">EffectivePolicySnapshot</span></div><div class="org-list"><div v-for="row in orgRows" :key="row.name" class="org-row"><span class="org-level">{{ row.level }}</span><strong>{{ row.name }}</strong><span>{{ row.policy }}</span><em>{{ row.state }}</em></div></div><p class="detail-note">本地 Mock 已验证策略链路和租户范围判断；真实组织树、联邦协议、审计导出和企业目录尚未接入。</p></section>

    <section v-if="activeTab === 'developer'" class="ecosystem-detail developer-layout"><article class="panel"><div class="panel-heading"><div><p class="eyebrow">SANDBOX APPLICATION</p><h3>梧曜沙箱应用</h3></div><span class="sandbox-state">ACTIVE</span></div><div class="app-meta"><span>环境 <b>Sandbox</b></span><span>应用 ID <b>app_sandbox_preview</b></span></div><div class="scope-list"><p class="eyebrow">APPROVED SCOPES</p><span v-for="scope in scopes" :key="scope">{{ scope }} <i>授权</i></span></div><button class="secondary-button" type="button" @click="notice = 'Token 已在 Sandbox 中撤销，生产凭证不会在页面显示。'">撤销沙箱 Token</button></article><article class="panel"><div class="panel-heading"><div><p class="eyebrow">DEVELOPER GUARDRAILS</p><h3>开放平台尚未开放</h3></div><span class="coming-badge">WAITING FOR API</span></div><div class="guard-list"><div><b>01</b><span>OAuth + PKCE</span><i>待服务端</i></div><div><b>02</b><span>Webhook HMAC</span><i>待 Worker</i></div><div><b>03</b><span>游标分页 / 幂等</span><i>契约已定义</i></div><div><b>04</b><span>生产 Scope 审核</span><i>待权限</i></div></div></article></section>

    <section v-if="activeTab === 'agent'" class="panel ecosystem-detail"><div class="panel-heading"><div><p class="eyebrow">GROWTH OS / TOOL POLICY</p><h3>动作先经过 Policy，再进入执行器</h3></div><span class="mono muted-text">L0–L2 ONLY</span></div><div class="tool-list"><div v-for="tool in tools" :key="tool.name" class="tool-row"><span class="tool-code">{{ tool.name }}</span><b>{{ tool.level }}</b><span>{{ tool.state }}</span><em :class="tool.tone">{{ tool.tone === 'safe' ? 'ALLOWLIST' : tool.tone === 'warn' ? 'APPROVAL' : 'BLOCKED' }}</em></div></div><p class="detail-note">支付、退款、密钥轮换、删除数据、发布和批量消息属于高风险 Tool，当前始终转人工或阻断；L4 永久关闭。</p></section>
    <p v-if="notice" class="ecosystem-notice" role="status">{{ notice }}</p>
  </div>
</template>
