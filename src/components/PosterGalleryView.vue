<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import {
  IMAGE_STATUS,
  imageDimensions,
  isImageActive,
} from "../domain/imageCreation";
import { POSTER_SCENARIOS } from "../domain/posterScenarios";
import InspirationCard from "./InspirationCard.vue";
import { Check, Pencil, X } from "lucide-vue-next";

const props = defineProps({ studio: { type: Object, required: true } });
const emit = defineEmits(["choose"]);
const studio = computed(() => props.studio);
const galleryScroll = ref(null);
const preview = ref(null);
const previewDialog = ref(null);
const previewCanvas = ref(null);
const previewImage = ref(null);
const editorOptions = ref([]);
const editorHeadline = ref("");
const editorCaption = ref("");
const editorInstruction = ref("");
const editingTitleId = ref(null);
const editingTitle = ref("");
const titleSaving = ref(false);
const zoom = ref(1);
const fittedSize = ref({ width: 0, height: 0 });
const imageSize = computed(() => ({
  width: `${Math.round(fittedSize.value.width * zoom.value)}px`,
  height: `${Math.round(fittedSize.value.height * zoom.value)}px`,
}));
function showTab(tab) {
  studio.value.workspaceTab = tab;
  if (tab === 'history') studio.value.refreshHistory();
}
async function showPreview(item) {
  preview.value = item;
  zoom.value = 1;
  await nextTick();
  previewDialog.value?.showModal();
  await nextTick();
  fitPreview();
}
function fitPreview() {
  const image = previewImage.value;
  const canvas = previewCanvas.value;
  if (!image?.naturalWidth || !canvas?.clientWidth) return;
  const scale = Math.min(
    (canvas.clientWidth - 40) / image.naturalWidth,
    (canvas.clientHeight - 40) / image.naturalHeight,
    1,
  );
  fittedSize.value = {
    width: image.naturalWidth * scale,
    height: image.naturalHeight * scale,
  };
}
async function changeZoom(delta) {
  zoom.value = Math.min(
    3,
    Math.max(0.5, Math.round((zoom.value + delta) * 100) / 100),
  );
  await nextTick();
  const canvas = previewCanvas.value;
  if (canvas) {
    canvas.scrollLeft = (canvas.scrollWidth - canvas.clientWidth) / 2;
    canvas.scrollTop = (canvas.scrollHeight - canvas.clientHeight) / 2;
  }
}
function resetZoom() {
  changeZoom(1 - zoom.value);
}
function openEditor(item) {
  editorOptions.value = [];
  editorHeadline.value = item.headline || "";
  editorCaption.value = item.caption || "";
  editorInstruction.value = "";
}
function submitEditor(item) {
  if (!editorOptions.value.length && !editorInstruction.value.trim()) return;
  studio.value.revisePoster(item, {
    options: editorOptions.value,
    headline: editorOptions.value.includes("TEXT") ? editorHeadline.value : null,
    caption: editorOptions.value.includes("TEXT") ? editorCaption.value : null,
    instruction: editorInstruction.value,
  });
}
function beginTitleEdit(entry) {
  editingTitleId.value = entry.id;
  editingTitle.value = entry.title || entry.brief || "";
}
async function saveTitle(entry) {
  if (!editingTitle.value.trim() || titleSaving.value) return;
  titleSaving.value = true;
  try {
    await studio.value.renameCreation(entry.id, editingTitle.value);
    editingTitleId.value = null;
  } catch (error) {
    studio.value.error = error.message;
  } finally {
    titleSaving.value = false;
  }
}
function turnPrompt(turn) {
  if (!turn.parentId) return turn.brief || "这次海报创作";
  return turn.brief?.split("\n本次修改：").at(-1) || "继续修改海报";
}
watch(() => studio.value.current?.id, () => { editorOptions.value = []; });
watch(() => studio.value.thread?.length, async length => {
  if (!length) return;
  await nextTick();
  if (galleryScroll.value) galleryScroll.value.scrollTop = galleryScroll.value.scrollHeight;
});
onMounted(() => window.addEventListener("resize", fitPreview));
onBeforeUnmount(() => window.removeEventListener("resize", fitPreview));
</script>

<template>
  <section class="poster-gallery" aria-label="海报展示工作台">
    <header class="poster-gallery-toolbar">
      <nav class="poster-gallery-tabs" aria-label="展示内容">
        <button
          type="button"
          :class="{ active: studio.workspaceTab === 'inspiration' }"
          :aria-current="
            studio.workspaceTab === 'inspiration' ? 'page' : undefined
          "
          @click="showTab('inspiration')"
        >
          灵感画廊</button
        ><button
          type="button"
          :class="{ active: studio.workspaceTab === 'result' }"
          :aria-current="studio.workspaceTab === 'result' ? 'page' : undefined"
          @click="showTab('result')"
        >
          我的生成
          <span
            v-if="studio.active || studio.optimistic"
            class="poster-live-dot"
          /></button
        ><button
          type="button"
          :class="{ active: studio.workspaceTab === 'history' }"
          :aria-current="studio.workspaceTab === 'history' ? 'page' : undefined"
          @click="showTab('history')"
        >
          历史作品
          <small v-if="studio.history.length">{{
            studio.history.length
          }}</small>
        </button>
      </nav>
      <RouterLink to="/works" class="poster-works-link"
        >作品库 <span aria-hidden="true">↗</span></RouterLink
      >
    </header>

    <div ref="galleryScroll" class="poster-gallery-scroll">
      <div
        v-if="studio.workspaceTab === 'inspiration'"
        class="poster-inspiration-view"
      >
        <div class="poster-view-intro">
          <strong>一张海报，从真实的经营场景开始。</strong>
          <p>选一个方向，左侧画面重点与提示词会一起更新；你可以继续修改。</p>
        </div>
        <div class="poster-inspiration-grid">
          <InspirationCard
            v-for="(scenario, index) in POSTER_SCENARIOS"
            :key="scenario.focus"
            :scenario="scenario"
            :index="index"
            :selected="studio.style === scenario.focus"
            :disabled="studio.locked"
            @select="emit('choose', $event)"
          />
        </div>
      </div>

      <div
        v-else-if="studio.workspaceTab === 'history'"
        class="poster-history-view"
      >
        <div class="poster-view-intro">
          <strong>留下的每一版，都可以继续编辑。</strong>
          <p>按时间排列的海报会话，点击可查看图片与继续修改。</p>
        </div>
        <p v-if="!studio.history.length" class="poster-empty-history">
          还没有海报创作记录。
        </p>
        <div v-else class="poster-history-list">
          <div
            v-for="entry in studio.history"
            :key="entry.id"
            class="poster-history-entry"
          >
            <button type="button" class="poster-history-open" :disabled="studio.busy || !!studio.pending" @click="studio.openCreation(entry.id)">
            <span class="poster-history-thumb"
              ><img
                v-if="entry.previewUrl"
                :src="entry.previewUrl"
                :alt="`${entry.title || '海报创作'}缩略图`"
                loading="lazy"
              /><span v-else aria-label="暂无成图">待成图</span></span
            ><span class="poster-history-copy"
              ><strong>{{ entry.title || "海报创作" }}</strong
              ><small
                >{{ new Date(entry.createdAt).toLocaleString() }}</small
              ></span
            ><span class="poster-history-status">{{
              IMAGE_STATUS[entry.status]
            }}</span
            ><span aria-hidden="true">↗</span>
            </button>
            <form v-if="editingTitleId === entry.id" class="poster-history-rename" @submit.prevent="saveTitle(entry)">
              <input v-model="editingTitle" maxlength="100" aria-label="作品名称" @keydown.esc.prevent="editingTitleId = null" />
              <button type="submit" title="保存名称" aria-label="保存名称" :disabled="titleSaving || !editingTitle.trim()"><Check :size="16" /></button>
              <button type="button" title="取消" aria-label="取消" @click="editingTitleId = null"><X :size="16" /></button>
            </form>
            <button v-else type="button" class="poster-history-rename-trigger" title="修改名称" aria-label="修改名称" @click="beginTitleEdit(entry)"><Pencil :size="16" /></button>
          </div>
        </div>
        <button
          v-if="studio.historyMore"
          type="button"
          class="poster-load-more"
          @click="studio.refreshHistory(false)"
        >
          加载更早的记录 ↓
        </button>
      </div>

      <div v-else class="poster-result-view">
        <div
          v-if="studio.optimistic"
          class="poster-skeleton-card"
          role="status"
        >
          <div
            class="poster-skeleton-art"
            :style="{ aspectRatio: studio.dimensions.width + '/' + studio.dimensions.height }"
          >
            <span class="poster-skeleton-glow" /><span
              class="poster-skeleton-seal"
              >一方志</span
            ><span class="poster-skeleton-line wide" /><span
              class="poster-skeleton-line"
            />
          </div>
          <div class="poster-skeleton-copy">
            <span class="poster-live-dot" /><strong
              >一方志正在编排画面…</strong
            >
            <p>{{ studio.optimisticBrief || "参考已收到，正在准备画面" }}</p>
          </div>
        </div>
        <p v-if="studio.error" class="poster-result-error" role="alert">
          {{ studio.error }}
          <button type="button" @click="studio.error = ''">收起</button>
        </p>
        <p v-if="studio.pending" class="poster-result-note" role="status">
          上次提交尚未确认，可以恢复原请求。<button
            type="button"
            :disabled="studio.busy"
            @click="studio.generate"
          >
            恢复提交
          </button>
        </p>
        <div
          v-if="!studio.current && !studio.optimistic"
          class="poster-result-empty"
        >
          <span class="poster-empty-emblem">志</span>
          <h3>让一张海报，讲好门店的故事。</h3>
          <p>
            选一个经营场景，写下真实商品和活动。生成后海报会完整出现在这里。
          </p>
          <button type="button" @click="showTab('inspiration')">
            浏览灵感方向 ↗
          </button>
        </div>
        <div class="poster-thread">
          <div
            v-for="(turn, index) in studio.thread"
            :key="turn.id"
            class="poster-current"
          >
            <div class="poster-turn-label">{{ index === 0 ? '初稿' : `第 ${index + 1} 版` }} <time>{{ new Date(turn.createdAt).toLocaleString() }}</time></div>
            <div class="poster-result-head">
              <span class="poster-status"
                ><i :class="{ live: isImageActive(turn.status) }" />{{
                  IMAGE_STATUS[turn.status]
                }}</span
              ><span
                >{{ turn.completed }}/{{ turn.count }} 张</span
              ><button v-if="turn.id === studio.current?.id"
                type="button"
                :disabled="studio.busy"
                @click="studio.load(turn.id)"
              >
                刷新进度 ↻
              </button>
            </div>
            <h3>{{ turnPrompt(turn) }}</h3>
            <p v-if="turn.summary" class="poster-result-summary">
              {{ turn.summary }}
            </p>
            <p v-if="turn.question" class="poster-result-note">
              {{ turn.question }}
            </p>
            <p v-if="turn.error" class="poster-result-error">
              {{ turn.error }}
              <button
                v-if="turn.id === studio.current?.id && turn.status === 'INTERRUPTED'"
                type="button"
                :disabled="studio.busy || !!studio.pending"
                @click="studio.retry(null)"
              >
                恢复任务
              </button>
            </p>
            <div
              class="poster-result-grid"
              :class="{ single: turn.count === 1 }"
              :style="{ '--poster-image-ratio': imageDimensions(turn.quality, turn.ratio).width / imageDimensions(turn.quality, turn.ratio).height }"
            >
              <template v-if="!turn.items.length && isImageActive(turn.status)"
                ><div
                  v-for="n in turn.count"
                  :key="n"
                  class="poster-result-placeholder poster-skeleton-card"
                  :style="{
                     aspectRatio: imageDimensions(turn.quality, turn.ratio).width + '/' + imageDimensions(turn.quality, turn.ratio).height,
                  }"
                >
                  <span class="poster-skeleton-glow" /><span
                    >一方志正在编排画面…</span
                  >
                </div></template
              >
              <article
                v-for="item in turn.items"
                :key="item.id"
                class="poster-result-item"
                :style="{ '--poster-image-ratio': item.width / item.height }"
              >
                <button
                  v-if="item.url"
                  type="button"
                  class="poster-image-button"
                  :style="{ aspectRatio: item.width + '/' + item.height }"
                  :aria-label="`放大查看${item.role}`"
                  @click="showPreview(item)"
                >
                  <img :src="item.url" :alt="item.role" /><span
                    >放大查看 ↗</span
                  >
                </button>
                <div
                  v-else
                  class="poster-result-placeholder"
                  :style="{ aspectRatio: item.width + '/' + item.height }"
                >
                  <span
                    v-if="isImageActive(item.status)"
                    class="poster-live-dot"
                  /><strong>{{ item.role }}</strong
                  ><small>{{ IMAGE_STATUS[item.status] }}</small>
                </div>
                <div class="poster-item-details">
                <div class="poster-item-meta">
                  <strong
                    >{{ String(item.ordinal + 1).padStart(2, "0") }} /
                    {{ item.role }}</strong>
                </div>
                <p v-if="item.similarityWarning" class="poster-result-note">
                  这张与近期海报较相似，可换版式或场景。
                </p>
                <p v-if="item.error" class="poster-result-error">
                  {{ item.error }}
                </p>
                <div class="poster-item-actions">
                  <button
                    v-if="item.url"
                    type="button"
                    :disabled="studio.busy || !!studio.pending"
                    @click="studio.download(item)"
                  >
                    下载图片</button
                  ><button
                    v-if="item.url"
                    type="button"
                    :disabled="studio.busy || !!studio.pending || item.assetSaved"
                    @click="studio.saveToLibrary(item)"
                  >
                    {{ item.assetSaved ? "已保存到素材库" : "保存到素材库" }}</button
                  ><button
                    v-if="['FAILED', 'INTERRUPTED'].includes(item.status)"
                    type="button"
                    :disabled="studio.busy || !!studio.pending"
                    @click="studio.retry(item)"
                  >
                    {{ item.status === "FAILED" ? "重新生成这张" : "恢复这张" }}
                  </button>
                </div>
                <form v-if="item.url && turn.id === studio.current?.id && turn.status === 'SUCCEEDED'" class="poster-conversation-editor" @submit.prevent="submitEditor(item)">
                  <div class="poster-conversation-heading"><strong>继续修改这张海报</strong><small>可多选，生成后仍在当前会话中</small></div>
                  <div class="poster-conversation-options" role="group" aria-label="选择修改项">
                    <label v-for="option in [{ type: 'TEXT', label: '修改文字' }, { type: 'LAYOUT', label: '换版式' }, { type: 'SCENE', label: '换场景' }, { type: 'MESSAGE', label: '换传播角度' }]" :key="option.type" :class="{ selected: editorOptions.includes(option.type) }"><input v-model="editorOptions" type="checkbox" :value="option.type" :disabled="studio.busy || !!studio.pending || !studio.configured" />{{ option.label }}</label>
                  </div>
                  <div v-if="editorOptions.includes('TEXT')" class="poster-conversation-copy">
                    <label>标题<input v-model="editorHeadline" maxlength="40" /></label>
                    <label>活动说明<textarea v-model="editorCaption" maxlength="100" rows="2" /></label>
                  </div>
                  <textarea v-model="editorInstruction" class="poster-conversation-input" maxlength="1000" placeholder="还可以补充一句，例如：背景更清爽，商品保持不变" />
                  <button type="submit" class="poster-conversation-submit" :disabled="studio.busy || !!studio.pending || !studio.configured || (!editorOptions.length && !editorInstruction.trim())">生成这一版</button>
                </form>
                </div>
              </article>
            </div>
          </div>
        </div>
      </div>
    </div>
    <dialog
      ref="previewDialog"
      class="poster-lightbox"
      aria-label="海报大图预览"
      @close="preview = null"
    >
      <template v-if="preview">
        <header>
          <strong>{{ preview.role }}</strong>
          <div class="poster-zoom-controls" role="group" aria-label="图片缩放">
            <button
              type="button"
              aria-label="缩小图片"
              :disabled="zoom <= 0.5"
              @click="changeZoom(-0.25)"
            >
              −</button
            ><button
              type="button"
              class="poster-zoom-value"
              title="恢复适应画框"
              @click="resetZoom"
            >
              {{ Math.round(zoom * 100) }}%</button
            ><button
              type="button"
              aria-label="放大图片"
              :disabled="zoom >= 3"
              @click="changeZoom(0.25)"
            >
              ＋
            </button>
          </div>
          <button
            type="button"
            class="poster-lightbox-download"
            @click="studio.download(preview)"
          >
            下载原图 ↓</button
          ><button
            type="button"
            class="poster-lightbox-download"
            :disabled="studio.busy || preview.assetSaved"
            @click="studio.saveToLibrary(preview)"
          >
            {{ preview.assetSaved ? "已保存到素材库" : "保存到素材库" }}</button
          ><button
            type="button"
            class="poster-lightbox-close"
            aria-label="关闭预览"
            @click="previewDialog.close()"
          >
            ×
          </button>
        </header>
        <div ref="previewCanvas" class="poster-lightbox-canvas">
          <div
            class="poster-lightbox-stage"
            :style="{
              width: `${Math.round(fittedSize.width * zoom) + 40}px`,
              height: `${Math.round(fittedSize.height * zoom) + 40}px`,
            }"
          >
            <img
              ref="previewImage"
              :src="preview.url"
              :alt="preview.role"
              :style="imageSize"
              @load="fitPreview"
            />
          </div>
        </div>
      </template>
    </dialog>
  </section>
</template>
