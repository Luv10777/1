<script setup>
import { computed, ref, watch, onBeforeUnmount } from "vue";
import { useRoute } from 'vue-router';
import { usePlatformAccounts } from '../stores/platformAccounts';
import { usePublishingVideo } from "../composables/usePublishingVideo";
import { usePublishingAttachments, IMAGE_LIMIT } from "../composables/usePublishingAttachments";
import { assetSource } from "../stores/assetLibrary";
import PublishingPhone from "../components/publishing/PublishingPhone.vue";
import PublishingAssetPicker from "../components/publishing/PublishingAssetPicker.vue";
import PublishingCommerce from "../components/publishing/PublishingCommerce.vue";
import PublishingSelect from "../components/publishing/PublishingSelect.vue";
import '../publishing-media.css';
import { auth } from "../stores/auth";
import { platforms, tags } from "../data/publishing";
import {
  Check,
  Clock3,
  Image,
  MapPin,
  MessageCircle,
  Plus,
  Upload,
  Trash2,
  Send,
  WandSparkles,
  UsersRound,
  Images,
  PenLine,
  Store,
  CalendarClock,
  Video,
  ZoomIn,
  X,
  LoaderCircle,
  FolderOpen,
  Music2,
  Play,
  Pause,
  ArrowLeft,
  ArrowRight,
} from "lucide-vue-next";

const activePlatform = ref("抖音");
const studioStep = ref(0);
const copyVersion = ref('烟火日常');
const copyVariants = ['烟火日常', '匠心手艺', '市井趣味'];
function selectCopyVersion(version) {
  if (aiBusy.value || publishing.value) return;
  copyVersion.value = version;
  const place = poi.value;
  const samples = {
    '烟火日常': [`${place}的日常`, `街巷里的${place}，也有值得慢慢记录的日常。带上朋友，留点时间给一顿好饭。出发前请确认门店营业时间。`],
    '匠心手艺': [`在${place}，看见用心`, `一道菜背后的准备，值得多看一眼。记录${place}的手艺与细节，让这份用心被更多人看见。请根据实拍素材核实制作过程。`],
    '市井趣味': [`去${place}坐坐`, `今天的快乐清单：约个朋友，找张桌子，到${place}坐一会儿。你的下班小据点是哪家店？`],
  };
  [title.value, body.value] = samples[version];
  title.value = title.value.slice(0, titleLimit.value);
  announce('已切换本地示例文案，请结合门店真实情况编辑。');
}
async function dropMedia(event) {
  if (mediaBusy.value || publishing.value) return;
  const files = Array.from(event.dataTransfer?.files || []);
  if (!files.length) return;
  if (files[0].type.startsWith('video/')) { mediaType.value = 'video'; if (await selectVideo(files[0])) cover.value = ''; }
  else { mediaType.value = 'image'; await addPictures(files); }
}
const previewPlatform = ref("抖音");
const scheduleMode = ref("now");
const selectedAccounts = ref(["dy-main", "xhs-main"]);
const bundleSelections = ref({});
const comment = ref("到店出示团购券，额外赠送冰镇酸梅汤～");
const scheduledAt = ref("");
const cover = ref("");
const coverInput = ref(null);
const coverDialog = ref(null);
const videoInput = ref(null);
const imageInput = ref(null);
const musicInput = ref(null);
const assetPicker = ref(null);
const mediaType = ref('video');
const pickerKind = ref('video');
const libraryBusy = ref(false);
const libraryError = ref('');
const { pictures, music, busy: attachmentsBusy, error: attachmentsError, addPictures, selectMusic, removePicture, movePicture, removeMusic } = usePublishingAttachments();
const musicVolume = ref(35);
const originalVolume = ref(100);
const musicStart = ref(0);
const musicPlaying = ref(false);
const activePicture = ref(0);
const libraryAbort = new AbortController();
const phonePreview = ref(null);
const { videoUrl, videoInfo, videoBusy, videoError, selectVideo, removeVideo } = usePublishingVideo();
const mediaBusy = computed(() => videoBusy.value || attachmentsBusy.value || libraryBusy.value);
const hasMedia = computed(() => mediaType.value === 'video' ? !!videoUrl.value : pictures.value.length > 0);
const pickerLimit = computed(() => pickerKind.value === 'image' ? IMAGE_LIMIT - pictures.value.length : 1);
watch(music, () => { musicStart.value = 0; musicPlaying.value = false });
watch(() => pictures.value.length, length => { activePicture.value = Math.min(activePicture.value, Math.max(0, length - 1)) });
function uploadLocal() {
  if (mediaBusy.value) return;
  (mediaType.value === 'video' ? videoInput : imageInput).value?.click();
}
function openLibrary(kind = mediaType.value === 'video' ? 'video' : 'image') {
  pickerKind.value = kind;
  libraryError.value = '';
  assetPicker.value.open();
}
async function uploadPictures(event) {
  const files = Array.from(event.target.files || []);
  event.target.value = '';
  await addPictures(files);
}
async function uploadMusic(event) {
  const file = event.target.files?.[0];
  event.target.value = '';
  await selectMusic(file);
}
async function chooseLibrary(items) {
  if (mediaBusy.value) return;
  libraryBusy.value = true;
  libraryError.value = '';
  try {
    const files = [];
    for (const item of items) {
      const source = assetSource(item);
      if (!source) throw new Error('此素材缺少源文件，请选择其他素材');
      const response = await fetch(source, { signal: AbortSignal.any([libraryAbort.signal, AbortSignal.timeout(15000)]) });
      if (!response.ok) throw new Error(`读取 ${item.name} 失败，请重试`);
      const blob = await response.blob();
      const extension = new URL(source, window.location.href).pathname.split('.').pop();
      const name = `${item.name.replace(/\.[^.]+$/, '')}.${extension}`;
      files.push(new File([blob], name, { type: blob.type }));
    }
    let success;
    if (pickerKind.value === 'image') success = await addPictures(files);
    else if (pickerKind.value === 'audio') success = await selectMusic(files[0]);
    else { success = await selectVideo(files[0]); if (success) cover.value = ''; }
    if (success) assetPicker.value.close();
    else libraryError.value = pickerKind.value === 'video' ? videoError.value : attachmentsError.value;
  } catch (error) {
    if (error.name !== 'AbortError') libraryError.value = error.name === 'TimeoutError' ? '素材读取超时，请重试。' : error.message;
  }
  finally { libraryBusy.value = false; }
}
const durationLabel = computed(() => {
  const seconds = Math.floor(videoInfo.value?.duration || 0);
  return [Math.floor(seconds / 60), seconds % 60].map(n => String(n).padStart(2, "0")).join(":");
});
async function uploadVideo(event) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (await selectVideo(file)) {
    cover.value = "";
    announce("视频已载入右侧预览，可播放和拖动进度；文件仅在本机读取。");
  }
}
function captureCover() {
  try {
    cover.value = phonePreview.value.captureFrame();
    announce("已将当前视频画面设为封面");
  } catch (error) { announce(error.message); }
}
function clearVideo() { removeVideo(); cover.value = ""; }
const aiBusy = ref("");
const route = useRoute();
const { publishingAccounts: localAccounts, refreshTime } = usePlatformAccounts();
const accountClock = setInterval(refreshTime, 30000);
const savedKey = computed(
  () => `wuyao-publishing-v1-${auth.user?.id || "local"}`,
);
let noticeTimer;
let actionTimer;
const publishing = ref(false);
const notice = ref("");
const title = ref("杭州湖滨，藏着一家值得慢慢吃的炭火烧鸟");
const body = ref(
  "下班后和朋友去吃了这家炭火烧鸟，鸡皮、鸡腿肉和明太子饭团都很惊艳。店里氛围感拉满，窗边还能看到街景，适合约会也适合周末小聚。",
);
const poi = ref("炭火烧鸟·湖滨店");
const poiOptions = [
  { value: '炭火烧鸟·湖滨店', label: '炭火烧鸟·湖滨店', description: '抖音地点 · 湖滨商圈 · 东坡路 7 号' },
  { value: '炭火烧鸟·钱江店', label: '炭火烧鸟·钱江店', description: '高德地点参考 · 钱江商圈 · 钱江路 88 号' },
];

const activeAccounts = computed(() =>
  localAccounts.value.filter((a) => a.platform === activePlatform.value),
);
const displayBody = computed(() => body.value);
const selectedPlatformCount = computed(
  () =>
    new Set(
      localAccounts.value
        .filter((a) => selectedAccounts.value.includes(a.id))
        .map((a) => a.platform),
    ).size,
);
const currentAccount = computed(
  () =>
    localAccounts.value.find(
      (a) =>
        a.platform === previewPlatform.value &&
        selectedAccounts.value.includes(a.id),
    ) || localAccounts.value.find((a) => a.platform === previewPlatform.value),
);
const address = computed(() =>
  poi.value.includes("湖滨")
    ? "杭州市上城区湖滨街道东坡路 7 号（示例）"
    : "杭州市上城区钱江路 88 号（示例）",
);
const titleLimit = computed(() =>
  localAccounts.value.some(
    (a) => a.platform === "小红书" && selectedAccounts.value.includes(a.id),
  )
    ? 20
    : 30,
);
const sensitiveWords = computed(() =>
  ["全网最低", "第一", "100%有效", "绝对"].filter((word) =>
    `${title.value}${body.value}`.includes(word),
  ),
);
const canPublish = computed(
  () =>
    hasMedia.value && !mediaBusy.value && !aiBusy.value &&
    selectedAccounts.value.length > 0 &&
    selectedAccounts.value.every(id => localAccounts.value.some(a => a.id === id)) &&
    title.value.trim().length > 0 &&
    title.value.length <= titleLimit.value &&
    body.value.trim().length > 0 &&
    !sensitiveWords.value.length &&
    (scheduleMode.value === "now" ||
      new Date(scheduledAt.value).getTime() > Date.now()),
);
const toggleAccount = (id) =>
  (selectedAccounts.value = selectedAccounts.value.includes(id)
    ? selectedAccounts.value.filter((x) => x !== id)
    : [...selectedAccounts.value, id]);
const addTag = (tag) => {
  if (!body.value.includes(tag)) body.value = `${body.value} ${tag}`;
};
const announce = (text) => {
  notice.value = text;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => {
    notice.value = "";
  }, 4500);
};
const runAction = (action) => {
  if (action === "草稿保存") return saveDraft();
  if (action === "替换封面图") return coverInput.value?.click();
  if (aiBusy.value || publishing.value) return;

  aiBusy.value = action;
  clearTimeout(actionTimer);
  actionTimer = setTimeout(() => {
    if (action === "生成作品标题") {
      const candidates = [
        `${poi.value}｜周末探店记`,
        `在${poi.value}，慢慢吃顿饭`,
        `同城探店｜${poi.value}`,
      ].map(text => text.slice(0, titleLimit.value));
      title.value = candidates.find(text => text !== title.value) || candidates[0];
    }
    if (action === "生成正文描述") {
      const opening = `${poi.value}，下一次聚餐的备选清单。`;
      const alternate = `周末探店计划：去${poi.value}，留一点时间给好好吃饭。`;
      body.value = `${body.value.startsWith(opening) ? alternate : opening}\n\n想和朋友找个地方坐坐，也想给忙碌的一周留一点松弛感。把这家店先收藏起来，出发前记得确认营业时间和预约情况。\n\n#同城好去处 #周末探店`;
    }
    if (action.includes("扩写"))
      body.value +=
        "\n炭火慢烤，把周末的节奏放慢一点。提前预约窗边座位，和喜欢的人一起，认真吃顿饭。";
    if (action.includes("Tag")) tags.slice(0, 3).forEach(addTag);
    if (action.includes("去重"))
      body.value = [
        ...new Set(
          body.value
            .split(/(?<=[。！？\n])/)
            .map((s) => s.trim())
            .filter(Boolean),
        ),
      ].join("");
    announce(
      action.includes("敏感词")
        ? sensitiveWords.value.length
          ? "本地词表命中：" + sensitiveWords.value.join("、")
          : "本地示例词表未命中敏感词；不代表平台审核结论"
        : action + "：已应用本地演示结果",
    );
    aiBusy.value = "";
  }, 500);
};
const saveDraft = () => {
  try {
    const savedAt = new Date().toISOString();
    localStorage.setItem(
      savedKey.value,
      JSON.stringify({
        version: 1,
        title: title.value,
        body: body.value,
        poi: poi.value,
        comment: comment.value,
        bundleSelections: bundleSelections.value,
        selectedAccounts: selectedAccounts.value,
        localAccounts: localAccounts.value,
        activePlatform: activePlatform.value,
        scheduleMode: scheduleMode.value,
        scheduledAt: scheduledAt.value,
        cover: cover.value,
        videoName: videoInfo.value?.name || "",
        mediaType: mediaType.value,
        imageNames: pictures.value.map(item => item.name),
        musicName: music.value?.name || '',
        musicVolume: musicVolume.value,
        originalVolume: originalVolume.value,
        musicStart: musicStart.value,
        savedAt,
      }),
    );
    announce("草稿已保存；图片、视频和音乐仅在本机预览，刷新后需重新选择源文件。");
  } catch {
    announce("保存失败：浏览器存储空间不足或不可用，请缩小封面后重试");
  }
};
try {
  const draft = JSON.parse(localStorage.getItem(savedKey.value) || "null");
  if (draft?.version === 1) {
    mediaType.value = draft.mediaType === 'image' ? 'image' : 'video';
    for (const [key, target] of Object.entries({ musicVolume, originalVolume })) {
      if (Number.isFinite(draft[key])) target.value = Math.min(100, Math.max(0, draft[key]));
    }
    for (const [key, target] of Object.entries({
      title,
      body,
      poi,
      comment,
      selectedAccounts,
      activePlatform,
      scheduleMode,
      scheduledAt,
      cover,
    })) {
      if (
        typeof draft[key] === typeof target.value &&
        Array.isArray(draft[key]) === Array.isArray(target.value)
      )
        target.value = draft[key];
    }
  }
} catch {
  /* Ignore an unreadable local draft and keep usable fixture defaults. */
}
if (!platforms.some(platform => platform.id === activePlatform.value)) activePlatform.value = platforms[0].id;
watch(localAccounts, list => {
  selectedAccounts.value = selectedAccounts.value.filter(id => list.some(a => a.id === id));
}, { immediate: true });
watch(() => route.query.accountId, id => {
  if (!id) return;
  const account = localAccounts.value.find(a => a.id === id);
  if (!account) {
    selectedAccounts.value = [];
    announce('该账号不存在、授权已失效或缺少发布权限，请前往关联平台管理检查。');
    return;
  }
  selectedAccounts.value = [account.id];
  activePlatform.value = account.platform;
  previewPlatform.value = account.platform;
}, { immediate: true });
const replaceCover = (event) => {
  const file = event.target.files?.[0];
  if (!file) return;
  if (
    !["image/jpeg", "image/png", "image/webp"].includes(file.type) ||
    file.size > 2 * 1024 * 1024
  ) {
    announce("请选择 2MB 以内的 JPG、PNG 或 WebP 图片");
    event.target.value = "";
    return;
  }
  const reader = new FileReader();
  reader.onload = () => {
    cover.value = String(reader.result);
    announce("封面已替换，仅在本机预览");
  };
  reader.onerror = () => announce("图片读取失败，请重新选择");
  reader.readAsDataURL(file);
  event.target.value = "";
};
const publish = () => {
  refreshTime();
  if (
    scheduleMode.value === "later" &&
    new Date(scheduledAt.value).getTime() <= Date.now()
  ) {
    announce("预约时间已过期，请重新选择");
    return;
  }
  if (!canPublish.value) {
    announce("请先添加视频或图片，并检查标题字数、账号、正文和预约时间。");
    return;
  }
  publishing.value = true;
  clearTimeout(actionTimer);
  actionTimer = setTimeout(() => {
    publishing.value = false;
    announce(
      "发布预演完成：" +
        selectedPlatformCount.value +
        " 个平台 / " +
        selectedAccounts.value.length +
        " 个账号；未向真实平台发送",
    );
  }, 800);
};
watch(activePlatform, (value) => {
  previewPlatform.value = value;
});
onBeforeUnmount(() => {
  clearInterval(accountClock);
  libraryAbort.abort();
  clearTimeout(noticeTimer);
  clearTimeout(actionTimer);
});
</script>

<template>
  <div
    :data-studio-step="studioStep" class="publishing-page antialiased font-sans selection:bg-blue-100 selection:text-blue-900"
  >
    <header class="pub-header">
      <div class="pub-header-left">
        <div class="pub-title-block">
          <h1 class="text-xl font-bold tracking-tight">
            内容刊印 <span class="title-slash">/</span> 把烟火带到更远的地方
          </h1>
        </div>
      </div>
    </header>

    <div v-if="notice" class="toast" role="status">
      <Check :size="15" /> {{ notice }}
    </div>
    <input
      ref="coverInput"
      type="file"
      accept="image/jpeg,image/png,image/webp"
      hidden
      @change="replaceCover"
    />
    <input ref="videoInput" type="file" accept="video/mp4,video/webm,video/quicktime,.mp4,.mov,.webm" hidden @change="uploadVideo" />
    <input ref="imageInput" type="file" accept="image/jpeg,image/png,image/webp" multiple hidden @change="uploadPictures" />
    <input ref="musicInput" type="file" accept="audio/mpeg,audio/wav,audio/mp4,audio/ogg,audio/aac,.mp3,.wav,.m4a,.ogg,.aac" hidden @change="uploadMusic" />
    <PublishingAssetPicker ref="assetPicker" :kind="pickerKind" :limit="pickerLimit" :busy="libraryBusy" :error="libraryError" @select="chooseLibrary" />
    <p class="studio-demo-note">演示工作台 · 文案为本地示例，发布仅预演；不会向真实平台发送。</p>
    <nav class="studio-mobile-steps" aria-label="刊印步骤"><button v-for="(label, index) in ['素材', '文案与挂载', '预览']" :key="label" :aria-current="studioStep === index ? 'step' : undefined" @click="studioStep = index">0{{ index + 1 }} {{ label }}</button></nav>
    <main class="pub-grid">
      <section class="studio-upload-column" tabindex="0" aria-label="素材上传区">
        <div class="pub-section asset-section" :aria-busy="mediaBusy" @dragover.prevent @drop.prevent="dropMedia">
          <div class="section-heading">
            <div>
              <h2 class="flex items-center gap-2"><Images :size="18" aria-hidden="true" />作品核心素材</h2>
              <p>{{ mediaType === 'video' ? '视频作品' : `图文作品 · ${pictures.length} / ${IMAGE_LIMIT} 张图片` }}</p>
            </div>
            <div class="asset-actions">
              <button class="pub-upload-primary" :disabled="mediaBusy || publishing || (mediaType === 'image' && pictures.length >= IMAGE_LIMIT)" @click="uploadLocal">
                <LoaderCircle v-if="mediaBusy" class="pub-loading" :size="14" /><Upload v-else :size="14" />
                {{ mediaBusy ? '正在读取…' : '本地上传' }}
              </button>
              <button :disabled="mediaBusy || publishing || (mediaType === 'image' && pictures.length >= IMAGE_LIMIT)" @click="openLibrary()"><FolderOpen :size="14" />从素材库选择</button>
            </div>
          </div>
          <div class="pub-media-tabs" role="group" aria-label="作品类型">
            <button :aria-pressed="mediaType === 'video'" :disabled="mediaBusy || publishing" @click="mediaType = 'video'"><Video :size="15" />视频</button>
            <button :aria-pressed="mediaType === 'image'" :disabled="mediaBusy || publishing" @click="mediaType = 'image'"><Images :size="15" />图文</button>
          </div>
          <div v-if="!hasMedia" class="pub-upload-zone pub-upload-empty">
            <component :is="mediaType === 'video' ? Video : Images" :size="26" /><strong>{{ mediaType === 'video' ? '拖入作品视频' : '拖入作品图片' }}</strong>
            <span>{{ mediaType === 'video' ? 'MP4 / MOV / WebM · 最大 500 MB' : `JPG / PNG / WebP · 单张 20 MB · 最多 ${IMAGE_LIMIT} 张` }}</span>
            <div class="asset-actions"><button :disabled="mediaBusy" @click="uploadLocal"><Upload :size="14" />本地上传</button><button :disabled="mediaBusy" @click="openLibrary()"><FolderOpen :size="14" />从素材库选择</button></div>
          </div>
          <div v-else-if="mediaType === 'video'" class="pub-video-file">
            <Video :size="24" /><div>
              <b>{{ videoInfo.name }}</b><div class="spec-row">
                <span>{{ durationLabel }}</span><span>{{ videoInfo.width }} × {{ videoInfo.height }}</span><span>{{ (videoInfo.size / 1024 / 1024).toFixed(1) }} MB</span>
              </div>
            </div>
            <button aria-label="移除视频" :disabled="mediaBusy || publishing" @click="clearVideo"><Trash2 :size="17" /></button>
          </div>
          <div v-else class="pub-picture-grid">
            <div v-for="(picture, index) in pictures" :key="picture.id" class="pub-picture-item">
              <button class="pub-picture-select" :aria-label="`预览第 ${index + 1} 张图片`" :aria-pressed="activePicture === index" @click="activePicture = index"><img :src="picture.url" :alt="picture.name" /><span>{{ index === 0 ? '封面' : index + 1 }}</span></button>
              <div class="pub-picture-actions"><button :disabled="index === 0 || mediaBusy" :aria-label="`前移第 ${index + 1} 张图片`" title="向前移" @click="movePicture(index, -1); activePicture = index - 1"><ArrowLeft :size="13" /></button><button :disabled="index === pictures.length - 1 || mediaBusy" :aria-label="`后移第 ${index + 1} 张图片`" title="向后移" @click="movePicture(index, 1); activePicture = index + 1"><ArrowRight :size="13" /></button><button :disabled="mediaBusy" :aria-label="`删除第 ${index + 1} 张图片`" title="删除图片" @click="removePicture(picture.id)"><Trash2 :size="13" /></button></div>
            </div>
          </div>
          <p v-if="videoError && mediaType === 'video'" class="pub-error" role="alert">{{ videoError }}</p>
          <p v-if="attachmentsError" class="pub-error" role="alert">{{ attachmentsError }}</p>
          <div v-if="mediaType === 'video'" class="pub-cover-tools">
            <button v-if="cover" class="pub-cover-thumb" aria-label="放大预览封面" @click="coverDialog?.showModal()"><img :src="cover" alt="当前作品封面" /><ZoomIn :size="15" /></button>
            <div>
              <b>作品封面</b><p>{{ cover ? '已设置封面，右侧仍显示原视频。' : '可截取视频当前帧，或单独上传封面。' }}</p>
              <div class="asset-actions">
                <button :disabled="!videoUrl || videoBusy" @click="captureCover"><Image :size="14" />截取当前帧</button>
                <button @click="coverInput?.click()"><Upload :size="14" />{{ cover ? '替换封面' : '上传封面' }}</button>
              </div>
            </div>
          </div>
        </div>

        <div class="pub-section pub-music-section">
          <div class="section-heading"><div><h2 class="flex items-center gap-2"><Music2 :size="18" />背景音乐</h2><p>视频与图文均可配乐</p></div><span class="selection-count">可选</span></div>
          <div class="asset-actions"><button :disabled="mediaBusy" @click="musicInput?.click()"><Upload :size="14" />上传音乐</button><button :disabled="mediaBusy" @click="openLibrary('audio')"><FolderOpen :size="14" />素材库音乐</button></div>
          <p v-if="attachmentsError" class="pub-error" role="alert">{{ attachmentsError }}</p>
          <div v-if="music" class="pub-music-editor">
            <div class="pub-music-track"><Music2 :size="22" /><div><b>{{ music.name }}</b><small>{{ Math.floor(music.duration / 60) }}:{{ String(Math.floor(music.duration % 60)).padStart(2, '0') }}</small></div><button class="pub-icon-button" :aria-label="musicPlaying ? '暂停配乐试听' : '试听配乐'" :title="musicPlaying ? '暂停试听' : '试听配乐'" @click="phonePreview?.toggleMusic()"><Pause v-if="musicPlaying" :size="17" /><Play v-else :size="17" /></button><button class="pub-icon-button" aria-label="移除背景音乐" title="移除音乐" :disabled="mediaBusy" @click="removeMusic"><X :size="17" /></button></div>
            <div class="pub-music-settings"><label>配乐音量 <output>{{ musicVolume }}%</output><input v-model.number="musicVolume" type="range" min="0" max="100" aria-label="配乐音量" /></label><label v-if="mediaType === 'video'">视频原声 <output>{{ originalVolume }}%</output><input v-model.number="originalVolume" type="range" min="0" max="100" aria-label="视频原声音量" /></label><label>音乐起点 <output>{{ musicStart }} 秒</output><input v-model.number="musicStart" type="range" min="0" :max="Math.max(0, Math.floor(music.duration - 1))" step="1" aria-label="音乐起播位置" /></label></div>
          </div>
          <p class="pub-music-note">使用自有或已获授权的音频。平台曲库与发布混音尚未接入，当前可在此试听效果。</p>
        </div>

        <div class="feature-tags pub-section"><h2>AI 店铺特征</h2><p>识别服务待接入。上传素材后，可先手动标注门店特征。</p><label>特征标签<input class="pub-input" placeholder="例如：街边小馆、炭火、手作" /></label></div>
      </section>
      <section class="editor-column" tabindex="0" aria-label="文案与本地化挂载">
        <div class="pub-section platform-section">
          <div class="section-heading">
            <div>
              <h2 class="flex items-center gap-2">
                <UsersRound :size="18" aria-hidden="true" />目标平台与账号
              </h2>
              <p>选择发布渠道和已授权的商家账号，可同时勾选多个账号。</p>
            </div>
            <span class="selection-count">{{ selectedAccounts.length }} 个账号已选</span>
          </div>
          <div class="platform-tabs">
            <button
              v-for="item in platforms"
              :key="item.id"
              :aria-pressed="activePlatform === item.id"
              :class="['platform-tab', { active: activePlatform === item.id }]"
              @click="activePlatform = item.id"
            >
              <span
                class="pub-platform-icon"
                :class="{
                  'pub-platform-icon--douyin': item.id === '抖音',
                  'pub-platform-icon--redbook': item.id === '小红书',
                  'pub-platform-icon--channels': item.id === '视频号',
                }"
                aria-hidden="true"
              ><img
                v-if="item.id === '抖音'"
                src="/images/publishing/platforms/tiktok.svg"
                alt=""
              /><img
                v-else-if="item.id === '小红书'"
                src="/images/publishing/platforms/xiaohongshu.svg"
                alt=""
              /><Video v-else :size="23" /></span>
              <span><b>{{ item.label }}</b><small>{{ item.sub }}</small></span><span
                v-if="activePlatform === item.id"
                class="pub-platform-check"
              ><Check :size="12" :stroke-width="3" aria-hidden="true" /></span>
            </button>
          </div>
          <div class="account-grid">
            <button
              v-for="account in activeAccounts"
              :key="account.id"
              :aria-pressed="selectedAccounts.includes(account.id)"
              :class="[
                'account-card',
                { selected: selectedAccounts.includes(account.id) },
              ]"
              @click="toggleAccount(account.id)"
            >
              <span class="account-avatar"><img
                src="/images/publishing/restaurant.jpg"
                alt=""
                :style="{
                  objectPosition:
                    account.id === 'dy-main' ? '15% center' : '85% center',
                }"
              /><span>{{ account.avatar }}</span></span><span class="account-info"><b>{{ account.name }}</b><small class="pub-account-handle">{{ account.handle }}</small><span class="pub-account-meta"><span class="pub-fan-count font-mono font-medium">{{ account.fans }}<span>粉丝</span></span></span><em><span class="status-dot" /> {{ account.status }}</em></span><span class="check-box"><Check v-if="selectedAccounts.includes(account.id)" :size="13" /></span>
            </button>
          </div>
          <p v-if="!activeAccounts.length" class="pub-music-note">此平台暂无具备发布权限的账号，<RouterLink to="/publishing/platforms">前往关联平台管理绑定或续期</RouterLink>。</p>
        </div>

        <div class="pub-section copy-section">
          <div class="section-heading">
            <div>
              <h2 class="flex items-center gap-2">
                <PenLine :size="18" aria-hidden="true" />文本内容与 AI 润色
              </h2>
              <p>针对{{ activePlatform }}的内容规范，实时同步右侧预览。</p>
            </div>
          </div>
          <div class="copy-version-tabs" aria-label="文案风格"><button v-for="version in copyVariants" :key="version" :aria-pressed="copyVersion === version" :disabled="!!aiBusy || publishing" @click="selectCopyVersion(version)">{{ version }}</button></div>
          <div class="pub-field-heading">
            <label class="field-label" for="title">作品标题</label>
            <button type="button" class="pub-field-generate" aria-label="AI 智能生成作品标题" aria-controls="title" :aria-busy="aiBusy === '生成作品标题'" :disabled="!!aiBusy || publishing" title="根据当前门店生成标题（本地演示）" @click="runAction('生成作品标题')">
              <LoaderCircle v-if="aiBusy === '生成作品标题'" class="pub-loading" :size="14" /><WandSparkles v-else :size="14" />{{ aiBusy === '生成作品标题' ? '生成中…' : 'AI 智能生成' }}
            </button>
          </div><input
            id="title"
            v-model="title"
            :readonly="aiBusy === '生成作品标题'"
            :aria-invalid="title.length > titleLimit"
            class="pub-input"
            :class="{ over: title.length > titleLimit }"
            maxlength="60"
          />
          <p v-if="title.length > titleLimit" class="pub-error">
            标题超出所选平台上限，请缩短至 {{ titleLimit }} 字以内。
          </p>
          <div class="pub-field-heading">
            <label class="field-label" for="body">正文描述</label>
            <button type="button" class="pub-field-generate" aria-label="AI 智能生成正文描述" aria-controls="body" :aria-busy="aiBusy === '生成正文描述'" :disabled="!!aiBusy || publishing" title="根据当前门店生成正文（本地演示）" @click="runAction('生成正文描述')">
              <LoaderCircle v-if="aiBusy === '生成正文描述'" class="pub-loading" :size="14" /><WandSparkles v-else :size="14" />{{ aiBusy === '生成正文描述' ? '生成中…' : 'AI 智能生成' }}
            </button>
          </div><textarea id="body" v-model="body" :readonly="aiBusy === '生成正文描述'" class="pub-textarea" rows="4" />
          <div class="ai-toolbar">
            <div class="ai-toolbar-head">
              <span><WandSparkles :size="15" /> AI 提效工具</span><small>本地演示 · 未连接 AI 服务</small>
            </div>
            <div class="ai-actions" :aria-busy="!!aiBusy">
              <button :disabled="!!aiBusy" @click="runAction('AI 扩写 / 润色')">
                AI 扩写 / 润色
              </button><button :disabled="!!aiBusy" @click="runAction('敏感词自检')">
                敏感词自检
              </button><button
                :disabled="!!aiBusy"
                @click="runAction('热门同城 Tag 提取')"
              >
                提取热门同城 Tag
              </button><button :disabled="!!aiBusy" @click="runAction('爆款文案去重')">
                爆款文案去重
              </button>
            </div>
          </div>
          <div class="tag-library">
            <span>快捷标签</span><button v-for="tag in tags" :key="tag" @click="addTag(tag)">
              {{ tag }} <Plus :size="11" />
            </button>
          </div>
        </div>

        <div class="pub-section local-section">
          <div class="section-heading">
            <div>
              <h2 class="flex items-center gap-2">
                <Store :size="18" aria-hidden="true" />本地生活专属挂载
              </h2>
              <p>这不是流量，这是生意。关联抖音门店 POI 与团购套餐，承接到店转化。</p>
            </div>
            <span class="commerce-badge"><MapPin :size="13" /> 商业闭环</span>
          </div>
          <div class="form-grid">
            <div>
              <label class="field-label" for="poi">POI 门店定位</label>
              <PublishingSelect id="poi" v-model="poi" label="POI 门店定位" :options="poiOptions">
                <template #icon><MapPin :size="16" /></template>
              </PublishingSelect>
              <small class="helper">{{ address }}</small>
            </div>
            <PublishingCommerce :accounts="localAccounts.filter(a => selectedAccounts.includes(a.id))" :store="poi" @change="bundleSelections = $event" />
          </div>
          <div class="pin-copy">
            <label for="pinned-comment" class="field-label">评论区自动置顶引流话术 <small>可选</small></label>
            <div class="pin-input">
              <MessageCircle :size="14" /><input
                id="pinned-comment"
                v-model="comment"
              />
            </div>
          </div>
        </div>

        <div class="pub-section strategy-section">
          <div class="section-heading">
            <div>
              <h2 class="flex items-center gap-2">
                <CalendarClock :size="18" aria-hidden="true" />分发策略
              </h2>
              <p>选择内容进入平台的时间。</p>
            </div>
          </div>
          <div class="strategy-options">
            <label
              :class="['strategy-option', { selected: scheduleMode === 'now' }]"
              @click="scheduleMode = 'now'"
            ><input v-model="scheduleMode" type="radio" value="now" /><span><Send :size="16" /><b>立即发布</b><small>审核通过后立即同步</small></span><Check v-if="scheduleMode === 'now'" :size="15" /></label><label
              :class="[
                'strategy-option',
                { selected: scheduleMode === 'later' },
              ]"
              @click="scheduleMode = 'later'"
            ><input v-model="scheduleMode" type="radio" value="later" /><span><Clock3 :size="16" /><b>定时发布</b><small>选择一个更合适的时机</small></span><Check v-if="scheduleMode === 'later'" :size="15" /></label>
          </div>
          <div v-if="scheduleMode === 'later'" class="schedule-picker">
            <label for="publish-at">预约日期和时间</label><input
              id="publish-at"
              v-model="scheduledAt"
              type="datetime-local"
            /><span>本地生活晚高峰推荐：18:30 - 20:00</span>
          </div>
        </div>
      </section>

      <aside class="preview-column">
        <div class="preview-sticky">
          <div class="preview-topline">
            <div class="preview-switch" aria-label="预览平台">
              <button
                :class="{ active: previewPlatform === '抖音' }"
                @click="previewPlatform = '抖音'"
              >
                抖音
              </button><button
                :class="{ active: previewPlatform === '小红书' }"
                @click="previewPlatform = '小红书'"
              >
                小红书
              </button><button :class="{ active: previewPlatform === '视频号' }" @click="previewPlatform = '视频号'">视频号</button>
            </div>
          </div>
          <div v-if="previewPlatform === '小红书'" class="xhs-feed" aria-label="小红书双列封面预览"><article v-for="(picture, index) in (pictures.length ? pictures.slice(0, 4) : cover ? [{ url: cover, name: title }] : [])" :key="picture.url"><img :src="picture.url" :alt="picture.name || title" /><strong>{{ title || '封面标题' }}</strong><small>{{ currentAccount?.name || '门店账号' }} · {{ index + 1 }}</small></article><p v-if="!pictures.length && !cover">上传图片或设置视频封面，即可查看双列信息流效果。</p></div>
          <PublishingPhone
            ref="phonePreview"
            :video-url="mediaType === 'video' ? videoUrl : ''"
            :pictures="mediaType === 'image' ? pictures : []"
            :media-type="mediaType"
            :picture-index="activePicture"
            :music="music"
            :music-volume="musicVolume"
            :original-volume="originalVolume"
            :music-start="musicStart"
            :uploading="mediaBusy"
            :preview-platform="previewPlatform"
            :title="title"
            :display-body="displayBody"
            :poi="poi"
            :current-account="currentAccount"
            @upload="uploadLocal"
            @library="openLibrary()"
            @picture-change="activePicture = $event"
            @music-playing="musicPlaying = $event"
            @playback-error="announce"
          />
          <p v-if="!canPublish" class="pub-error">
            {{ !hasMedia ? "请先添加视频或图片，再确认发布。" : "请选择账号，填写正文与有效标题；定时发布需选择未来时间。" }}
          </p>
        </div>
      </aside>
    </main>
    <div class="publish-bar studio-bottom-bar">
      <label class="studio-schedule">刊印时间<select v-model="scheduleMode" aria-label="刊印时间"><option value="now">立即发布</option><option value="later">定时发布</option></select><input v-if="scheduleMode === 'later'" v-model="scheduledAt" type="datetime-local" aria-label="定时刊印日期" /></label><button class="draft-btn" @click="runAction('草稿保存')">
        存为草稿
      </button><button
        class="publish-btn"
        :disabled="publishing || !canPublish"
        :aria-busy="publishing"
        @click="publish"
      >
        {{
          publishing
            ? "正在预演…"
            : `一键刊印全网 · ${selectedAccounts.length} 个账号`
        }}
        <LoaderCircle
          v-if="publishing"
          class="pub-loading"
          :size="16"
          aria-hidden="true"
        /><Send v-else :size="16" aria-hidden="true" />
      </button>
    </div>
    <dialog
      ref="coverDialog"
      class="pub-cover-dialog"
      aria-labelledby="pub-cover-dialog-title"
      @click="$event.target === $event.currentTarget && coverDialog?.close()"
    >
      <div class="pub-cover-dialog-header">
        <div>
          <h2 id="pub-cover-dialog-title">封面预览</h2>
          <p>当前发布封面</p>
        </div>
        <button
          autofocus
          type="button"
          aria-label="关闭封面预览"
          @click="coverDialog?.close()"
        >
          <X :size="20" />
        </button>
      </div>
      <div class="pub-cover-art"><img v-if="cover" :src="cover" alt="当前作品发布封面" /></div>
    </dialog>
  </div>
</template>
