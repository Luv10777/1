<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { Clock, MapPin, Phone, RefreshCw, Search, Store, Ticket } from 'lucide-vue-next'
import { allianceApi, yuan } from '../services/allianceApi'
import '../alliance.css'

const PAGE_SIZE = 12
const quota = ref(null)
const rules = ref(null)
const feed = ref({ items: [], total: 0, totalPages: 0 })
const page = ref(0)
const keyword = ref('')
const city = ref('')
const loading = ref(false)
const error = ref('')
const notice = ref(null)
const claimingId = ref(null)
let generation = 0

const current = computed(() => quota.value?.current || null)
const usedPercent = computed(() => current.value?.amountMinor ? Math.min(100, Math.round(current.value.usedMinor / current.value.amountMinor * 100)) : 0)
const pages = computed(() => Math.max(1, feed.value.totalPages || 1))
const date = value => value ? new Date(value).toLocaleDateString('zh-CN') : ''

async function load() {
  const attempt = ++generation
  loading.value = true
  error.value = ''
  try {
    const [nextRules, nextQuota, nextFeed] = await Promise.all([
      allianceApi.rules(),
      allianceApi.quota(),
      allianceApi.pool({ keyword: keyword.value.trim(), city: city.value.trim(), page: page.value, size: PAGE_SIZE }),
    ])
    if (attempt !== generation) return
    rules.value = nextRules
    quota.value = nextQuota
    feed.value = nextFeed
  } catch (failure) {
    if (attempt === generation) error.value = failure.message || '领券中心暂时无法读取。'
  } finally {
    if (attempt === generation) loading.value = false
  }
}

function search() { page.value = 0; return load() }
function go(next) { page.value = next; return load() }

async function claim(item) {
  if (claimingId.value) return
  if (!window.confirm(`领取后扣除本期额度 ¥${yuan(item.faceValueMinor)}。\n这张券需要到「${item.storeName}」门店出示券码使用，领取后 ${rules.value?.voucherValidDays ?? 30} 天内有效。确定领取吗？`)) return
  claimingId.value = item.offerId
  error.value = ''
  notice.value = null
  try {
    const voucher = await allianceApi.claim(item.offerId)
    if (voucher.status === 'ACTIVE') notice.value = { text: `已领取「${voucher.productName}」，到店时出示券码即可使用。`, link: true }
    else if (voucher.status === 'CLAIMING') notice.value = { text: '正在确认名额，稍后可在「我的联盟券」查看结果。', link: true }
    else error.value = '这张券刚刚被领完，额度已退回，可以换一张。'
    await load()
  } catch (failure) {
    error.value = failure.message || '领取失败，请稍后再试。'
  } finally {
    claimingId.value = null
  }
}

load()
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <section class="alliance-voucher-center" aria-labelledby="alliance-center-title">
    <h2 id="alliance-center-title" class="alliance-sr-only">领券中心</h2>
    <div class="alliance-panel">
      <div v-if="current" class="alliance-quota">
        <div>
          <p class="alliance-muted">{{ current.planName }} · 本期联盟券额度，{{ date(current.validUntil) }} 到期，不累积</p>
          <strong>¥{{ yuan(current.remainingMinor) }}</strong>
          <p class="alliance-muted">共 ¥{{ yuan(current.amountMinor) }}，已用 ¥{{ yuan(current.usedMinor) }}；同一商户的券每期最多领 {{ quota.perSupplierLimit }} 张</p>
        </div>
        <RouterLink :to="{ name: 'alliance-vouchers' }" class="business-link">我的联盟券</RouterLink>
        <div class="alliance-quota-bar" aria-hidden="true"><span :style="{ width: `${usedPercent}%` }" /></div>
      </div>
      <div v-else class="alliance-panel-heading">
        <div>
          <h2>开通会员，每期可领联盟券</h2>
          <p class="alliance-muted">{{ quota?.upcoming?.length ? `下一期额度将于 ${date(quota.upcoming[0].validFrom)} 生效。` : '会员期内可以用额度领取其他联盟商户的商品兑换券，到店核销。' }}</p>
        </div>
        <RouterLink :to="{ name: 'billing' }" class="business-button business-button-primary">查看会员套餐</RouterLink>
      </div>
    </div>

    <p v-if="notice" class="alliance-notice" role="status">{{ notice.text }}<RouterLink v-if="notice.link" :to="{ name: 'alliance-vouchers' }">去看看</RouterLink></p>
    <p v-if="error" class="alliance-error" role="alert">{{ error }}</p>

    <form class="alliance-filters" role="search" @submit.prevent="search">
      <label><Search :size="15" aria-hidden="true" /><input v-model="keyword" type="search" maxlength="80" placeholder="商品或门店名称" aria-label="搜索商品或门店" /></label>
      <label><MapPin :size="15" aria-hidden="true" /><input v-model="city" type="search" maxlength="40" placeholder="城市，例如 嘉兴" aria-label="按城市筛选" /></label>
      <button type="submit" class="business-button" :disabled="loading">{{ loading ? '读取中…' : '筛选' }}</button>
    </form>

    <p v-if="loading && !feed.items.length" class="alliance-state" role="status">正在读取券库…</p>
    <div v-else-if="!feed.items.length" class="alliance-state">
      <Ticket :size="30" aria-hidden="true" />
      <h3>{{ keyword || city ? '没有找到合适的券' : '券库里暂时没有可领的券' }}</h3>
      <p>联盟商户提供的券审核通过后会出现在这里。</p>
      <button type="button" class="business-button" @click="load"><RefreshCw :size="14" aria-hidden="true" />刷新</button>
    </div>
    <template v-else>
      <ul class="alliance-card-grid" :aria-busy="loading">
        <li v-for="item in feed.items" :key="item.offerId">
          <article class="alliance-voucher-card">
            <header><h3>{{ item.productName }}</h3><span class="alliance-face">¥{{ yuan(item.faceValueMinor) }}</span></header>
            <ul class="alliance-facts">
              <li><Store :size="13" aria-hidden="true" />{{ item.storeName }} · {{ item.supplierName }}</li>
              <li v-if="item.storeAddress"><MapPin :size="13" aria-hidden="true" />{{ item.storeAddress }}</li>
              <li v-if="item.storeHours"><Clock :size="13" aria-hidden="true" />{{ item.storeHours }}</li>
              <li v-if="item.storePhone"><Phone :size="13" aria-hidden="true" />{{ item.storePhone }}</li>
            </ul>
            <footer>
              <span class="alliance-tag is-warning">需到店使用 · 剩 {{ item.remaining }} 张</span>
              <button type="button" class="business-button business-button-primary" :disabled="!current || claimingId === item.offerId || current.remainingMinor < item.faceValueMinor" @click="claim(item)">
                {{ claimingId === item.offerId ? '领取中…' : !current ? '需开通会员' : current.remainingMinor < item.faceValueMinor ? '额度不足' : '领取' }}
              </button>
            </footer>
          </article>
        </li>
      </ul>
      <nav v-if="pages > 1" class="alliance-pager" aria-label="券库分页">
        <button type="button" :disabled="page <= 0 || loading" @click="go(page - 1)">上一页</button>
        <span>{{ page + 1 }} / {{ pages }}</span>
        <button type="button" :disabled="page + 1 >= pages || loading" @click="go(page + 1)">下一页</button>
      </nav>
    </template>
  </section>
</template>
