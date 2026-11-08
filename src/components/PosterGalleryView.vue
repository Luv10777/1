<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import {
  IMAGE_STATUS,
  imageDimensions,
  imageStatusLabel,
  isImageActive,
} from "../domain/imageCreation";
import { POSTER_STYLES, SEASONAL_PREFIX, upcomingMoment } from "../domain/posterStyles";
import ImageWorkspaceSwitch from "./ImageWorkspaceSwitch.vue";
import ImageInspirationPanel from "./ImageInspirationPanel.vue";
import PosterStyleCard from "./PosterStyleCard.vue";
import { ArrowUp, Check, Download, Folder, Maximize2, Pencil, RefreshCw, X } from "lucide-vue-next";

const props = defineProps({ studio: { type: Object, required: true } });
const emit = defineEmits(["choose"]);
const studio = computed(() => props.studio);
const moment = upcomingMoment();
const seasonalStyle = SEASONAL_PREFIX + moment.name;
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
// 灵感方案第一次打开才挂上，之后切页签只是隐藏，出好的方案不会丢。
const planOpened = ref(studio.value.workspaceTab === "plan");
watch(() => studio.value.workspaceTab, tab => { if (tab === "plan") planOpened.value = true; });
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
function historyDate(value) {
  return new Date(value).toLocaleString("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" });
}
function turnPrompt(turn) {
  if (!turn.parentId) return turn.brief || "这次绘图创作";
  return turn.brief?.split("\n本次修改：").at(-1) || "继续修改图片";
}
// 图片是主角：描述只在图下占一行，点开才看全文和「一方志的理解」。
const expandedPrompts = ref(new Set());
function togglePrompt(id) {
  expandedPrompts.value[expandedPrompts.value.has(id) ? "delete" : "add"](id);
}
function turnMeta(turn, index) {
  return [
    index > 0 && `第 ${index + 1} 版`,
    (isImageActive(turn.status) || turn.completed > 0) && turn.completed !== turn.count && `${turn.completed}/${turn.count}`,
    historyDate(turn.createdAt),
  ].filter(Boolean).join(" · ");
}
const retryable = item => ["FAILED", "INTERRUPTED", "UPSTREAM_UNKNOWN"].includes(item.status);
const REVISION_OPTIONS = [
  { type: "TEXT", label: "修改文字" },
  { type: "LAYOUT", label: "换构图" },
  { type: "SCENE", label: "换场景" },
  { type: "MESSAGE", label: "换表达角度" },
];
// 底部输入框只服务当前这一轮：规划时反问就补充信息，出完图就继续修改（修改作用于整轮）。
const dockImage = computed(() => studio.value.current?.items?.find(item => item.url) || null);
const dockMode = computed(() => {
  const turn = studio.value.current;
  if (studio.value.workspaceTab !== "result" || !turn) return null;
  if (turn.status === "NEEDS_INPUT") return "answer";
  return turn.status === "SUCCEEDED" && dockImage.value ? "revise" : null;
});
watch(() => studio.value.current?.id, () => { editorOptions.value = []; });
watch(() => studio.value.thread, turns => {
  if (!preview.value) return;
  const latest = turns.flatMap(turn => turn.items || []).find(item => item.id === preview.value.id);
  if (latest) preview.value = latest;
});
watch(() => studio.value.thread?.length, async length => {
  if (!length) return;
  await nextTick();
  if (galleryScroll.value) galleryScroll.value.scrollTop = galleryScroll.value.scrollHeight;
});
onMounted(() => window.addEventListener("resize", fitPreview));
onBeforeUnmount(() => window.removeEventListener("resize", fitPreview));
</script>

<template>
  <section class="poster-gallery" aria-label="图片展示工作台">
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
          绘图风格</button
        ><button
          type="button"
          :class="{ active: studio.workspaceTab === 'plan' }"
          :aria-current="studio.workspaceTab === 'plan' ? 'page' : undefined"
          @click="showTab('plan')"
        >
          灵感方案</button
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
      <div class="poster-toolbar-end">
        <RouterLink to="/works" class="poster-works-link" aria-label="作品库" title="作品库"
          ><Folder :size="18" :stroke-width="1.6" aria-hidden="true"
        /></RouterLink>
        <ImageWorkspaceSwitch />
      </div>
    </header>

    <div ref="galleryScroll" class="poster-gallery-scroll">
      <ImageInspirationPanel v-if="planOpened" v-show="studio.workspaceTab === 'plan'" :studio="studio" />
      <div
        v-if="studio.workspaceTab === 'inspiration'"
        class="poster-inspiration-view"
      >
        <button
          type="button"
          class="poster-seasonal"
          :class="{ 'is-selected': studio.style === seasonalStyle }"
          :disabled="studio.locked"
          :aria-pressed="studio.style === seasonalStyle"
          @click="emit('choose', seasonalStyle)"
        >
          <span class="poster-seasonal-label">应时</span>
          <strong>{{ moment.name }}</strong>
          <span>{{ moment.days ? `${moment.days} 天后 · ${moment.month}/${moment.day}` : "就是今天" }}</span>
          <small>下一个：{{ moment.next }}</small>
          <Check v-if="studio.style === seasonalStyle" class="poster-seasonal-check" :size="16" aria-hidden="true" />
        </button>
        <div class="poster-style-grid">
          <PosterStyleCard
            v-for="style in POSTER_STYLES"
            :key="style.key"
            :image="style.image"
            :name="style.name"
            :fit="style.fit"
            :selected="studio.style === style.name"
            :disabled="studio.locked"
            @select="emit('choose', style.name)"
          />
        </div>
      </div>

      <div
        v-else-if="studio.workspaceTab === 'history'"
        class="poster-history-view"
      >
        <p v-if="!studio.history.length" class="poster-empty-history">还没有绘图作品</p>
        <ul v-else class="poster-history-grid">
          <li v-for="entry in studio.history" :key="entry.id" class="poster-history-card">
            <button type="button" class="poster-history-open" :disabled="studio.busy || !!studio.pending" :aria-label="`打开${entry.title || '灵感绘图'}`" @click="studio.openCreation(entry.id)">
              <img v-if="entry.previewUrl" :src="entry.previewUrl" :alt="`${entry.title || '灵感绘图'}缩略图`" loading="lazy" />
              <span v-else class="poster-history-placeholder"><img src="/images/brand/yifangzhi-mark.png" alt="" /><span>待成图</span></span>
              <span v-if="entry.status !== 'SUCCEEDED'" class="poster-history-status">{{ IMAGE_STATUS[entry.status] }}</span>
            </button>
            <form v-if="editingTitleId === entry.id" class="poster-history-rename" @submit.prevent="saveTitle(entry)">
              <input v-model="editingTitle" maxlength="100" aria-label="作品名称" @keydown.esc.prevent="editingTitleId = null" />
              <button type="submit" title="保存名称" aria-label="保存名称" :disabled="titleSaving || !editingTitle.trim()"><Check :size="14" /></button>
              <button type="button" title="取消" aria-label="取消" @click="editingTitleId = null"><X :size="14" /></button>
            </form>
            <div v-else class="poster-history-meta">
              <strong :title="entry.title || '灵感绘图'">{{ entry.title || "灵感绘图" }}</strong>
              <button type="button" class="poster-history-rename-trigger" title="修改名称" aria-label="修改名称" @click="beginTitleEdit(entry)"><Pencil :size="13" /></button>
            </div>
            <time class="poster-history-date" :datetime="entry.createdAt">{{ historyDate(entry.createdAt) }}</time>
          </li>
        </ul>
        <button v-if="studio.historyMore" type="button" class="poster-load-more" @click="studio.refreshHistory(false)">加载更多</button>
      </div>

      <div v-else-if="studio.workspaceTab !== 'plan'" class="poster-result-view">
        <div
          v-if="studio.optimistic"
          class="poster-skeleton-card"
          role="status"
        >
          <div
            class="poster-skeleton-art"
            :style="{ aspectRatio: studio.dimensions.width + '/' + studio.dimensions.height }"
          >
            <span class="poster-skeleton-glow" /><img
              class="poster-skeleton-seal"
              src="/images/brand/yifangzhi-mark.png"
              alt=""
            /><img
              class="poster-skeleton-emblem"
              src="/images/brand/yifangzhi-mark.png"
              alt=""
            /><span class="poster-skeleton-line wide" /><span
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
          <img class="poster-empty-emblem" src="/images/brand/yifangzhi-mark.png" alt="一方志" />
          <h3>让想象，成为看得见的画面。</h3>
          <p>
            描述人物、场景或创意，选择喜欢的画风。生成的图片会出现在这里。
          </p>
          <button type="button" @click="showTab('inspiration')">
            挑选绘图风格
          </button>
        </div>
        <div class="poster-thread">
          <div
            v-for="(turn, index) in studio.thread"
            :key="turn.id"
            class="poster-current"
            :class="{ single: turn.count === 1 }"
            :style="{ '--poster-image-ratio': imageDimensions(turn.quality, turn.ratio).width / imageDimensions(turn.quality, turn.ratio).height }"
          >
            <div class="poster-result-grid">
              <template v-if="!turn.items.length && (isImageActive(turn.status) || turn.status === 'NEEDS_INPUT')">
                <div v-for="n in turn.count" :key="n" class="poster-result-media poster-result-placeholder" :style="{ aspectRatio: imageDimensions(turn.quality, turn.ratio).width + '/' + imageDimensions(turn.quality, turn.ratio).height }">
                  <template v-if="turn.status === 'NEEDS_INPUT'"><small>等你补充信息</small></template>
                  <template v-else><span class="poster-skeleton-glow" /><small>生成中</small></template>
                </div>
              </template>
              <figure v-for="item in turn.items" :key="item.id" class="poster-result-item">
                <div class="poster-result-media" :style="{ aspectRatio: item.width + '/' + item.height }">
                  <button v-if="item.url" type="button" class="poster-image-button" aria-label="放大查看图片" @click="showPreview(item)">
                    <img :src="item.url" alt="生成的图片" @load="studio.reportImageVisible(item)" @error="studio.imageLoadFailed(item)" />
                  </button>
                  <div v-else class="poster-result-placeholder"><small>{{ IMAGE_STATUS[item.status] }}</small></div>
                  <div v-if="item.url" class="poster-image-actions">
                    <button type="button" aria-label="放大查看" title="放大查看" @click="showPreview(item)"><Maximize2 :size="15" :stroke-width="1.8" aria-hidden="true" /></button>
                    <button type="button" aria-label="下载图片" title="下载图片" :disabled="studio.busy || !!studio.pending || !studio.canUseAsset(item)" @click="studio.download(item)"><Download :size="15" :stroke-width="1.8" aria-hidden="true" /></button>
                  </div>
                </div>
                <p v-if="item.similarityWarning" class="poster-result-note">这张与近期图片较相似，可换构图或场景。</p>
                <p v-if="item.error || retryable(item)" class="poster-result-error">
                  {{ item.error }}
                  <button v-if="retryable(item)" type="button" :disabled="studio.busy || !!studio.pending" @click="studio.retry(item)">{{ item.status === "FAILED" ? "重新生成这张" : item.status === "UPSTREAM_UNKNOWN" ? "核对后重新生成" : "恢复这张" }}</button>
                </p>
              </figure>
            </div>
            <div class="poster-turn-caption">
              <span v-if="turn.status !== 'SUCCEEDED'" class="poster-status" role="status"><i :class="{ live: isImageActive(turn.status) }" />{{ imageStatusLabel(turn) }}</span>
              <button type="button" class="poster-turn-prompt" :class="{ expanded: expandedPrompts.has(turn.id) }" :aria-expanded="expandedPrompts.has(turn.id)" :title="expandedPrompts.has(turn.id) ? '收起' : '查看完整描述'" @click="togglePrompt(turn.id)">{{ turnPrompt(turn) }}</button>
              <span class="poster-turn-meta">{{ turnMeta(turn, index) }}</span>
              <button v-if="turn.id === studio.current?.id && isImageActive(turn.status)" type="button" class="poster-icon-button" aria-label="刷新进度" title="刷新进度" :disabled="studio.busy" @click="studio.load(turn.id)"><RefreshCw :size="14" :stroke-width="1.6" aria-hidden="true" /></button>
            </div>
            <p v-if="turn.summary && expandedPrompts.has(turn.id)" class="poster-result-summary"><span>一方志的理解</span>{{ turn.summary }}</p>
            <p v-if="turn.question && turn.id !== studio.current?.id" class="poster-result-note">{{ turn.question }}</p>
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
          </div>
        </div>
      </div>
    </div>
    <form v-if="dockMode" class="poster-dock" @submit.prevent="dockMode === 'answer' ? studio.revise() : submitEditor(dockImage)">
      <p v-if="dockMode === 'answer'" class="poster-dock-question">{{ studio.current.question }}</p>
      <div v-else class="poster-dock-options" role="group" aria-label="选择修改项">
        <label v-for="option in REVISION_OPTIONS" :key="option.type" :class="{ selected: editorOptions.includes(option.type) }"><input v-model="editorOptions" type="checkbox" :value="option.type" :disabled="studio.busy || !!studio.pending || !studio.configured" />{{ option.label }}</label>
        <small>{{ studio.imageCredits != null ? `每张 ${studio.imageCredits} 烟火，` : '' }}成功交付后扣费</small>
      </div>
      <div v-if="dockMode === 'revise' && editorOptions.includes('TEXT')" class="poster-conversation-copy">
        <label><span>标题</span><input v-model="editorHeadline" maxlength="40" /></label>
        <label><span>说明文字</span><input v-model="editorCaption" maxlength="100" placeholder="可不填" /></label>
      </div>
      <div class="poster-dock-bar">
        <template v-if="dockMode === 'answer'">
          <input v-model="studio.revision" class="poster-dock-input" maxlength="1000" aria-label="补充信息" placeholder="补充上面问到的信息，提交后继续规划这张图片" />
          <button type="submit" class="poster-conversation-submit" :disabled="studio.busy || !!studio.pending || !studio.configured || !studio.revision.trim()">补充并继续</button>
        </template>
        <template v-else>
          <img class="poster-dock-thumb" :src="dockImage.url" alt="" />
          <input v-model="editorInstruction" class="poster-dock-input" maxlength="1000" aria-label="补充修改说明" placeholder="继续修改这一轮，例如：背景更清爽，主体不变" />
          <button type="submit" class="poster-conversation-submit is-icon" aria-label="生成这一版" title="生成这一版" :disabled="studio.busy || !!studio.pending || !studio.configured || (!editorOptions.length && !editorInstruction.trim())"><ArrowUp :size="18" :stroke-width="2" aria-hidden="true" /></button>
        </template>
      </div>
    </form>
    <dialog
      ref="previewDialog"
      class="poster-lightbox"
      aria-label="图片大图预览"
      @close="preview = null"
    >
      <template v-if="preview">
        <header>
          <strong>查看大图</strong>
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
            :disabled="studio.busy || !studio.canUseAsset(preview)"
            @click="studio.download(preview)"
          >
            下载原图</button
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
              alt="生成的图片"
              :style="imageSize"
              @load="fitPreview"
            />
          </div>
        </div>
      </template>
    </dialog>
  </section>
</template>
