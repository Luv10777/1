import test from 'node:test'
import assert from 'node:assert/strict'
import { LEGAL_ENTITY, LEGAL_POLICY_UPDATED_AT, LEGAL_REFERENCES, LEGAL_POLICY_NAVIGATION, legalPolicies } from './legalPolicies.js'

test('each published policy is substantive, dated, navigable, and cites official legal sources', () => {
  assert.equal(LEGAL_POLICY_UPDATED_AT, '2026-10-18')
  assert.equal(LEGAL_ENTITY.name, '嘉兴市梧曜科技有限公司')
  assert.equal(LEGAL_ENTITY.creditCode, '91330483MAKMYJHC7U')
  assert.equal(LEGAL_ENTITY.address, '浙江省嘉兴市桐乡市濮院镇园区大道799号7楼704')
  assert.equal(LEGAL_ENTITY.phone, '15157376602')
  assert.deepEqual(LEGAL_POLICY_NAVIGATION.map(({ slug }) => slug), ['terms', 'privacy', 'merchant', 'aftersales', 'identity', 'publishing'])

  for (const { slug, shortTitle } of LEGAL_POLICY_NAVIGATION) {
    const policy = legalPolicies[slug]
    assert.equal(policy.slug, slug)
    assert.ok(shortTitle.length > 1)
    assert.ok(policy.title.length > 1)
    assert.ok(policy.summary.length >= 30)
    assert.ok(policy.effective.length >= 25)
    assert.ok(policy.sections.length >= 4, `${slug} should contain operative articles`)
    assert.ok(policy.sections.every(section => section.title.length >= 4 && section.paragraphs.every(text => text.length >= 40)), `${slug} must not contain placeholder articles`)
    assert.ok(policy.citations.length >= 1, `${slug} should cite supporting law`)
  }

  assert.ok(LEGAL_REFERENCES.every(({ title, organization, url }) => title.length > 5 && organization.length > 5 && new URL(url).protocol === 'https:'), 'official references should include the law, issuing agency, and HTTPS source')
  assert.ok(legalPolicies.privacy.sections.some(section => section.paragraphs.some(text => text.includes('AES-256-GCM'))), 'identity security claims must match the implementation')
  assert.ok(legalPolicies.aftersales.sections.some(section => section.paragraphs.some(text => text.includes('收款码付款由商家人工核实到账'))), 'the physical-goods refund policy must describe actual payment status')
  assert.ok(Object.values(legalPolicies).every(policy => policy.citations.every(reference => LEGAL_REFERENCES.includes(reference))), 'every policy must reference an existing source')
})

test('published agreements exclude AI provisions as requested by the operator', () => {
  assert.equal(legalPolicies.ai, undefined)
  assert.doesNotMatch(JSON.stringify(legalPolicies), /AI|人工智能|OnlyRouter|DashScope|CosyVoice|模型|训练|声音克隆/i)
})
