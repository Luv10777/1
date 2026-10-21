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
    const { allianceApi, allianceAdminApi, yuan, discountLabel } = await vite.ssrLoadModule('/src/services/allianceApi.js')
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
      () => allianceAdminApi.review({ tenantId: 7, offer: { id: 9 } }, true, '', 8500),
      () => allianceAdminApi.resolveDispute({ holderTenantId: 8, ref }, true, ''),
      () => allianceAdminApi.statementPaid({ tenantId: 7, statement: { id: 4 } }, 'BANK-1', '2026-10-20'),
      () => allianceAdminApi.statements({ status: '', period: '2026-09' }),
      () => allianceApi.saveAccount({ accountType: 'CORPORATE' }),
      () => allianceAdminApi.generateStatements('2026-09'),
    ]) await call()
    const paths = calls.map(call => decodeURIComponent(new URL(call.url, 'http://local').pathname))
    assert.deepEqual(paths, [
      '/api/alliance/pool', '/api/alliance/offers/9/claims', `/api/alliance/vouchers/${ref}`,
      `/api/alliance/vouchers/${ref}/dispute`, '/api/alliance/offers', '/api/alliance/offers/9/withdraw',
      '/api/stores/3/alliance-redemptions/preview', '/api/stores/3/alliance-redemptions',
      '/api/platform/tenants/7/alliance/offers/9/review', '/api/platform/tenants/7/alliance/offers/9/review',
      `/api/platform/tenants/8/alliance/vouchers/${ref}/dispute-resolution`,
      '/api/platform/tenants/7/alliance/statements/4/paid', '/api/platform/alliance/statements',
      '/api/alliance/settlement-account', '/api/platform/alliance/statements/generate',
    ])
    assert.equal(decodeURIComponent(new URL(calls[0].url, 'http://local').search), '?city=嘉兴&page=0')
    assert.equal(decodeURIComponent(new URL(calls[12].url, 'http://local').search), '?period=2026-09', 'empty filters are not sent')
    assert.equal(calls[13].options.method, 'PUT')
    assert.deepEqual(JSON.parse(calls[11].options.body), { reference: 'BANK-1', paidDate: '2026-10-20' })
    assert.deepEqual(JSON.parse(calls[6].options.body), { code: '12345678' })
    assert.deepEqual(JSON.parse(calls[8].options.body), { approved: false, note: '定价偏高' })
    assert.deepEqual(JSON.parse(calls[9].options.body), { approved: true, note: '', settleRateBp: 8500 })
    assert.equal(yuan(15000), '150.00')
    assert.equal(discountLabel(5000), '5 折')
    assert.equal(discountLabel(8500), '8.5 折')
  } finally {
    globalThis.fetch = originalFetch
    await vite.close()
  }
})
