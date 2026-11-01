export function isMobilePayment() {
  const browser = globalThis.navigator
  return /Android|iPhone|iPad|iPod|Mobile/i.test(browser?.userAgent || '')
    || (browser?.platform === 'MacIntel' && browser.maxTouchPoints > 1)
}

// Mobile stays on the checkout until the signed URL is ready; no blank popup is created.
export async function openBillingCashier(getUrl, { mobile = isMobilePayment(), beforeNavigate = async () => {} } = {}) {
  const tab = mobile ? null : window.open('', '_blank')
  if (!mobile && !tab) throw new Error('浏览器拦截了新窗口，请允许本站弹出窗口后重试')
  try {
    if (tab) tab.opener = null
    const url = await getUrl(mobile)
    await beforeNavigate()
    if (tab) tab.location.href = url
    else window.location.assign(url)
  } catch (error) {
    tab?.close()
    throw error
  }
}
