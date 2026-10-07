// Enabled FAQ rule sets exposed to live configuration. The knowledge page can
// replace this local source with its API response without changing the UI.
const storageKey = 'wuyao-enabled-store-faqs-v1'
const enabledRuleSets = [
  {
    id: 'kb_20240824_03',
    name: '高频售前问题集',
    pairs: [
      { id: 'sale-pair-01', question: '你们支持哪些配送方式？', answer: '目前支持门店自提、同城配送和全国物流配送。下单时可根据收货地址选择对应方式。', status: 'active' },
      { id: 'sale-pair-02', question: '购买后可以无理由退换吗？', answer: '商品签收后 7 天内，在保持商品完好、配件齐全的情况下支持无理由退换。', status: 'active' },
    ],
  },
  {
    id: 'faq-hub',
    name: '门店服务流程标准',
    pairs: [
      { id: 'hub-pair-02', question: '门店营业时间是几点？', answer: '常规营业时间为每日 10:00–22:00，不同门店可能略有差异，请以门店页信息为准。', status: 'active' },
      { id: 'hub-pair-01', question: '如何联系在线客服？', answer: '进入订单详情后点击“联系在线客服”，也可以在工作时间拨打服务热线获得帮助。', status: 'active' },
    ],
  },
]

function cloneRuleSets(ruleSets) {
  return ruleSets.map(ruleSet => ({
    ...ruleSet,
    pairs: ruleSet.pairs.filter(pair => pair.status !== 'draft').map(pair => ({ ...pair })),
  }))
}

function readRuleSets() {
  if (typeof localStorage === 'undefined') return cloneRuleSets(enabledRuleSets)
  try {
    const stored = localStorage.getItem(storageKey)
    if (!stored) return cloneRuleSets(enabledRuleSets)
    const parsed = JSON.parse(stored)
    if (!Array.isArray(parsed)) return cloneRuleSets(enabledRuleSets)
    return cloneRuleSets(parsed)
  } catch {
    return cloneRuleSets(enabledRuleSets)
  }
}

export function saveEnabledRuleSets(ruleSets) {
  if (typeof localStorage === 'undefined') return
  localStorage.setItem(storageKey, JSON.stringify(cloneRuleSets(ruleSets)))
  window.dispatchEvent(new CustomEvent('knowledge-faqs-updated'))
}

export function getEnabledStoreFaqs() {
  return readRuleSets().flatMap(ruleSet => ruleSet.pairs.map(pair => ({
    id: pair.id,
    q: pair.question,
    a: pair.answer,
    scope: 'store',
    source: ruleSet.name,
    sourceType: 'store',
  })))
}

export function addStoreFaq(pair, ruleSetId = 'faq-hub', ruleSetName = '常见问答集') {
  const ruleSets = readRuleSets()
  let ruleSet = ruleSets.find(item => item.id === ruleSetId)
  if (!ruleSet) {
    ruleSet = { id: ruleSetId, name: ruleSetName, pairs: [] }
    ruleSets.push(ruleSet)
  }
  const normalized = { id: pair.id || `store-faq-${Date.now()}`, question: pair.question, answer: pair.answer, status: 'active' }
  const existing = ruleSet.pairs.findIndex(item => item.id === normalized.id)
  if (existing >= 0) ruleSet.pairs.splice(existing, 1, normalized)
  else ruleSet.pairs.push(normalized)
  saveEnabledRuleSets(ruleSets)
}

export function getEnabledRuleSets() {
  return readRuleSets()
}
