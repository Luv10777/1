<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { imageApi, downloadImage } from '../services/imageCreation'
import { IMAGE_STATUS } from '../domain/imageCreation'
const props = defineProps({ mode: { type: String, default: 'works' } })
const records = ref([])
const workflow = ref('POSTER')
const page = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const error = ref('')
let timer
let alive = true
let sequence = 0
const link = row => ({ path: row.workflow === 'PRODUCT_SET' ? '/image/create/product-set' : '/image/create/poster', query: { creation: row.creationId || row.id } })
async function load(next = page.value) {
  clearTimeout(timer)
  const round = ++sequence
  loading.value = true; error.value = ''
  try {
    const data = props.mode === 'works' ? await imageApi.works(next) : await imageApi.history(workflow.value, next)
    if (!alive || round !== sequence) return
    records.value = data.items; page.value = next; totalPages.value = data.totalPages
  } catch (e) { if (alive && round === sequence) error.value = e.message }
  finally {
    if (alive && round === sequence) {
      loading.value = false
      if (props.mode === 'tasks') timer = setTimeout(() => load(), 6000)
    }
  }
}
async function download(row) {
  try { await downloadImage(row.url, '一方志作品-' + row.id + '.png') }
  catch (e) { error.value = e.message }
}
onMounted(() => load(0))
onBeforeUnmount(() => { alive = false; sequence++; clearTimeout(timer) })
</script>

<template>
  <section class="image-records">
    <header><div><h2>{{ mode === 'works' ? '我的图片作品' : 'AI 图片创作任务' }}</h2><p>{{ mode === 'works' ? '已经完成并保存的海报和产品套图。' : '查看真实进度，打开创作继续修改或恢复任务。' }}</p></div><RouterLink to="/image/create">新建图片创作 ↗</RouterLink></header>
    <div class="records-toolbar"><label v-if="mode === 'tasks'">创作类型 <select v-model="workflow" @change="load(0)"><option value="POSTER">营销海报</option><option value="PRODUCT_SET">产品套图</option></select></label><button type="button" :disabled="loading" @click="load()">{{ loading ? '正在读取…' : '刷新记录' }}</button></div>
    <p v-if="error" class="records-error" role="alert">{{ error }}</p>
    <p v-else-if="!records.length && !loading" class="records-empty">{{ mode === 'works' ? '还没有完成的图片作品。去说一句需求，开始第一次创作。' : '还没有这类创作任务。' }}</p>
    <div :class="mode === 'works' ? 'records-grid' : 'records-list'">
      <article v-for="row in records" :key="row.id">
        <RouterLink v-if="mode === 'works'" :to="link(row)"><img :src="row.url" :alt="row.title" loading="lazy" /></RouterLink>
        <div><strong>{{ row.title || row.brief || '商品图片创作' }}</strong><p>{{ new Date(row.createdAt).toLocaleString() }}<template v-if="mode === 'tasks'"> · {{ IMAGE_STATUS[row.status] }} · {{ row.completed }}/{{ row.count }} 张</template></p></div>
        <footer><RouterLink :to="link(row)">{{ mode === 'works' ? '查看与修改' : '查看任务' }}</RouterLink><button v-if="mode === 'works'" type="button" @click="download(row)">下载</button></footer>
      </article>
    </div>
    <div v-if="totalPages > 1" class="records-pagination"><button :disabled="page === 0 || loading" type="button" @click="load(page - 1)">上一页</button><span>{{ page + 1 }} / {{ totalPages }}</span><button :disabled="page + 1 >= totalPages || loading" type="button" @click="load(page + 1)">下一页</button></div>
  </section>
</template>

<style scoped>
.image-records{padding:24px;border:1px solid var(--color-border-subtle);border-radius:10px;margin-bottom:25px;background:var(--color-bg-surface);color:var(--color-primary)}header{display:flex;align-items:center;justify-content:space-between;gap:20px}h2{font-size:24px;margin:0 0 10px}header p,.records-toolbar,.records-empty{font-size:12px;color:var(--color-text-muted)}a{font-size:12px}button,select{font:inherit;font-size:12px;border:1px solid var(--color-border-subtle);border-radius:5px;background:var(--color-bg-surface);color:var(--color-primary);padding:7px 12px;cursor:pointer}button:disabled{opacity:.4;cursor:default}.records-toolbar{display:flex;gap:15px;justify-content:flex-end;margin:20px 0}.records-empty{padding:30px 0}.records-error{color:var(--color-accent-text);font-size:12px}.records-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:22px}.records-grid img{display:block;width:100%;aspect-ratio:1;object-fit:contain;background:var(--color-bg-subtle);border-radius:7px}article strong{display:block;font-size:13px;overflow-wrap:anywhere;margin-top:12px}article p{font-size:10px;color:var(--color-text-muted);line-height:1.6}footer{display:flex;gap:20px;align-items:center}.records-list article{display:flex;justify-content:space-between;gap:20px;padding:10px 0;border-bottom:1px solid var(--color-border-subtle)}.records-list article>div{min-width:0}.records-list footer{flex-shrink:0}.records-pagination{display:flex;gap:16px;justify-content:center;align-items:center;margin-top:25px;font-size:12px}button:focus-visible,a:focus-visible{outline:2px solid var(--color-accent-text);outline-offset:3px}@media(max-width:700px){.image-records{padding:17px}.records-grid{grid-template-columns:repeat(2,minmax(0,1fr))}header{align-items:flex-start;flex-direction:column}.records-list article{display:block}.records-list footer{padding-bottom:10px}}
</style>
