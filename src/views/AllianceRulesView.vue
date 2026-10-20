<script setup>
import { computed, ref } from 'vue'
import { RefreshCw, Store, Ticket } from 'lucide-vue-next'
import { allianceApi, yuan } from '../services/allianceApi'
import '../alliance.css'

const rules = ref(null)
const error = ref('')
const loading = ref(false)

// 结算比例按「折」说：5000 万分比 → 5 折，8500 → 8.5 折。
const discount = computed(() => rules.value ? `${(rules.value.settleRateBp / 1000).toFixed(1).replace(/\.0$/, '')} 折` : '')
const exampleFace = 3000
const exampleSettle = computed(() => rules.value ? Math.floor(exampleFace * rules.value.settleRateBp / 10000) : 0)

async function load() {
  loading.value = true
  error.value = ''
  try { rules.value = await allianceApi.rules() }
  catch (failure) { error.value = failure.message || '规则暂时无法读取。' }
  finally { loading.value = false }
}

load()
</script>

<template>
  <div class="business-page alliance-rules-page">
    <header class="business-heading">
      <div><h1>联盟券规则</h1><p>联盟券是商户联盟里，会员商户之间互相赠送的商品兑换券：一张券兑一件指定商品，到那家门店出示券码使用。</p></div>
      <div class="business-heading-links">
        <a href="#for-holders" class="business-link">我要领券</a>
        <a href="#for-suppliers" class="business-link">我要供券</a>
      </div>
    </header>

    <p v-if="loading && !rules" class="alliance-state" role="status">正在读取规则…</p>
    <div v-else-if="error" class="alliance-state" role="alert">
      <h3>规则暂时无法读取</h3><p>{{ error }}</p>
      <button type="button" class="business-button" @click="load"><RefreshCw :size="14" aria-hidden="true" />重新读取</button>
    </div>
    <template v-else-if="rules">
      <section class="alliance-panel alliance-rules-flow" aria-labelledby="rules-flow-title">
        <h2 id="rules-flow-title">一张券怎么走完</h2>
        <ol class="alliance-rules-steps">
          <li><strong>供券</strong><span>入驻商户拿自家门店的一件商品提供券，平台审核</span></li>
          <li><strong>领券</strong><span>会员商户用本期额度在领券中心挑选</span></li>
          <li><strong>到店核销</strong><span>出示券码，店员核对后当场交付</span></li>
          <li><strong>结算</strong><span>过了 {{ rules.disputeDays }} 天申诉期，平台按面值 {{ discount }}结算给供券商户</span></li>
        </ol>
      </section>

      <section id="for-holders" class="alliance-panel alliance-rules-section" aria-labelledby="rules-holder-title">
        <h2 id="rules-holder-title"><Ticket :size="18" aria-hidden="true" />领券商户</h2>
        <h3>额度</h3>
        <ul>
          <li>开通或续费会员后，按套餐发放本期联盟券额度，各套餐的额度见<RouterLink :to="{ name: 'billing' }">套餐与权益</RouterLink>。</li>
          <li>额度只在本期会员期内可用，期满作废，<strong>不累积</strong>。提前续费的，下一期额度在本期结束后才生效。</li>
          <li>领一张券，扣掉这张券面值的额度。</li>
        </ul>
        <h3>领券</h3>
        <ul>
          <li>在<RouterLink :to="{ name: 'alliance-pool' }">领券中心</RouterLink>自己挑，先到先得，领完即止。</li>
          <li>同一家供券商户的券，每期最多领 <strong>{{ rules.perSupplierLimit }} 张</strong>。</li>
          <li>不能领自己商户的券，也不能领同一营业执照主体下其他商户的券。</li>
        </ul>
        <h3>使用</h3>
        <ul>
          <li>领取后 <strong>{{ rules.voucherValidDays }} 天内</strong>，到券上写明的那家门店使用；同一商户的其他门店也不行。</li>
          <li>在<RouterLink :to="{ name: 'alliance-vouchers' }">我的联盟券</RouterLink>里出示二维码或 8 位券码，店员核对后核销，当场交付商品或服务。</li>
          <li>券码等同于券，请不要截图转给他人。联盟券不能转让、不能兑换现金。</li>
          <li>到期没用的券自动作废，已扣的额度不退回。</li>
        </ul>
        <h3>申诉</h3>
        <ul>
          <li>核销后 <strong>{{ rules.disputeDays }} 天内</strong>，如果券被核销了却没拿到商品，可以在「我的联盟券」里申诉。</li>
          <li>平台核实期间，这笔核销不会结算给对方。判定核销无效的，券恢复可用，有效期不延长。</li>
        </ul>
      </section>

      <section id="for-suppliers" class="alliance-panel alliance-rules-section" aria-labelledby="rules-supplier-title">
        <h2 id="rules-supplier-title"><Store :size="18" aria-hidden="true" />供券商户</h2>
        <h3>条件</h3>
        <ul>
          <li>需要先通过<RouterLink :to="{ name: 'merchant-join' }">商家入驻</RouterLink>审核。供券完全自愿。</li>
        </ul>
        <h3>提交</h3>
        <ul>
          <li>在<RouterLink :to="{ name: 'alliance-offers' }">供券管理</RouterLink>选本店的一件在售商品、填写数量，提交后由平台审核定价和数量。</li>
          <li>券的面值等于提交时的商品售价。之后改价，不影响已经发出的券。</li>
          <li>单个商品的供券数量不超过当前库存的 <strong>{{ rules.inventorySharePercent }}%</strong>；库存不限的商品，一次最多 <strong>{{ rules.unlimitedInventoryCap }} 张</strong>。</li>
          <li>每月提供的券，面值合计不超过 <strong>¥{{ yuan(rules.monthlyFaceCapMinor) }}</strong>。</li>
        </ul>
        <h3>库存与撤回</h3>
        <ul>
          <li>提交时就预留对应数量的库存，免得有券没货。平台驳回或你撤回时，没被领走的部分还回库存。</li>
          <li>没被领走的券随时可以撤回；已经领出去的券，仍要在门店照常兑现。</li>
          <li>还有券没用完时，对应的商品不能删除，门店不能关闭，商户也不能整体关闭。</li>
          <li>平台暂停你的入驻后，没被领走的券自动撤回；已领出去的券仍要兑现。</li>
        </ul>
        <h3>门店核销</h3>
        <ul>
          <li>顾客到店出示券码，店员在<RouterLink :to="{ name: 'alliance-redeem' }">门店核销</RouterLink>扫码或输入 8 位数字。</li>
          <li>先核对商品名称和领券商户，确认无误后再核销，并当场交付。</li>
          <li>同一店员连续输错 {{ rules.redeemAttemptLimit }} 次，暂停核销 {{ rules.redeemLockMinutes }} 分钟。</li>
        </ul>
        <h3>结算</h3>
        <ul>
          <li>核销后先过 <strong>{{ rules.disputeDays }} 天申诉期</strong>；期满且没有申诉，平台按面值的 <strong>{{ discount }}</strong>结算，例如面值 ¥{{ yuan(exampleFace) }} 的券结算 ¥{{ yuan(exampleSettle) }}。</li>
          <li>结算按月线下打款，可在「供券管理 → 结算单」查看每一笔的状态。</li>
          <li>领券商户申诉期间，这笔结算冻结；平台判定核销无效的，这笔结算作废。</li>
        </ul>
      </section>
    </template>
  </div>
</template>
