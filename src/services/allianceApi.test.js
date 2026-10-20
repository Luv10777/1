import test from 'node:test'
import assert from 'node:assert/strict'

test('alliance API keeps merchant, store redemption and platform routes apart and never sends empty filters', async () => {
  const calls = []
  const { createServer } = await import('vite')
  const vite = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    globalThis.fetch = async (url, options) => {
      calls.push({ url, options })
      return { ok: true, status: 200, statusText: 'OK', json: async () => ({ code: 200, data: {} }) }
    }
    const { allianceApi, allianceAdminApi, yuan } = await vite.ssrLoadModule('/src/services/allianceApi.js')
    const ref = '0b6f1d2e-0000-4000-8000-000000000001'
    for (const call of [
      () => allianceApi.pool({ keyword: '', city: '嘉兴', page: 0 }),
      () => allianceApi.claim(9),
      () => allianceApi.voucher(ref),
      () => allianceApi.dispute(ref, '没拿到商品'),
      () => allianceApi.createOffer({ storeId: 3, productId: 5, quantity: 2 }),
      () => allianceApi.withdrawOffer(9),
      () => allianceApi.previewRedemption(3, '12345678'),
      () => allianceApi.redeem(3, '12345678'),
      () => allianceAdminApi.review({ tenantId: 7, offer: { id: 9 } }, false, '定价偏高'),
      () => allianceAdminApi.resolveDispute({ holderTenantId: 8, ref }, true, ''),
      () => allianceAdminApi.markPaid({ tenantId: 7, settlement: { id: 4 } }, 'BANK-1'),
      () => allianceAdminApi.settlements(''),
    ]) await call()
    const paths = calls.map(call => decodeURIComponent(new URL(call.url, 'http://local').pathname))
    assert.deepEqual(paths, [
      '/api/alliance/pool', '/api/alliance/offers/9/claims', `/api/alliance/vouchers/${ref}`,
      `/api/alliance/vouchers/${ref}/dispute`, '/api/alliance/offers', '/api/alliance/offers/9/withdraw',
      '/api/stores/3/alliance-redemptions/preview', '/api/stores/3/alliance-redemptions',
      '/api/platform/tenants/7/alliance/offers/9/review', `/api/platform/tenants/8/alliance/vouchers/${ref}/dispute-resolution`,
      '/api/platform/tenants/7/alliance/settlements/4/paid', '/api/platform/alliance/settlements',
    ])
    assert.equal(decodeURIComponent(new URL(calls[0].url, 'http://local').search), '?city=嘉兴&page=0')
    assert.equal(new URL(calls[11].url, 'http://local').search, '')
    assert.deepEqual(JSON.parse(calls[6].options.body), { code: '12345678' })
    assert.deepEqual(JSON.parse(calls[8].options.body), { approved: false, note: '定价偏高' })
    assert.equal(yuan(15000), '150.00')
  } finally {
    globalThis.fetch = originalFetch
    await vite.close()
  }
})
