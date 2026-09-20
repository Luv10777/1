import React, { useEffect, useRef, useState } from "react";
import {
  ArrowUpRight,
  Bot,
  Check,
  ChevronDown,
  ChevronRight,
  CircleAlert,
  Clock3,
  Edit3,
  Flame,
  Image as ImageIcon,
  Inbox,
  Leaf,
  LockKeyhole,
  MessageCircle,
  PenLine,
  PhoneCall,
  Search,
  Settings2,
  ShieldCheck,
  Sparkles,
  Star,
  X,
} from "lucide-react";

/** React 18 single-file workbench. Requires Tailwind CSS and lucide-react.
 * All platform operations below are local mock transitions; no external messages are sent.
 */
type Platform = "大众点评" | "美团外卖" | "抖音团购" | "小红书";
type ReviewStatus = "pending" | "auto" | "manual";
type Review = {
  id: number;
  platform: Platform;
  tone: string;
  level: string;
  avatar: string;
  nickname: string;
  time: string;
  order: string;
  rating: number;
  status: ReviewStatus;
  image?: string;
  content: string;
  tags: string[];
  draft: string;
  strategies: string[];
  strategy: number;
  following: boolean;
  expanded: boolean;
};
type Policy = {
  stars: number[];
  keywords: string[];
  delay: boolean;
  compensation: boolean;
  sms: boolean;
};
const DEFAULT_POLICY: Policy = {
  stars: [4, 5],
  keywords: ["卫生", "腹泻", "发票"],
  delay: true,
  compensation: true,
  sms: true,
};
const PLATFORM_META: { label: Platform; tone: string }[] = [
  { label: "大众点评", tone: "dianping" },
  { label: "美团外卖", tone: "meituan" },
  { label: "抖音团购", tone: "douyin" },
  { label: "小红书", tone: "xiaohongshu" },
];
const FILTERS = [
  "全部星级",
  "需掌柜确认",
  "AI 已代发",
  "低星差评",
  "已处理",
] as const;
const NEGATIVE_DRAFTS = [
  "客官万望海涵，让您在[一方小院]受了冷遇，掌柜在此躬身致歉。我们已即刻调取当晚出餐记录，确实因后厨传菜温控脱节导致牛肉煲温度不足。涉事传菜流程已闭门整改，我们愿为您本次订单全额退款，并私信备下当季手作甜茶一份。恳请客官再给我们一次弥补的机会。",
  "客官您好，这次出品温度与服务体验没有达到应有的标准，我们深感抱歉。掌柜愿为本次订单安排全额退款，并在您下次到店时赠送手作甜茶与桂花糕。我们会专人跟进整改与补偿事宜，期待有机会认真弥补这次遗憾。",
  "客官您好，非常抱歉让您经历了不愉快的一餐。关于牛肉煲的温度、等餐时间与服务态度，掌柜已经安排核对当晚的出餐记录与值班情况。烦请通过平台私信告知订单时间，我们会尽快反馈核实结果并协商妥善的处理方案。感谢您给我们改进的机会。",
];
const MEDIUM_DRAFTS = [
  "感谢客官愿意留下具体感受。配送与包装没有把这份心意好好送到，我们已经把本单反馈同步给配送与打包岗位。下次到店或下单时，掌柜为您添一份桂花糕作赔礼，盼能把这次的遗憾补回来。",
  "客官您好，感谢您认可菜品的味道，也对这次配送和汤汁洒漏向您致歉。我们愿补送一份桂花糕，并与您确认受影响菜品的处理方案。请通过平台私信联系掌柜，我们会专人跟进。",
  "感谢您的提醒。我们正在检查汤品封口与外卖打包流程，并核对该订单的配送时间。后续将增加出餐前封口检查，让每一份餐食都更稳妥地送达。期待下次带给您更好的体验。",
];
const INITIAL_REVIEWS: Review[] = [
  {
    id: 1,
    platform: "大众点评",
    tone: "dianping",
    level: "Lv.6",
    avatar: "M",
    nickname: "Momo不吃香菜",
    time: "今天 11:42",
    order: "招牌牛肉煲 · 双人套餐",
    rating: 1,
    status: "pending",
    image: "/images/publishing/restaurant.jpg",
    content:
      "上菜慢就算了，招牌牛肉煲上来竟然是温的，服务员还爱搭不理，体验极差！",
    tags: [
      "情绪指数：极度不满",
      "核心痛点：出品温控、服务态度",
      "舆情风险：二级",
    ],
    draft: NEGATIVE_DRAFTS[0],
    strategies: ["诚挚深切致歉", "补偿方案升级", "委婉事实核验"],
    strategy: 0,
    following: false,
    expanded: false,
  },
  {
    id: 2,
    platform: "美团外卖",
    tone: "meituan",
    level: "已消费",
    avatar: "清",
    nickname: "清风徐来",
    time: "今天 10:18",
    order: "桂花糕 · 手撕鸡 · 外带",
    rating: 3,
    status: "pending",
    content: "味道还是可以的，但配送等了有点久，包装里汤汁洒出来了一些。",
    tags: ["情绪指数：略有失望", "核心痛点：配送时效、包装", "舆情风险：低"],
    draft: MEDIUM_DRAFTS[0],
    strategies: ["温和承接情绪", "轻量补偿", "改善承诺"],
    strategy: 0,
    following: false,
    expanded: false,
  },
  {
    id: 3,
    platform: "小红书",
    tone: "xiaohongshu",
    level: "笔记评论",
    avatar: "L",
    nickname: "Luna在杭州",
    time: "今天 09:36",
    order: "探店笔记 · 4 人晚餐",
    rating: 5,
    status: "auto",
    content: "环境很雅致，竹林庭院很有禅意，桂花糕和手撕鸡必点！",
    tags: [],
    draft:
      "谢谢 Luna 把一方小院的桂花香带进笔记里。能和你一起在竹影里吃一顿饭，是我们今日收到的温柔回响。下次来，掌柜给你留一盏新茶。",
    strategies: [],
    strategy: 0,
    following: false,
    expanded: false,
  },
  {
    id: 4,
    platform: "抖音团购",
    tone: "douyin",
    level: "团购评价",
    avatar: "Y",
    nickname: "Yuki的周末",
    time: "今天 09:05",
    order: "时令双人套餐",
    rating: 5,
    status: "auto",
    content: "店里很安静，服务也很细致，适合约会和朋友小聚，已经收藏。",
    tags: [],
    draft:
      "谢谢你把这份安静的好时光分享给更多人。小院会继续把每一盏茶、每一道菜都照料好，等你下次来坐。",
    strategies: [],
    strategy: 0,
    following: false,
    expanded: false,
  },
];

function Stars({ rating }: { rating: number }) {
  return (
    <span className="rating-stars" aria-label={`${rating} 星`}>
      {[1, 2, 3, 4, 5].map((star) => (
        <Star
          key={star}
          size={14}
          fill={star <= rating ? "currentColor" : "none"}
          aria-hidden="true"
        />
      ))}
      <em className={rating > 3 ? "positive-rating" : ""}>
        {rating} 星{rating <= 2 ? "差评" : rating === 3 ? "中评" : "好评"}
      </em>
    </span>
  );
}
function Toggle({
  checked,
  label,
  onChange,
}: {
  checked: boolean;
  label: string;
  onChange: () => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={onChange}
      className={`switch-on ${checked ? "" : "switch-off"}`}
    >
      <span />
    </button>
  );
}
function PlatformMark({ tone, platform }: { tone: string; platform: string }) {
  return (
    <span className={`platform-mark platform-${tone}`} aria-label={platform}>
      {platform.slice(0, 1)}
    </span>
  );
}

export default function ReviewsWorkbench() {
  const [reviews, setReviews] = useState<Review[]>(() =>
    INITIAL_REVIEWS.map((review) => ({ ...review })),
  );
  const [platform, setPlatform] = useState("全部");
  const [filter, setFilter] = useState<string>("全部星级");
  const [query, setQuery] = useState("");
  const [policy, setPolicy] = useState<Policy>(DEFAULT_POLICY);
  const [draftPolicy, setDraftPolicy] = useState<Policy>(DEFAULT_POLICY);
  const [drawer, setDrawer] = useState(false);
  const [toast, setToast] = useState("");
  const [editor, setEditor] = useState<{ id: number; text: string } | null>(
    null,
  );
  const [photo, setPhoto] = useState<string | null>(null);
  const [sending, setSending] = useState<number[]>([]);
  const [keyword, setKeyword] = useState("");
  const [error, setError] = useState("");
  const toastTimer = useRef<ReturnType<typeof setTimeout>>();
  const sendTimers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const sendingIds = useRef(new Set<number>());
  const drawerRef = useRef<HTMLElement>(null);
  const policyButton = useRef<HTMLButtonElement>(null);
  const photoRef = useRef<HTMLDivElement>(null);
  const editorRef = useRef<HTMLTextAreaElement>(null);
  const pending = reviews.filter(
    (review) => review.status === "pending",
  ).length;
  const visible = reviews.filter(
    (review) =>
      (platform === "全部" || review.platform === platform) &&
      (filter === "全部星级" ||
        (filter === "需掌柜确认" && review.status === "pending") ||
        (filter === "AI 已代发" && review.status === "auto") ||
        (filter === "低星差评" && review.rating <= 3) ||
        (filter === "已处理" && review.status !== "pending")) &&
      `${review.nickname} ${review.content} ${review.order}`
        .toLowerCase()
        .includes(query.trim().toLowerCase()),
  );
  const waiting = visible.filter((review) => review.status === "pending");
  const handled = visible.filter((review) => review.status !== "pending");
  const selectedReview = editor
    ? reviews.find((review) => review.id === editor.id)
    : undefined;

  useEffect(
    () => () => {
      clearTimeout(toastTimer.current);
      sendTimers.current.forEach(clearTimeout);
    },
    [],
  );
  useEffect(() => {
    if (editor) editorRef.current?.focus();
  }, [editor?.id]);
  useEffect(() => {
    if (!drawer && !photo) return;
    const previous = document.activeElement as HTMLElement | null;
    const container = drawer ? drawerRef.current : photoRef.current;
    container?.querySelector<HTMLElement>("button")?.focus();
    const keyboard = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setDrawer(false);
        setPhoto(null);
      }
      if (event.key !== "Tab" || !container) return;
      const controls = Array.from(
        container.querySelectorAll<HTMLElement>(
          'button,input,select,textarea,[tabindex="0"]',
        ),
      ).filter((element) => !element.hasAttribute("disabled"));
      const first = controls[0],
        last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    };
    document.addEventListener("keydown", keyboard);
    return () => {
      document.removeEventListener("keydown", keyboard);
      previous?.focus();
    };
  }, [drawer, photo]);

  function notify(message: string) {
    clearTimeout(toastTimer.current);
    setToast(message);
    toastTimer.current = setTimeout(() => setToast(""), 3200);
  }
  function update(id: number, changes: Partial<Review>) {
    setReviews((current) =>
      current.map((review) =>
        review.id === id ? { ...review, ...changes } : review,
      ),
    );
  }
  function send(review: Review, text = review.draft) {
    if (!text.trim()) {
      setError("回复内容不能为空，请先填写拟答内容。");
      notify("请先填写回复内容");
      return;
    }
    if (sendingIds.current.has(review.id)) return;
    sendingIds.current.add(review.id);
    setSending((current) => [...current, review.id]);
    setError("");
    if (editor?.id === review.id) setEditor(null);
    sendTimers.current.push(
      setTimeout(() => {
        update(review.id, {
          status: "manual",
          draft: text.trim(),
          expanded: true,
        });
        setSending((current) => current.filter((id) => id !== review.id));
        sendingIds.current.delete(review.id);
        notify(`已回复并同步至${review.platform}（模拟）`);
      }, 320),
    );
  }
  function openPolicy() {
    setDraftPolicy({
      ...policy,
      stars: [...policy.stars],
      keywords: [...policy.keywords],
    });
    setKeyword("");
    setDrawer(true);
  }
  function toggleStar(star: number) {
    setDraftPolicy((current) => ({
      ...current,
      stars: current.stars.includes(star)
        ? current.stars.filter((value) => value !== star)
        : [...current.stars, star].sort(),
    }));
  }
  function toggleKeyword(word: string) {
    setDraftPolicy((current) => ({
      ...current,
      keywords: current.keywords.includes(word)
        ? current.keywords.filter((value) => value !== word)
        : [...current.keywords, word],
    }));
  }
  function addKeyword() {
    const value = keyword.trim();
    if (!value) return;
    setDraftPolicy((current) => ({
      ...current,
      keywords: Array.from(new Set([...current.keywords, value])),
    }));
    setKeyword("");
  }
  function changeStrategy(review: Review, index: number) {
    update(review.id, {
      strategy: index,
      draft: (review.rating === 1 ? NEGATIVE_DRAFTS : MEDIUM_DRAFTS)[index],
    });
    notify("已按所选策略重新拟答，可继续编辑");
  }

  return (
    <div className="reviews-page bg-[#F8F6F1] text-[#1C3532]">
      <style>{workbenchCSS}</style>
      <header className="reviews-heading">
        <div>
          <div className="reviews-breadcrumb">
            <span>智能客服</span>
            <ChevronRight size={12} />
            <strong>全渠评论 / 差评智能回复</strong>
          </div>
          <div className="title-lockup">
            <div className="title-seal" aria-hidden="true">
              志
            </div>
            <div>
              <p className="reviews-eyebrow">REPUTATION · 一方口碑</p>
              <h1>全渠口碑视界</h1>
              <p className="reviews-subtitle">
                让每一条评论，都被认真听见、妥帖回应。
              </p>
            </div>
          </div>
        </div>
        <div className="heading-actions">
          <div className="live-status">
            <span className="live-dot" />
            <div>
              <b>一方灵犀 · 全天候盯盘中</b>
              <small>四大平台已接入 · 演示数据</small>
            </div>
          </div>
          <button
            ref={policyButton}
            className="quiet-button"
            onClick={openPolicy}
          >
            <Settings2 size={15} />
            全局托管策略设置
          </button>
        </div>
      </header>

      <section className="overview-grid" aria-label="口碑经营概览">
        <article className="overview-card overview-score">
          <div className="overview-card-top">
            <span className="overline">全网综合星级</span>
            <span className="metric-note">
              <ArrowUpRight size={13} />
              较昨日 +0.2
            </span>
          </div>
          <div className="score-line">
            <strong>4.8</strong>
            <span className="stars">
              {[1, 2, 3, 4, 5].map((star) => (
                <Star key={star} size={15} fill="currentColor" />
              ))}
              <span>/ 5.0</span>
            </span>
          </div>
          <div className="score-bottom">
            <span>点评 · 美团 · 抖音 · 小红书</span>
            <span className="sentiment">
              <span className="sentiment-dot" />
              今日正面情绪 94.2%
            </span>
          </div>
        </article>
        <article className="overview-card">
          <div className="overview-card-top">
            <span className="overline">今日代答公文</span>
            <span className="metric-icon">
              <MessageCircle size={16} />
            </span>
          </div>
          <div className="big-metric">
            38 <small>条好评</small>
          </div>
          <div className="overview-foot">
            <span>灵犀自动完成</span>
            <b>节省约 1.5 小时人工</b>
          </div>
          <div className="mini-bar">
            <span style={{ width: "76%" }} />
          </div>
        </article>
        <button
          className="overview-card overview-alert clickable"
          onClick={() => setFilter("需掌柜确认")}
          aria-label={`朱砂待决 ${pending} 条，筛选需掌柜确认`}
        >
          <div className="overview-card-top">
            <span className="overline">朱砂待决 · 高危差评</span>
            <span className="metric-icon alert-icon">
              <CircleAlert size={17} />
            </span>
          </div>
          <div className="big-metric alert-number" aria-live="polite">
            {pending} <small>条待决</small>
          </div>
          <div className="overview-foot">
            <span>每一份歉意，由掌柜落印</span>
            <b>
              查看待决 <ArrowUpRight size={12} />
            </b>
          </div>
          <div className="alert-rule">
            <span />
            <span />
            <span />
          </div>
        </button>
      </section>

      <section className="policy-ribbon" aria-label="策略总控分流">
        <div className="policy-intro">
          <div className="policy-mark">
            <ShieldCheck size={17} />
          </div>
          <div>
            <p className="reviews-eyebrow">AUTO-PILOT POLICY</p>
            <h2>策略总控分流</h2>
          </div>
        </div>
        <div className="policy-track">
          <div className="policy-step">
            <div className="policy-step-head">
              <span className="step-index">01</span>
              <strong>
                好评通道 <em>（4–5 星）</em>
              </strong>
            </div>
            <p className="policy-state state-green">
              <span className="status-dot" />
              {policy.stars.some((star) => star >= 4)
                ? "全自动托管已开启"
                : "自动托管已暂停"}
            </p>
            <div className="policy-chip-row">
              <Clock3 size={13} />
              <span>防机械秒回{policy.delay ? "已启用" : "已关闭"}</span>
              <Toggle
                checked={policy.delay}
                label="防机械秒回"
                onChange={() =>
                  setPolicy((current) => ({
                    ...current,
                    delay: !current.delay,
                  }))
                }
              />
            </div>
          </div>
          <ChevronRight className="policy-flow-arrow" size={20} />
          <div className="policy-step policy-step-risk">
            <div className="policy-step-head">
              <span className="step-index">02</span>
              <strong>
                差评通道 <em>（1–3 星 / 敏感词）</em>
              </strong>
            </div>
            <p className="policy-state state-red">
              <span className="status-dot" />
              {policy.stars.some((star) => star <= 3)
                ? "已开放部分低星托管 · 敏感词仍拦截"
                : "拦截审核制 · 需人工盖印"}
            </p>
            <div className="policy-chip-row">
              <Flame size={13} />
              <span>
                差评安抚与补偿策略库{policy.compensation ? "已激活" : "已暂停"}
              </span>
            </div>
          </div>
        </div>
      </section>

      <section className="feed-toolbar" aria-label="评论筛选">
        <div className="platform-tabs" role="tablist" aria-label="平台">
          {[{ label: "全部", tone: "" }, ...PLATFORM_META].map((item) => (
            <button
              key={item.label}
              role="tab"
              aria-selected={platform === item.label}
              className={`platform-tab ${platform === item.label ? "active" : ""}`}
              onClick={() => setPlatform(item.label)}
            >
              {item.tone ? (
                <PlatformMark tone={item.tone} platform={item.label} />
              ) : (
                <span className="all-platform-mark">
                  <Leaf size={13} />
                </span>
              )}
              {item.label}
              <small>
                {item.label === "全部"
                  ? reviews.length
                  : reviews.filter((review) => review.platform === item.label)
                      .length}
              </small>
            </button>
          ))}
        </div>
        <label className="search-box">
          <Search size={15} />
          <input
            aria-label="搜索昵称、评论或菜品"
            placeholder="搜索昵称、评论或菜品"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
          {query && (
            <button
              className="icon-ghost"
              onClick={() => setQuery("")}
              aria-label="清空搜索"
            >
              <X size={13} />
            </button>
          )}
        </label>
      </section>
      <div className="rating-filters" aria-label="评级筛选">
        {FILTERS.map((value) => (
          <button
            key={value}
            aria-pressed={filter === value}
            className={filter === value ? "selected" : ""}
            onClick={() => setFilter(value)}
          >
            {value === "需掌柜确认" && <i />}
            {value}
            {value === "需掌柜确认" && <small>{pending}</small>}
          </button>
        ))}
        <span className="result-count">共 {visible.length} 条评论</span>
      </div>

      <main className="review-feed">
        <div className="feed-column">
          <div className="feed-section-head">
            <div>
              <p className="reviews-eyebrow">REVIEW & DECISION</p>
              <h2>
                评论流席 <span>听见烟火，也照料情绪</span>
              </h2>
            </div>
            <span className="feed-summary">
              <Clock3 size={12} />
              按最新时间排序
            </span>
          </div>
          {error && (
            <p className="inline-error" role="alert">
              {error}
            </p>
          )}
          {[
            {
              label: "待掌柜决策",
              items: waiting,
              note: `${waiting.length} 条待决`,
            },
            { label: "已处理", items: handled, note: "灵犀代发与掌柜回复" },
          ].map(
            (group) =>
              group.items.length > 0 && (
                <section
                  key={group.label}
                  className="feed-group"
                  aria-label={group.label}
                >
                  <div className="group-label">
                    <span className="group-rule" />
                    <h3>{group.label}</h3>
                    <span className="group-count">{group.note}</span>
                  </div>
                  {group.items.map((review) => (
                    <article
                      key={review.id}
                      className={`review-card ${review.status === "pending" ? "pending-card" : "sent-card"} ${sending.includes(review.id) ? "sending" : ""}`}
                      aria-label={`${review.nickname}的${review.rating}星评论`}
                    >
                      <header className="review-card-head">
                        <div className="customer-meta">
                          <div
                            className={`customer-avatar avatar-${review.tone}`}
                          >
                            {review.avatar}
                          </div>
                          <div>
                            <div className="customer-name">
                              <strong>{review.nickname}</strong>
                              <PlatformMark
                                tone={review.tone}
                                platform={review.platform}
                              />
                              <small>
                                {review.platform} · {review.level}
                              </small>
                            </div>
                            <div className="customer-subline">
                              <span>{review.time}</span>
                              <span className="dot-separator" />
                              <span>{review.order}</span>
                            </div>
                          </div>
                        </div>
                        <span
                          className={
                            review.status === "pending"
                              ? "pending-badge"
                              : "sent-badge"
                          }
                        >
                          {review.status === "pending" ? (
                            <CircleAlert size={13} />
                          ) : (
                            <Check size={13} />
                          )}
                          {review.status === "pending"
                            ? "待掌柜确认"
                            : review.status === "auto"
                              ? "灵犀已代发"
                              : "掌柜已回复"}
                        </span>
                      </header>
                      {review.status === "pending" ? (
                        <>
                          <div className="review-body">
                            <div className="original-column">
                              <div className="rating-line">
                                <Stars rating={review.rating} />
                                <span className="order-tag">
                                  {review.order.split(" · ")[0]}
                                </span>
                              </div>
                              <p className="review-content">
                                “{review.content}”
                              </p>
                              {review.image && (
                                <button
                                  className="review-photo-wrap"
                                  onClick={() => setPhoto(review.image!)}
                                  aria-label="查看顾客配图"
                                >
                                  <img
                                    src={review.image}
                                    alt="顾客配图示例：庭院用餐环境"
                                  />
                                  <span className="photo-expand">
                                    <ImageIcon size={12} />
                                    顾客配图 · 1
                                  </span>
                                </button>
                              )}
                            </div>
                            <div className="ai-column bg-[#EDF4F2] text-[#234D45]">
                              <div className="ai-column-head">
                                <span>
                                  <Bot size={15} />
                                  灵犀深度析评
                                </span>
                                <span className="ai-time">已完成推演</span>
                              </div>
                              <div className="ai-tags">
                                {review.tags.map((tag) => (
                                  <span
                                    key={tag}
                                    className={
                                      tag.includes("风险") &&
                                      review.rating === 1
                                        ? "risk"
                                        : ""
                                    }
                                  >
                                    {tag}
                                  </span>
                                ))}
                              </div>
                              <label
                                htmlFor={`draft-${review.id}`}
                                className="draft-label"
                              >
                                <PenLine size={13} />
                                拟答草稿纸<span>点击文字即可修改</span>
                              </label>
                              <textarea
                                id={`draft-${review.id}`}
                                className="draft-textarea"
                                value={review.draft}
                                rows={5}
                                maxLength={2000}
                                disabled={sending.includes(review.id)}
                                onChange={(event) =>
                                  update(review.id, {
                                    draft: event.target.value,
                                  })
                                }
                              />
                              <div className="strategy-row">
                                <span className="strategy-label">拟答策略</span>
                                {review.strategies.map((strategy, index) => (
                                  <button
                                    key={strategy}
                                    className={`strategy-pill ${review.strategy === index ? "active-strategy" : ""}`}
                                    aria-pressed={review.strategy === index}
                                    disabled={sending.includes(review.id)}
                                    onClick={() =>
                                      changeStrategy(review, index)
                                    }
                                  >
                                    {strategy}
                                  </button>
                                ))}
                              </div>
                            </div>
                          </div>
                          <footer className="decision-bar">
                            <div className="decision-note">
                              <LockKeyhole size={13} />
                              {review.following
                                ? "已加入内部跟进清单 · 等待值班店长核实"
                                : "草稿待核实 · 盖印后模拟同步至平台"}
                            </div>
                            <div className="decision-actions">
                              <button
                                className="secondary-action"
                                disabled={sending.includes(review.id)}
                                onClick={() =>
                                  setEditor({
                                    id: review.id,
                                    text: review.draft,
                                  })
                                }
                              >
                                <Edit3 size={14} />
                                转入人工重写
                              </button>
                              <button
                                className="secondary-action"
                                onClick={() => {
                                  update(review.id, {
                                    following: !review.following,
                                  });
                                  notify(
                                    review.following
                                      ? "已取消内部跟进标记"
                                      : "已标记内部跟进，待值班店长线下核实",
                                  );
                                }}
                                aria-pressed={review.following}
                              >
                                <PhoneCall size={14} />
                                {review.following
                                  ? "已标记 · 取消跟进"
                                  : "联系线下巡店核实"}
                              </button>
                              <button
                                className="stamp-button bg-[#C43D2A]"
                                disabled={
                                  !review.draft.trim() ||
                                  sending.includes(review.id)
                                }
                                onClick={() => send(review)}
                              >
                                <span className="stamp-ring">印</span>
                                {sending.includes(review.id)
                                  ? "正在落印…"
                                  : "确认发出"}
                                <ArrowUpRight size={15} />
                              </button>
                            </div>
                          </footer>
                        </>
                      ) : (
                        <>
                          <div className="sent-original">
                            <div className="rating-line">
                              <Stars rating={review.rating} />
                              <span className="order-tag">
                                已同步至{review.platform}
                              </span>
                            </div>
                            <p className="review-content">“{review.content}”</p>
                            <button
                              className="reply-disclosure"
                              aria-expanded={review.expanded}
                              aria-controls={`reply-${review.id}`}
                              onClick={() =>
                                update(review.id, {
                                  expanded: !review.expanded,
                                })
                              }
                            >
                              <span>
                                <Sparkles size={14} />
                                {review.status === "auto"
                                  ? "灵犀已发出温润回应"
                                  : `已回复并同步至${review.platform}`}
                                <small> · 查看回复全文</small>
                              </span>
                              <ChevronDown
                                size={15}
                                className={review.expanded ? "rotate-180" : ""}
                              />
                            </button>
                            {review.expanded && (
                              <div
                                id={`reply-${review.id}`}
                                className="sent-reply"
                              >
                                <p>{review.draft}</p>
                              </div>
                            )}
                          </div>
                          <footer className="sent-actions">
                            <span>
                              <Check size={13} />
                              同步完成 · 演示状态
                            </span>
                            <div>
                              <button
                                className="text-action"
                                onClick={() =>
                                  setEditor({
                                    id: review.id,
                                    text: review.draft,
                                  })
                                }
                              >
                                <Edit3 size={13} />
                                重新修改
                              </button>
                              <button
                                className="text-action danger-text"
                                onClick={() => {
                                  update(review.id, {
                                    status: "pending",
                                    expanded: false,
                                  });
                                  notify("已模拟撤回，评论返回待决队列");
                                }}
                              >
                                撤回
                              </button>
                            </div>
                          </footer>
                        </>
                      )}
                    </article>
                  ))}
                </section>
              ),
          )}
          {visible.length === 0 && (
            <div className="empty-feed">
              <Inbox size={28} />
              <h3>这一席暂时没有评论</h3>
              <p>试试其他平台、关键词或筛选条件。</p>
              <button
                className="quiet-button"
                onClick={() => {
                  setPlatform("全部");
                  setFilter("全部星级");
                  setQuery("");
                }}
              >
                清空筛选
              </button>
            </div>
          )}
          <footer className="workbench-foot">
            <Leaf size={13} />
            <span>一言一答，皆有回响。</span>
            <small>一方志 · 本地演示工作台</small>
          </footer>
        </div>
      </main>

      {drawer && (
        <div
          className="drawer-scrim"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setDrawer(false);
          }}
        >
          <aside
            ref={drawerRef}
            className="strategy-drawer"
            role="dialog"
            aria-modal="true"
            aria-labelledby="policy-title"
          >
            <div className="drawer-head">
              <div>
                <p className="reviews-eyebrow">GLOBAL POLICY · 托管有度</p>
                <h2 id="policy-title">全局托管策略</h2>
                <p>把温柔交给灵犀，把分寸留给掌柜。</p>
              </div>
              <button
                aria-label="关闭策略设置"
                className="drawer-close"
                onClick={() => setDrawer(false)}
              >
                <X size={18} />
              </button>
            </div>
            <div className="drawer-content">
              <section className="drawer-section">
                <div className="drawer-section-head">
                  <h3>几星级自动发出</h3>
                  <b>已选 {draftPolicy.stars.length} 个星级</b>
                </div>
                <div className="star-toggle-row">
                  {[1, 2, 3, 4, 5].map((star) => (
                    <button
                      key={star}
                      className={`star-toggle ${draftPolicy.stars.includes(star) ? "selected" : ""}`}
                      aria-pressed={draftPolicy.stars.includes(star)}
                      onClick={() => toggleStar(star)}
                    >
                      <Star size={14} fill="currentColor" />
                      {star} 星
                    </button>
                  ))}
                </div>
                <p className="drawer-help">
                  建议将 4–5
                  星交给灵犀，低星评价由掌柜审核。命中关键词的评论始终拦截。
                </p>
              </section>
              <section className="drawer-section">
                <div className="drawer-section-head">
                  <h3>关键词强制拦截</h3>
                  <b className="danger-label">禁止自动回复</b>
                </div>
                <div className="keyword-row">
                  {Array.from(
                    new Set([
                      "卫生",
                      "腹泻",
                      "发票",
                      "异物",
                      "退款",
                      ...draftPolicy.keywords,
                    ]),
                  ).map((word) => (
                    <button
                      key={word}
                      className={`keyword-chip ${draftPolicy.keywords.includes(word) ? "blocked" : ""}`}
                      aria-pressed={draftPolicy.keywords.includes(word)}
                      onClick={() => toggleKeyword(word)}
                    >
                      {word}
                      {draftPolicy.keywords.includes(word) && (
                        <Check size={12} />
                      )}
                    </button>
                  ))}
                </div>
                <form
                  className="keyword-input"
                  onSubmit={(event) => {
                    event.preventDefault();
                    addKeyword();
                  }}
                >
                  <input
                    aria-label="添加拦截关键词"
                    placeholder="添加自定义关键词"
                    maxLength={20}
                    value={keyword}
                    onChange={(event) => setKeyword(event.target.value)}
                  />
                  <button disabled={!keyword.trim()} type="submit">
                    添加
                  </button>
                </form>
                <p className="drawer-help">
                  命中任一选中关键词，将拦截自动回复并升级人工审核。
                </p>
              </section>
              {(
                [
                  {
                    key: "sms",
                    title: "短信报警",
                    note: "敏感词命中时通知值班掌柜",
                  },
                  {
                    key: "compensation",
                    title: "差评安抚与补偿策略库",
                    note: "为低星评价匹配致歉与补偿建议",
                  },
                  {
                    key: "delay",
                    title: "防机械秒回",
                    note: "随机延迟 3–12 分钟，更接近真人回应",
                  },
                ] as const
              ).map((item) => (
                <section
                  key={item.key}
                  className="drawer-section switch-section"
                >
                  <div>
                    <span>{item.title}</span>
                    <small>{item.note}</small>
                  </div>
                  <Toggle
                    label={item.title}
                    checked={draftPolicy[item.key]}
                    onChange={() =>
                      setDraftPolicy((current) => ({
                        ...current,
                        [item.key]: !current[item.key],
                      }))
                    }
                  />
                </section>
              ))}
              <p className="drawer-help">
                演示模式：设置仅在当前页面生效，不会发送真实短信。
              </p>
            </div>
            <footer className="drawer-footer">
              <button
                className="drawer-cancel"
                onClick={() => setDrawer(false)}
              >
                取消
              </button>
              <button
                className="drawer-save"
                onClick={() => {
                  setPolicy(draftPolicy);
                  setDrawer(false);
                  notify("全局托管策略已保存（当前演示会话）");
                }}
              >
                <Check size={15} />
                保存策略
              </button>
            </footer>
          </aside>
        </div>
      )}
      {editor && selectedReview && (
        <section className="editor-dock" aria-label="精细编辑区">
          <div className="editor-dock-head">
            <div>
              <p className="reviews-eyebrow">PRECISION EDITOR</p>
              <strong>精细编辑区 · {selectedReview.nickname}</strong>
            </div>
            <button aria-label="关闭精细编辑区" onClick={() => setEditor(null)}>
              <X size={16} />
            </button>
          </div>
          <textarea
            ref={editorRef}
            aria-label="人工重写回复"
            value={editor.text}
            onChange={(event) =>
              setEditor({ ...editor, text: event.target.value })
            }
            rows={5}
            maxLength={2000}
          />
          <div className="editor-dock-foot">
            <span>{editor.text.length} / 2000 字</span>
            <button
              disabled={!editor.text.trim()}
              onClick={() => send(selectedReview, editor.text)}
            >
              保存并发出
              <ArrowUpRight size={14} />
            </button>
          </div>
        </section>
      )}
      {photo && (
        <div
          className="photo-modal drawer-scrim"
          onClick={(event) => {
            if (event.target === event.currentTarget) setPhoto(null);
          }}
        >
          <div
            ref={photoRef}
            role="dialog"
            aria-modal="true"
            aria-label="顾客配图预览"
          >
            <button
              className="drawer-close"
              aria-label="关闭配图"
              onClick={() => setPhoto(null)}
            >
              <X size={18} />
            </button>
            <img src={photo} alt="顾客配图示例：庭院用餐环境" />
            <p>顾客配图 · 演示素材</p>
          </div>
        </div>
      )}
      {toast && (
        <div className="toast" role="status">
          <Check size={15} />
          {toast}
        </div>
      )}
    </div>
  );
}

const workbenchCSS = `
.reviews-page {
  --ri:#1c3532;
  --rl:#e3ece9;
  --rp:#f8f6f1;
  --rr:#c43d2a;
  --rs:#fbeceb;
  --ra:#ce8f2a;
  --rg:#4d7d6f;
  --gs:#edf4f2}

.reviews-page {
  min-height:100%;
  padding:28px clamp(18px,3vw,48px) 70px;
  background:var(--rp);
  color:var(--ri);
  font-family:'Yifangzhi Sans','PingFang SC','Microsoft YaHei',sans-serif}

.reviews-page * {
  box-sizing:border-box}
.reviews-page button,.reviews-page input,.reviews-page textarea {
  font:inherit}
.reviews-page button {
  cursor:pointer;
  color:inherit}
.reviews-page button:disabled {
  opacity:.5;
  cursor:not-allowed}
.reviews-page button:focus-visible,.reviews-page input:focus-visible,.reviews-page textarea:focus-visible {
  outline:2px solid #234d45;
  outline-offset:3px}
.reviews-page h1,.reviews-page h2,.reviews-page h3,.reviews-page .review-content,.reviews-page .draft-textarea,.reviews-page .sent-reply p {
  font-family:'Yifangzhi Serif','Noto Serif SC','Songti SC',serif}
.reviews-page h1,.reviews-page h2,.reviews-page h3,.reviews-page p {
  margin-top:0}

.reviews-page .reviews-heading {
  display:flex;
  justify-content:space-between;
  align-items:center;
  gap:20px;
  margin-bottom:24px}
.reviews-page .reviews-breadcrumb {
  display:flex;
  gap:7px;
  align-items:center;
  color:#7f9690;
  font-size:11px}
.reviews-page .reviews-breadcrumb strong {
  color:var(--ri)}
.reviews-page .title-lockup {
  display:flex;
  align-items:center;
  gap:14px;
  margin-top:18px}
.reviews-page .title-seal {
  display:grid;
  place-items:center;
  position:relative;
  width:46px;
  height:46px;
  border:1px solid var(--rr);
  color:var(--rr);
  font:23px 'Yifangzhi Serif'}
.reviews-page .title-seal:after {
  content:'';
  position:absolute;
  inset:4px;
  border:1px solid #c43d2a40}
.reviews-page .reviews-eyebrow {
  margin:0;
  color:#86a29a;
  font-size:10px;
  letter-spacing:.16em;
  font-weight:600}
.reviews-page .title-lockup h1 {
  margin:5px 0 4px;
  font-size:30px;
  font-weight:600}
.reviews-page .reviews-subtitle {
  margin:0;
  color:#6f8580;
  font-size:12px}
.reviews-page .heading-actions {
  display:flex;
  align-items:center;
  gap:10px}
.reviews-page .live-status {
  display:flex;
  gap:10px;
  align-items:center;
  padding:10px 13px;
  border:1px solid #d6e5e1;
  background:#ffffffa6;
  border-radius:4px}
.reviews-page .live-status b {
  display:block;
  font-size:12px}
.reviews-page .live-status small {
  display:block;
  margin-top:3px;
  color:#8b9f9b;
  font-size:10px}
.reviews-page .live-dot {
  position:relative;
  width:7px;
  height:7px;
  border-radius:50%;
  background:#5e9d85;
  box-shadow:0 0 0 4px #5e9d8522}
.reviews-page .live-dot:after {
  content:'';
  position:absolute;
  inset:-4px;
  border:1px solid #5e9d8555;
  border-radius:50%;
  animation:breath 2s infinite}
.reviews-page .quiet-button {
  display:inline-flex;
  align-items:center;
  justify-content:center;
  gap:7px;
  height:38px;
  padding:0 13px;
  border:1px solid var(--rl);
  border-radius:4px;
  background:#fff;
  font-size:12px}
.reviews-page .quiet-button:hover {
  border-color:#a9c4bc;
  background:#fbfdfc}

.reviews-page .overview-grid {
  display:grid;
  grid-template-columns:1.25fr 1fr 1fr;
  gap:14px}
.reviews-page .overview-card {
  min-height:145px;
  padding:18px 20px;
  border:1px solid var(--rl);
  border-radius:5px;
  background:#fffffff0;
  box-shadow:0 2px 4px #1c353205;
  text-align:left}
.reviews-page .overview-card-top,.reviews-page .overview-foot,.reviews-page .score-line,.reviews-page .score-bottom {
  display:flex;
  align-items:center}
.reviews-page .overview-card-top {
  justify-content:space-between}
.reviews-page .overline {
  color:#75908a;
  font-size:11px;
  font-weight:600}
.reviews-page .metric-note {
  display:flex;
  gap:2px;
  align-items:center;
  color:var(--rg);
  font:10px ui-monospace}
.reviews-page .metric-icon {
  display:grid;
  place-items:center;
  width:25px;
  height:25px;
  border-radius:50%;
  background:var(--gs);
  color:var(--rg)}
.reviews-page .score-line {
  gap:12px;
  align-items:baseline;
  margin-top:16px}
.reviews-page .score-line strong,.reviews-page .big-metric {
  font:600 38px 'Yifangzhi Serif';
  letter-spacing:-.04em}
.reviews-page .stars,.reviews-page .rating-stars {
  display:flex;
  align-items:center;
  gap:2px;
  color:var(--ra)}
.reviews-page .stars span {
  margin-left:4px;
  color:#9aaba6;
  font-size:11px}
.reviews-page .score-bottom {
  flex-wrap:wrap;
  gap:6px 10px;
  margin-top:16px;
  color:#91a19d;
  font-size:10px}
.reviews-page .sentiment {
  display:inline-flex;
  align-items:center;
  gap:5px;
  color:var(--rg)}
.reviews-page .sentiment-dot {
  width:5px;
  height:5px;
  border-radius:50%;
  background:#6aa189}
.reviews-page .big-metric {
  margin-top:17px}
.reviews-page .big-metric small {
  font:11px 'Yifangzhi Sans';
  color:#79908a;
  letter-spacing:0}
.reviews-page .overview-foot {
  justify-content:space-between;
  gap:8px;
  flex-wrap:wrap;
  margin-top:17px;
  color:#91a19d;
  font-size:10px}
.reviews-page .overview-foot b {
  display:flex;
  align-items:center;
  gap:4px;
  font-weight:500}
.reviews-page .mini-bar {
  height:3px;
  margin-top:15px;
  background:#eff4f2}
.reviews-page .mini-bar span {
  display:block;
  height:100%;
  background:#6a998a}
.reviews-page .overview-alert {
  border-color:#e9c8c2;
  background:#fffdfc}
.reviews-page .clickable {
  cursor:pointer}
.reviews-page .alert-icon {
  background:var(--rs);
  color:var(--rr)}
.reviews-page .alert-number {
  color:var(--rr)}
.reviews-page .alert-rule {
  display:flex;
  gap:4px;
  margin-top:15px}
.reviews-page .alert-rule span {
  height:3px;
  background:#e9c8c2}
.reviews-page .alert-rule span:nth-child(1) {
  width:44%}
.reviews-page .alert-rule span:nth-child(2) {
  width:18%;
  background:#d97968}
.reviews-page .alert-rule span:nth-child(3) {
  width:9%;
  background:var(--rr)}

.reviews-page .policy-ribbon {
  display:grid;
  grid-template-columns:180px 1fr;
  gap:22px;
  margin-top:18px;
  padding:18px 20px;
  border:1px solid #d5e5df;
  border-radius:5px;
  background:var(--gs)}
.reviews-page .policy-intro {
  display:flex;
  gap:10px}
.reviews-page .policy-mark {
  display:grid;
  place-items:center;
  width:28px;
  height:28px;
  border:1px solid #bbd4cb;
  border-radius:50%;
  color:var(--rg);
  background:#f7fbfa}
.reviews-page .policy-intro h2 {
  margin:5px 0 0;
  font-size:17px}
.reviews-page .policy-track {
  display:grid;
  grid-template-columns:1fr 20px 1.2fr;
  align-items:center;
  gap:14px}
.reviews-page .policy-step-risk {
  padding-left:15px;
  border-left:1px solid #cfe1db}
.reviews-page .policy-step-head {
  display:flex;
  align-items:baseline;
  gap:8px;
  flex-wrap:wrap}
.reviews-page .step-index {
  color:#89a69e;
  font:10px ui-monospace}
.reviews-page .policy-step-head strong {
  font-size:12px}
.reviews-page .policy-step-head em {
  color:#78918b;
  font-style:normal;
  font-weight:400}
.reviews-page .policy-state {
  display:flex;
  align-items:center;
  gap:5px;
  margin:8px 0;
  color:#4f8172;
  font-size:11px}
.reviews-page .state-red {
  color:var(--rr)}
.reviews-page .status-dot {
  width:5px;
  height:5px;
  border-radius:50%;
  background:currentColor}
.reviews-page .policy-chip-row {
  display:flex;
  align-items:center;
  gap:6px;
  color:#55776c;
  font-size:10px;
  flex-wrap:wrap}
.reviews-page .policy-flow-arrow {
  color:#92b3a6}

.reviews-page .feed-toolbar {
  display:flex;
  justify-content:space-between;
  align-items:center;
  gap:12px;
  margin-top:23px;
  padding-bottom:12px;
  border-bottom:1px solid var(--rl)}
.reviews-page .platform-tabs {
  display:flex;
  gap:3px;
  min-width:0;
  overflow-x:auto}
.reviews-page .platform-tab {
  display:flex;
  align-items:center;
  gap:7px;
  flex-shrink:0;
  height:33px;
  padding:0 10px;
  border:1px solid transparent;
  border-radius:3px;
  background:transparent;
  color:#78908a;
  font-size:11px;
  white-space:nowrap}
.reviews-page .platform-tab.active {
  border-color:#d8e7e2;
  background:#fff;
  color:var(--ri)}
.reviews-page .platform-tab small {
  display:grid;
  place-items:center;
  min-width:17px;
  height:17px;
  padding:0 4px;
  border-radius:9px;
  background:#edf2f0;
  color:#7e9991;
  font-size:9px}
.reviews-page .platform-tab.active small {
  background:var(--ri);
  color:#fff}
.reviews-page .all-platform-mark,.reviews-page .platform-mark {
  display:grid;
  place-items:center}
.reviews-page .all-platform-mark {
  width:19px;
  height:19px;
  border-radius:50%;
  background:#e5efec;
  color:#4e8174}
.reviews-page .platform-mark {
  width:18px;
  height:18px;
  border-radius:3px;
  color:#fff;
  font-size:10px;
  font-weight:700}
.reviews-page .platform-dianping {
  background:#e8782b}
.reviews-page .platform-meituan {
  background:#ffb800;
  color:#654a00}
.reviews-page .platform-douyin {
  background:#1f2328}
.reviews-page .platform-xiaohongshu {
  background:#ea4a5a}
.reviews-page .search-box {
  display:flex;
  align-items:center;
  gap:7px;
  flex:0 1 230px;
  min-width:180px;
  height:33px;
  padding:0 9px;
  border:1px solid var(--rl);
  border-radius:3px;
  background:#fff;
  color:#90a19d}
.reviews-page .search-box input {
  width:100%;
  border:0;
  outline:0;
  background:transparent;
  color:var(--ri);
  font-size:11px}
.reviews-page .icon-ghost {
  display:grid;
  place-items:center;
  border:0;
  background:transparent;
  color:#91a39d}
.reviews-page .rating-filters {
  display:flex;
  align-items:center;
  gap:7px;
  flex-wrap:wrap;
  margin-top:13px}
.reviews-page .rating-filters button {
  display:flex;
  align-items:center;
  gap:5px;
  border:1px solid transparent;
  border-radius:3px;
  padding:5px 9px;
  background:transparent;
  color:#6d837c;
  font-size:11px}
.reviews-page .rating-filters button.selected {
  border-color:#cfddd7;
  background:#fff;
  color:#234d45}
.reviews-page .rating-filters i {
  width:5px;
  height:5px;
  border-radius:50%;
  background:var(--rr)}
.reviews-page .rating-filters small {
  color:var(--rr)}
.reviews-page .result-count {
  margin-left:auto;
  color:#789087;
  font-size:11px}

.reviews-page .review-feed {
  margin-top:24px}
.reviews-page .feed-section-head {
  display:flex;
  justify-content:space-between;
  align-items:end;
  margin-bottom:17px}
.reviews-page .feed-section-head h2 {
  margin:5px 0 0;
  font-size:21px}
.reviews-page .feed-section-head h2 span {
  font:11px 'Yifangzhi Sans';
  color:#81958e;
  margin-left:8px}
.reviews-page .feed-summary {
  display:flex;
  align-items:center;
  gap:5px;
  color:#849890;
  font-size:10px}
.reviews-page .feed-group+.feed-group {
  margin-top:26px}
.reviews-page .group-label {
  display:flex;
  align-items:center;
  gap:8px;
  margin-bottom:10px;
  color:#627c75}
.reviews-page .group-rule {
  width:18px;
  height:1px;
  background:#aac5bc}
.reviews-page .group-label h3 {
  margin:0;
  font-size:12px}
.reviews-page .group-count {
  color:#9aaba7;
  font-size:10px}
.reviews-page .review-card {
  border:1px solid var(--rl);
  border-radius:5px;
  background:#fff;
  overflow:hidden;
  box-shadow:0 2px 5px #1c353204;
  animation:review-enter .28s ease both}
.reviews-page .pending-card {
  border-color:#e6c6c0}
.reviews-page .review-card+.review-card {
  margin-top:12px}
.reviews-page .review-card.sending {
  animation:none;
  opacity:0;
  transform:translateY(6px);
  pointer-events:none}
.reviews-page .review-card-head {
  display:flex;
  justify-content:space-between;
  align-items:center;
  gap:12px;
  flex-wrap:wrap;
  padding:16px 19px;
  border-bottom:1px solid #eef3f1}
.reviews-page .customer-meta {
  display:flex;
  align-items:center;
  gap:10px}
.reviews-page .customer-avatar {
  display:grid;
  place-items:center;
  width:32px;
  height:32px;
  border-radius:3px;
  font:15px 'Yifangzhi Serif'}
.reviews-page .avatar-dianping {
  background:#fff0e6;
  color:#df6e24}
.reviews-page .avatar-meituan {
  background:#fff4d9;
  color:#a06e00}
.reviews-page .avatar-douyin {
  background:#eef0f1;
  color:#20272a}
.reviews-page .avatar-xiaohongshu {
  background:#ffedf0;
  color:#d64756}
.reviews-page .customer-name {
  display:flex;
  align-items:center;
  gap:6px;
  flex-wrap:wrap;
  font-size:12px}
.reviews-page .customer-name small {
  color:#9aaba7;
  font-size:10px}
.reviews-page .customer-subline {
  display:flex;
  align-items:center;
  gap:6px;
  flex-wrap:wrap;
  margin-top:5px;
  color:#9aaba7;
  font-size:10px}
.reviews-page .dot-separator {
  width:3px;
  height:3px;
  border-radius:50%;
  background:#bfcbc7}
.reviews-page .pending-badge,.reviews-page .sent-badge {
  display:inline-flex;
  align-items:center;
  gap:5px;
  padding:5px 8px;
  border-radius:2px;
  font-size:10px}
.reviews-page .pending-badge {
  color:var(--rr);
  background:var(--rs)}
.reviews-page .sent-badge {
  color:#4d7f70;
  background:var(--gs)}

.reviews-page .review-body {
  display:grid;
  grid-template-columns:minmax(0,.8fr) minmax(0,1.2fr)}
.reviews-page .original-column,.reviews-page .ai-column {
  min-width:0;
  padding:19px}
.reviews-page .original-column {
  border-right:1px solid #eef3f1}
.reviews-page .rating-line {
  display:flex;
  justify-content:space-between;
  align-items:center;
  gap:10px;
  flex-wrap:wrap}
.reviews-page .rating-stars em {
  margin-left:5px;
  font-style:normal;
  color:var(--rr);
  font-size:10px}
.reviews-page .rating-stars em.positive-rating {
  color:#947022}
.reviews-page .order-tag {
  padding:4px 7px;
  background:#f4f6f4;
  color:#8b9d98;
  font-size:10px;
  white-space:nowrap}
.reviews-page .review-content {
  margin:14px 0;
  font-size:15px;
  line-height:1.9;
  color:#294540}
.reviews-page .review-photo-wrap {
  position:relative;
  display:block;
  width:136px;
  height:88px;
  padding:0;
  border:0;
  border-radius:3px;
  overflow:hidden;
  background:#eef2f0}
.reviews-page .review-photo-wrap img {
  width:100%;
  height:100%;
  object-fit:cover;
  filter:saturate(.76)}
.reviews-page .photo-expand {
  position:absolute;
  right:4px;
  bottom:4px;
  left:4px;
  display:flex;
  align-items:center;
  gap:4px;
  padding:4px 6px;
  border:0;
  border-radius:2px;
  background:#1c3532b8;
  color:#fff;
  font-size:9px}
.reviews-page .ai-column {
  background:#f4f8f6;
  color:#234d45}
.reviews-page .ai-column-head {
  display:flex;
  justify-content:space-between;
  align-items:center;
  color:#397062;
  font-size:11px}
.reviews-page .ai-column-head span:first-child {
  display:flex;
  align-items:center;
  gap:6px}
.reviews-page .ai-time {
  color:#9aada7;
  font-size:9px}
.reviews-page .ai-tags {
  display:flex;
  flex-wrap:wrap;
  gap:5px;
  margin-top:12px}
.reviews-page .ai-tags span {
  padding:4px 6px;
  border:1px solid #d7e7e1;
  border-radius:2px;
  background:#fff;
  color:#598076;
  font-size:10px}
.reviews-page .ai-tags span.risk {
  border-color:#ebc5bf;
  color:var(--rr);
  background:#fff7f6}
.reviews-page .draft-label {
  display:flex;
  align-items:center;
  gap:5px;
  margin:14px 0 7px;
  color:#5a7c72;
  font-size:11px;
  font-weight:600}
.reviews-page .draft-label span {
  margin-left:auto;
  color:#a3b2ae;
  font-weight:400;
  font-size:10px}
.reviews-page .draft-textarea {
  display:block;
  width:100%;
  min-height:155px;
  padding:10px;
  border:1px solid #cfe2da;
  border-radius:3px;
  resize:vertical;
  outline:0;
  background:#fff;
  color:#31564d;
  font-size:13px;
  line-height:1.9}
.reviews-page .strategy-row {
  display:flex;
  align-items:center;
  gap:5px;
  flex-wrap:wrap;
  margin-top:10px}
.reviews-page .strategy-label {
  color:#8ba19b;
  font-size:10px}
.reviews-page .strategy-pill {
  padding:5px 8px;
  border:1px solid #cfe1da;
  border-radius:12px;
  background:#fff;
  color:#568074;
  font-size:10px}
.reviews-page .active-strategy {
  background:#e1ede7;
  border-color:#9ebbaf;
  color:#234d45}

.reviews-page .decision-bar {
  display:flex;
  justify-content:space-between;
  align-items:center;
  gap:12px;
  flex-wrap:wrap;
  padding:12px 19px;
  border-top:1px solid #eef3f1;
  background:#fcfdfc}
.reviews-page .decision-note {
  display:flex;
  align-items:center;
  gap:5px;
  color:#99aaa6;
  font-size:10px}
.reviews-page .decision-actions {
  display:flex;
  align-items:center;
  gap:7px;
  flex-wrap:wrap;
  margin-left:auto}
.reviews-page .secondary-action {
  display:inline-flex;
  align-items:center;
  gap:5px;
  height:34px;
  padding:0 9px;
  border:1px solid var(--rl);
  border-radius:3px;
  background:#fff;
  color:#66807a;
  font-size:10px}
.reviews-page .stamp-button {
  display:inline-flex;
  align-items:center;
  gap:6px;
  height:36px;
  padding:0 12px;
  border:1px solid var(--rr);
  border-radius:3px;
  background:var(--rr);
  color:#fff;
  font-size:11px;
  font-weight:600;
  box-shadow:0 2px 0 #8f281b;
  transition:transform .12s,box-shadow .12s}
.reviews-page .stamp-button:active {
  transform:translateY(2px);
  box-shadow:none}
.reviews-page .stamp-ring {
  display:grid;
  place-items:center;
  width:19px;
  height:19px;
  border:1px solid #ffffffb3;
  border-radius:50%;
  font:10px 'Yifangzhi Serif'}
.reviews-page .sent-original {
  padding:18px 19px}
.reviews-page .reply-disclosure {
  display:flex;
  align-items:center;
  justify-content:space-between;
  width:100%;
  gap:12px;
  padding:10px 12px;
  border:1px solid #e0ebe5;
  border-radius:3px;
  background:#f4f8f6;
  color:#496f60;
  text-align:left;
  font-size:11px}
.reviews-page .reply-disclosure span {
  display:flex;
  align-items:center;
  flex-wrap:wrap;
  gap:5px}
.reviews-page .reply-disclosure small {
  color:#8d9f97;
  font-size:10px}
.reviews-page .rotate-180 {
  transform:rotate(180deg)}
.reviews-page .sent-reply {
  border:1px solid #e0ebe5;
  border-top:0;
  padding:12px 14px;
  background:#f4f8f6}
.reviews-page .sent-reply p {
  margin:0;
  font-size:13px;
  line-height:1.8;
  color:#4d7066}
.reviews-page .sent-actions {
  display:flex;
  justify-content:space-between;
  align-items:center;
  gap:10px;
  flex-wrap:wrap;
  padding:11px 19px;
  border-top:1px solid #eef3f1;
  color:#8ea19b;
  font-size:10px}
.reviews-page .text-action {
  display:inline-flex;
  align-items:center;
  gap:4px;
  min-height:28px;
  border:0;
  background:transparent;
  color:#6d8980;
  font-size:10px}
.reviews-page .danger-text {
  color:#b75b4e}
.reviews-page .empty-feed {
  display:grid;
  justify-items:center;
  padding:78px 20px;
  border:1px dashed #cbdcd6;
  color:#829892}
.reviews-page .empty-feed h3 {
  margin:14px 0 4px;
  font-size:16px}
.reviews-page .empty-feed p {
  margin:0;
  font-size:11px}
.reviews-page .workbench-foot {
  display:flex;
  align-items:center;
  gap:7px;
  margin-top:25px;
  color:#80968c;
  font-size:11px}
.reviews-page .workbench-foot small {
  margin-left:auto;
  font-size:10px}

.reviews-page .drawer-scrim {
  position:fixed;
  inset:0;
  z-index:90;
  display:block;
  background:#1c353238;
  backdrop-filter:blur(2px);
  animation:overlay-in .2s}
.reviews-page .strategy-drawer {
  position:absolute;
  top:0;
  right:0;
  display:flex;
  flex-direction:column;
  width:min(440px,100vw);
  height:100%;
  padding:26px 25px 20px;
  border-left:1px solid #dbe7e2;
  background:#fffefa;
  box-shadow:-12px 0 30px #1c35321f;
  animation:drawer-in .25s}
.reviews-page .drawer-head {
  display:flex;
  justify-content:space-between;
  gap:20px;
  padding-bottom:20px;
  border-bottom:1px solid var(--rl)}
.reviews-page .drawer-head h2 {
  margin:6px 0 5px;
  font-size:22px}
.reviews-page .drawer-head p:last-child {
  margin:0;
  color:#859b95;
  font-size:11px}
.reviews-page .drawer-close {
  display:grid;
  place-items:center;
  width:30px;
  height:30px;
  border:1px solid var(--rl);
  background:#fff;
  color:#6f8881}
.reviews-page .drawer-content {
  flex:1;
  min-height:0;
  overflow-y:auto}
.reviews-page .drawer-section {
  padding:20px 0;
  border-bottom:1px solid #edf2f0}
.reviews-page .drawer-section-head {
  display:flex;
  justify-content:space-between;
  align-items:center;
  gap:8px}
.reviews-page .drawer-section-head h3 {
  margin:0;
  font-size:13px}
.reviews-page .drawer-section-head b {
  font-size:10px;
  color:#6e9385;
  font-weight:500}
.reviews-page .danger-label {
  color:var(--rr)!important}
.reviews-page .star-toggle-row,.reviews-page .keyword-row {
  display:flex;
  gap:6px;
  flex-wrap:wrap;
  margin-top:13px}
.reviews-page .star-toggle,.reviews-page .keyword-chip {
  display:inline-flex;
  align-items:center;
  justify-content:center;
  gap:4px;
  padding:7px 8px;
  border:1px solid #dce7e3;
  border-radius:3px;
  background:#fff;
  color:#91a29e;
  font-size:10px}
.reviews-page .star-toggle {
  flex:1}
.reviews-page .star-toggle.selected {
  border-color:#efcc87;
  background:#fbf5ea;
  color:var(--ra)}
.reviews-page .keyword-chip.blocked {
  border-color:#efc6be;
  background:var(--rs);
  color:var(--rr)}
.reviews-page .keyword-input {
  display:flex;
  margin-top:12px;
  border:1px solid var(--rl);
  border-radius:3px;
  background:#fff}
.reviews-page .keyword-input input {
  flex:1;
  min-width:0;
  padding:9px;
  border:0;
  outline:0;
  background:transparent;
  color:#234d45;
  font-size:12px}
.reviews-page .keyword-input button {
  border:0;
  border-left:1px solid var(--rl);
  background:#edf4f2;
  padding:0 13px;
  color:#234d45;
  font-size:11px}
.reviews-page .drawer-help {
  display:block;
  margin:10px 0 0;
  color:#9aa9a5;
  font-size:10px;
  line-height:1.7}
.reviews-page .switch-section {
  display:flex;
  justify-content:space-between;
  align-items:center}
.reviews-page .switch-section>div {
  display:grid;
  gap:4px}
.reviews-page .switch-section span {
  font-size:12px;
  font-weight:600}
.reviews-page .switch-section small {
  color:#94a6a1;
  font-size:10px}
.reviews-page .switch-on {
  width:36px;
  height:20px;
  padding:2px;
  border:0;
  border-radius:20px;
  background:#659581}
.reviews-page .switch-on span {
  display:block;
  width:16px;
  height:16px;
  margin-left:15px;
  border-radius:50%;
  background:#fff;
  transition:margin .18s}
.reviews-page .switch-off {
  background:#b5c6bd}
.reviews-page .switch-off span {
  margin-left:0}
.reviews-page .drawer-footer {
  display:flex;
  justify-content:space-between;
  gap:8px;
  margin-top:auto;
  padding-top:20px}
.reviews-page .drawer-cancel,.reviews-page .drawer-save {
  height:36px;
  padding:0 14px;
  border-radius:3px;
  font-size:11px}
.reviews-page .drawer-cancel {
  border:1px solid var(--rl);
  background:#fff;
  color:#6e8780}
.reviews-page .drawer-save {
  display:inline-flex;
  align-items:center;
  gap:6px;
  border:1px solid var(--rr);
  background:var(--rr);
  color:#fff}

.reviews-page .editor-dock {
  position:fixed;
  right:26px;
  bottom:24px;
  z-index:80;
  width:min(510px,calc(100vw - 32px));
  padding:15px;
  border:1px solid #cadfd7;
  border-radius:5px;
  background:#fffefa;
  box-shadow:0 10px 30px #1c353224;
  animation:review-enter .2s}
.reviews-page .editor-dock-head,.reviews-page .editor-dock-foot {
  display:flex;
  justify-content:space-between;
  align-items:center;
  gap:12px}
.reviews-page .editor-dock-head {
  padding-bottom:10px;
  border-bottom:1px solid var(--rl)}
.reviews-page .editor-dock-head strong {
  display:block;
  margin-top:4px;
  font-size:12px}
.reviews-page .editor-dock-head button {
  display:grid;
  place-items:center;
  width:25px;
  height:25px;
  border:1px solid var(--rl);
  background:#fff;
  color:#6e8780}
.reviews-page .editor-dock textarea {
  display:block;
  width:100%;
  margin:11px 0;
  padding:10px;
  border:1px solid var(--rl);
  outline:0;
  resize:vertical;
  color:#31564d;
  font-size:13px;
  line-height:1.8}
.reviews-page .editor-dock-foot {
  color:#91a39d;
  font-size:9px}
.reviews-page .editor-dock-foot button {
  display:inline-flex;
  align-items:center;
  gap:5px;
  border:0;
  background:var(--rr);
  color:#fff;
  padding:8px 10px;
  font-size:10px}
.reviews-page .toast {
  position:fixed;
  left:50%;
  bottom:25px;
  z-index:120;
  display:flex;
  align-items:center;
  gap:7px;
  max-width:calc(100vw - 28px);
  padding:10px 14px;
  border:1px solid #bed6cb;
  border-radius:4px;
  background:#f8fffb;
  color:#376c5b;
  box-shadow:0 8px 22px #1c353229;
  font-size:11px;
  transform:translateX(-50%)}
.reviews-page .photo-modal {
  display:grid;
  place-items:center;
  padding:24px}
.reviews-page .photo-modal>div {
  position:relative;
  width:min(800px,100%);
  padding:14px;
  border-radius:5px;
  background:#fff}
.reviews-page .photo-modal img {
  width:100%;
  max-height:70vh;
  object-fit:contain}
.reviews-page .photo-modal .drawer-close {
  position:absolute;
  top:20px;
  right:20px}
.reviews-page .photo-modal p {
  margin:10px 0 0;
  color:#60776b;
  font-size:12px}
.reviews-page .inline-error {
  padding:10px;
  color:var(--rr);
  background:#fff0ed;
  font-size:12px}

@keyframes breath {
  0%,100% {
  transform:scale(.8);
  opacity:.45}
50% {
  transform:scale(1.25);
  opacity:0}
}
@keyframes review-enter {
  from {
  opacity:0;
  transform:translateY(6px)}
to {
  opacity:1;
  transform:none}
}
@keyframes overlay-in {
  from {
  opacity:0}
to {
  opacity:1}
}
@keyframes drawer-in {
  from {
  transform:translateX(100%)}
to {
  transform:none}
}

@media(max-width:900px) {
  .reviews-page .reviews-heading {
  align-items:flex-start;
  flex-direction:column}
.reviews-page .heading-actions {
  width:100%;
  justify-content:space-between}
.reviews-page .policy-ribbon {
  grid-template-columns:1fr}
.reviews-page .policy-intro {
  align-items:center}
.reviews-page .review-body {
  grid-template-columns:1fr}
.reviews-page .original-column {
  border-right:0;
  border-bottom:1px solid #eef3f1}
.reviews-page .decision-note {
  width:100%}
.reviews-page .search-box {
  margin-left:auto}
}

@media(max-width:620px) {
  .reviews-page {
  padding:20px 14px 32px}
.reviews-page .title-lockup h1 {
  font-size:26px}
.reviews-page .heading-actions {
  align-items:stretch}
.reviews-page .heading-actions>* {
  flex:1}
.reviews-page .overview-grid {
  grid-template-columns:1fr 1fr}
.reviews-page .overview-score {
  grid-column:1/-1}
.reviews-page .score-bottom {
  justify-content:space-between}
.reviews-page .policy-track {
  grid-template-columns:1fr}
.reviews-page .policy-flow-arrow {
  display:none}
.reviews-page .policy-step-risk {
  padding:14px 0 0;
  border:0;
  border-top:1px solid #cfe1db}
.reviews-page .feed-toolbar {
  align-items:stretch;
  flex-direction:column}
.reviews-page .platform-tabs {
  width:100%}
.reviews-page .search-box {
  width:100%;
  max-width:none;
  margin:0}
.reviews-page .result-count {
  width:100%;
  margin:4px 0 0}
.reviews-page .feed-section-head {
  align-items:flex-start;
  flex-direction:column;
  gap:10px}
.reviews-page .decision-actions {
  width:100%;
  margin:0}
.reviews-page .decision-actions button {
  flex:1}
.reviews-page .stamp-button {
  width:100%;
  justify-content:center}
.reviews-page .workbench-foot small {
  display:none}
.reviews-page .strategy-drawer {
  padding:20px}
.reviews-page .editor-dock {
  right:16px;
  bottom:80px}
.reviews-page .toast {
  bottom:82px}
}

@media(max-width:420px) {
  .reviews-page .overview-grid {
  grid-template-columns:1fr}
.reviews-page .overview-score {
  grid-column:auto}
.reviews-page .live-status,.reviews-page .quiet-button {
  flex:none;
  width:100%}
.reviews-page .review-card-head,.reviews-page .original-column,.reviews-page .ai-column {
  padding:16px}
.reviews-page .decision-actions {
  display:grid;
  grid-template-columns:1fr 1fr}
.reviews-page .decision-actions .stamp-button {
  grid-column:1/-1}
.reviews-page .stars {
  gap:1px}
}

@media(prefers-reduced-motion:reduce) {
  .reviews-page *,.reviews-page *:before,.reviews-page *:after {
  animation:none!important;
  transition:none!important}
}


.reviews-page {
  container-type:inline-size;
  min-width:0;
  color-scheme:light}

.reviews-page svg {
  flex-shrink:0}

.reviews-page .overline {
  text-decoration:none}

.reviews-page .stamp-button,.reviews-page .drawer-save,.reviews-page .editor-dock-foot button {
  color:white}

.reviews-page .heading-actions {
  flex-wrap:wrap}

.reviews-page .review-card {
  transition:opacity .28s,transform .28s}

.reviews-page .search-box {
  flex:0 1 230px}

@container(max-width:900px) {
  .reviews-page .reviews-heading {
  flex-direction:column;
  align-items:flex-start}
.reviews-page .heading-actions {
  width:100%;
  justify-content:space-between}
.reviews-page .policy-ribbon {
  grid-template-columns:1fr}
.reviews-page .feed-toolbar {
  flex-wrap:wrap}
.reviews-page .decision-note {
  width:100%}
}

@container(max-width:650px) {
  .reviews-page .overview-grid {
  grid-template-columns:1fr 1fr}
.reviews-page .overview-score {
  grid-column:1/-1}
.reviews-page .review-body {
  grid-template-columns:1fr}
.reviews-page .original-column {
  border-right:0;
  border-bottom:1px solid #e3ece9}
.reviews-page .platform-tabs {
  width:100%}
.reviews-page .search-box {
  flex:1 0 auto;
  width:100%;
  height:34px}
.reviews-page .feed-section-head h2 span {
  display:none}
}

@container(max-width:430px) {
  .reviews-page .heading-actions {
  flex-direction:column;
  align-items:stretch}
.reviews-page .heading-actions>* {
  width:100%;
  flex:auto}
.reviews-page .policy-track {
  grid-template-columns:1fr}
.reviews-page .policy-flow-arrow {
  display:none}
.reviews-page .policy-step-risk {
  padding:14px 0 0;
  border-left:0;
  border-top:1px solid #cfe1db}
.reviews-page .decision-actions {
  width:100%;
  margin:0}
.reviews-page .decision-actions button {
  min-width:0;
  white-space:normal;
  height:auto;
  min-height:36px}
.reviews-page .stamp-button {
  grid-column:1/-1}
}


`;
