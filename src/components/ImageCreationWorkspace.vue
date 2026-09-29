<script setup>
import { computed, ref, toRef, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { IMAGE_STATUS, imageDimensions, isImageActive } from '../domain/imageCreation'
import { Check, Pencil, X } from 'lucide-vue-next'

const props = defineProps({ studio: { type: Object, required: true } })
const studio = toRef(props, 'studio')
const preview = ref(null)
const previewDialog = ref(null)
const editingTitleId = ref(null)
const editingTitle = ref('')
const titleSaving = ref(false)
const placeholders = computed(() => studio.value.current ? imageDimensions(studio.value.current.quality, studio.value.current.ratio) : null)
function showPreview(item) {
  preview.value = item
  previewDialog.value.showModal()
}
watch(() => studio.value.thread, turns => {
  if (!preview.value) return
  const latest = turns.flatMap(turn => turn.items || []).find(item => item.id === preview.value.id)
  if (latest) preview.value = latest
})
function beginTitleEdit(entry) {
  editingTitleId.value = entry.id
  editingTitle.value = entry.title || entry.brief || ''
}
async function saveTitle(entry) {
  if (!editingTitle.value.trim() || titleSaving.value) return
  titleSaving.value = true
  try {
    await studio.value.renameCreation(entry.id, editingTitle.value)
    editingTitleId.value = null
  } catch (error) { studio.value.error = error.message }
  finally { titleSaving.value = false }
}
</script>

<template>
  <div class="creation-workspace" :class="{ 'showing-results': studio.workspaceTab === 'result' }">
    <header class="creation-toolbar">
      <div class="creation-view-tabs" role="group" aria-label="展示内容">
        <button type="button" :class="{ active: studio.workspaceTab === 'inspiration' }" :aria-pressed="studio.workspaceTab === 'inspiration'" @click="studio.workspaceTab = 'inspiration'">{{ studio.product ? '灵感案例' : '场景灵感' }}</button>
        <button type="button" :class="{ active: studio.workspaceTab === 'result' }" :aria-pressed="studio.workspaceTab === 'result'" @click="studio.workspaceTab = 'result'">我的生成 <span v-if="studio.active" class="creation-pulse" /></button>
      </div>
      <div class="creation-library-links"><button type="button" :aria-expanded="studio.historyOpen" @click="studio.historyOpen = !studio.historyOpen">◷ 历史会话</button><RouterLink to="/works">作品库 ↗</RouterLink></div>
    </header>

    <div v-if="studio.historyOpen" class="creation-history">
      <p v-if="!studio.history.length">还没有创作记录，开始做第一张图吧。</p>
      <div v-for="entry in studio.history" :key="entry.id" class="creation-history-entry">
        <button type="button" class="creation-history-open" :disabled="studio.busy || !!studio.pending" @click="studio.openCreation(entry.id)"><span>{{ entry.title || '商品图片创作' }}</span><small>{{ IMAGE_STATUS[entry.status] }} · {{ new Date(entry.createdAt).toLocaleDateString() }}</small></button>
        <form v-if="editingTitleId === entry.id" class="creation-history-rename" @submit.prevent="saveTitle(entry)"><input v-model="editingTitle" maxlength="100" aria-label="作品名称" @keydown.esc.prevent="editingTitleId = null" /><button type="submit" title="保存名称" aria-label="保存名称" :disabled="titleSaving || !editingTitle.trim()"><Check :size="16" /></button><button type="button" title="取消" aria-label="取消" @click="editingTitleId = null"><X :size="16" /></button></form>
        <button v-else type="button" class="creation-history-rename-trigger" title="修改名称" aria-label="修改名称" @click="beginTitleEdit(entry)"><Pencil :size="16" /></button>
      </div>
      <button v-if="studio.historyMore" type="button" @click="studio.refreshHistory(false)">加载更早记录</button>
    </div>
    <div v-if="studio.error" class="creation-message is-error" role="alert">{{ studio.error }} <button type="button" @click="studio.error = ''">收起</button></div>
    <div v-if="studio.pending" class="creation-message" role="status">上次提交尚未确认，可以恢复原请求。<button type="button" :disabled="studio.busy" @click="studio.generate">恢复提交</button></div>

    <slot v-if="studio.workspaceTab === 'inspiration'" />
    <div v-else-if="!studio.current" class="creation-empty-result">
      <span class="material-symbols-outlined" aria-hidden="true">image</span>
      <h2>{{ studio.product ? '你的产品套图，会展示在这里。' : '你的第一张海报，会展示在这里。' }}</h2>
      <p>{{ studio.product ? '上传商品照片，点一下「立即生成」。主图、细节和场景图会依次出现在右侧。' : '在下方说一句需求，点一下「生成海报」。这里会展示进度和完成的大图。' }}</p>
      <small>生成后可放大查看、下载与修改，作品会自动保存。</small>
    </div>
    <div v-else class="creation-output" :aria-busy="studio.active">
      <div class="creation-progress" role="status" aria-live="polite"><span v-if="studio.active" class="creation-pulse" /><strong>{{ IMAGE_STATUS[studio.current.status] }}</strong><small>{{ studio.current.completed }}/{{ studio.current.count }} 张</small><button type="button" :disabled="studio.busy" @click="studio.load(studio.current.id)">刷新</button><button v-if="studio.active" type="button" :disabled="studio.busy" @click="studio.cancel()">取消生成</button></div>
      <p class="creation-original">{{ studio.current.brief || '根据商品照片自动创作' }}</p>
      <p v-if="studio.current.summary" class="creation-plan">{{ studio.current.summary }}</p>
      <div v-if="studio.current.question" class="creation-message">{{ studio.current.question }}</div>
      <div v-if="studio.current.error" class="creation-message is-error">{{ studio.current.error }}<button v-if="studio.current.status === 'INTERRUPTED'" type="button" :disabled="studio.busy || !!studio.pending" @click="studio.retry(null)">恢复任务</button></div>
      <div class="creation-results" :class="{ single: studio.current.count === 1 }">
        <template v-if="!studio.current.items.length && studio.active">
          <div v-for="n in studio.current.count" :key="n" class="creation-image-placeholder" :style="{ aspectRatio: placeholders.width + '/' + placeholders.height }"><span class="creation-pulse" /><strong>{{ studio.product ? '正在安排第 ' + n + ' 张画面' : '正在安排文案与画面' }}</strong><small>完成后会自动显示在这里</small></div>
        </template>
        <article v-for="item in studio.current.items" :key="item.id" class="creation-result">
          <button v-if="item.url" type="button" class="creation-image-button" :aria-label="'放大查看第 ' + (item.ordinal + 1) + ' 张：' + item.role" :style="{ aspectRatio: item.width + '/' + item.height }" @click="showPreview(item)"><img :src="item.url" :alt="item.role" /><span>放大查看 ↗</span></button>
          <div v-else class="creation-image-placeholder" :style="{ aspectRatio: item.width + '/' + item.height }"><span v-if="isImageActive(item.status)" class="creation-pulse" /><strong>{{ item.role }}</strong><small>{{ IMAGE_STATUS[item.status] }}</small></div>
          <div class="creation-result-caption"><strong>{{ item.ordinal + 1 }} / {{ item.role }}</strong></div>
          <p v-if="item.similarityWarning" class="creation-item-error">这张与近期海报较相似。可以换一个版式或场景；再次生成会调用图片服务。</p>
          <p v-if="item.error" class="creation-item-error">{{ item.error }}</p>
          <div class="creation-result-actions">
            <template v-if="item.url"><button type="button" :disabled="studio.busy || !!studio.pending || !studio.canUseAsset(item)" @click="studio.download(item)">下载图片</button><button type="button" :disabled="studio.busy || !!studio.pending || item.assetSaved || !studio.canUseAsset(item)" @click="studio.saveToLibrary(item)">{{ item.assetSaved ? '已保存到素材库' : '保存到素材库' }}</button><button type="button" :disabled="studio.busy || !!studio.pending || !studio.canUseAsset(item)" @click="studio.editText(item)">修改文字</button><template v-if="studio.current.status === 'SUCCEEDED'"><template v-if="studio.product"><button type="button" :disabled="studio.busy || !!studio.pending || !studio.configured" @click="studio.regenerate(item)">单独重做</button></template><template v-else><button type="button" :disabled="studio.busy || !!studio.pending || !studio.configured" @click="studio.regenerate(item, 'LAYOUT')">换版式</button><button type="button" :disabled="studio.busy || !!studio.pending || !studio.configured" @click="studio.regenerate(item, 'SCENE')">换场景</button><button type="button" :disabled="studio.busy || !!studio.pending || !studio.configured" @click="studio.regenerate(item, 'MESSAGE')">换传播角度</button></template></template></template>
            <button v-if="['FAILED', 'INTERRUPTED', 'UPSTREAM_UNKNOWN'].includes(item.status)" type="button" :disabled="studio.busy || !!studio.pending" @click="studio.retry(item)">{{ item.status === 'FAILED' ? '重新生成这张' : item.status === 'UPSTREAM_UNKNOWN' ? '核对后重新生成' : '恢复这张' }}</button>
          </div>
          <small v-if="!studio.product && item.url && studio.current.status === 'SUCCEEDED'" class="creation-variation-note">换版会再次调用图片服务，现有文案保持不变。</small>
        </article>
      </div>
      <form v-if="studio.textEdit" class="creation-text-edit" @submit.prevent="studio.saveText"><h3>参考原图修改文字</h3><label>标题<input v-model="studio.editHeadline" maxlength="40" /></label><label>活动说明<textarea v-model="studio.editCaption" maxlength="100" rows="2" /></label><div><button class="creation-primary" :disabled="studio.busy || !!studio.pending">保存新版本</button><button type="button" @click="studio.textEdit = null">取消</button></div><small>由图像模型参考原图生成新版本，会调用图片服务；局部画面可能变化，请检查生成的文字。原版本会保留。</small></form>
      <form v-if="!studio.active" class="creation-revision" @submit.prevent="studio.revise"><label for="image-revision">{{ studio.current.status === 'NEEDS_INPUT' ? '补充一句就好' : '哪里想再调整？' }}</label><div><input id="image-revision" v-model="studio.revision" maxlength="1000" :placeholder="studio.current.status === 'NEEDS_INPUT' ? '在这里补充信息' : '例如：背景再清爽一点，商品不要变'" /><button type="submit" class="creation-primary" :disabled="studio.busy || !!studio.pending || !studio.configured || !studio.revision.trim()">{{ studio.current.status === 'NEEDS_INPUT' ? '继续创作' : '生成新版本' }}</button></div><small>原作品会保留。新版本也会自动保存到作品库。</small></form>
    </div>
    <dialog ref="previewDialog" class="creation-preview" aria-label="图片大图预览" @click.self="previewDialog.close()" @close="preview = null">
      <template v-if="preview"><header><strong>{{ preview.role }}</strong><button type="button" aria-label="关闭图片预览" autofocus @click="previewDialog.close()">×</button></header><img :src="preview.url" :alt="preview.role" /><footer><button type="button" :disabled="studio.busy || !studio.canUseAsset(preview)" @click="studio.download(preview)">下载原图 ↓</button><button type="button" :disabled="studio.busy || preview.assetSaved || !studio.canUseAsset(preview)" @click="studio.saveToLibrary(preview)">{{ preview.assetSaved ? '已保存到素材库' : '保存到素材库' }}</button></footer></template>
    </dialog>
  </div>
</template>
