import React, { useEffect, useRef, useState, type ReactNode } from "react";
import { motion, AnimatePresence, LayoutGroup } from "framer-motion";
import {
  AlertTriangle,
  Bell,
  BookOpen,
  Check,
  ChevronDown,
  Info,
  MessageSquareText,
  Minus,
  Plus,
  RotateCcw,
  Save,
  ShieldAlert,
  Sparkles,
  X,
  Zap,
} from "lucide-react";

type Preset = "peak" | "regular" | "closed";
type Platform = "抖音" | "大众点评" | "美团外卖" | "小红书";
type OperatingHours = "全天 24 小时" | "营业时段（10:00 - 22:00）" | "闭店打烊时段" | "自定义高峰期";
type Persona = "温和茶庄掌柜" | "新派主理人" | "恭谦院落执事";
type AlertChannel = "企微/微信消息推送" | "紧急短信通知" | "桌面端高亮弹窗提醒";

type RuleConfig = {
  platforms: Platform[];
  hours: OperatingHours;
  intents: string[];
  keywords: string[];
  persona: Persona;
  template: string;
  grounding: boolean;
  sentiment: number;
  redlines: string[];
  loopLimit: number;
  alerts: AlertChannel[];
};

const PLATFORM_META: { label: Platform; mark: string; sub: string }[] = [
  { label: "抖音", mark: "音", sub: "团购 / 私信" },
  { label: "大众点评", mark: "众", sub: "评论 / 问答" },
  { label: "美团外卖", mark: "美", sub: "外卖 / 卡券" },
  { label: "小红书", mark: "书", sub: "笔记 / 评论" },
];
const INTENTS = ["包厢与桌位预订", "排队时长咨询", "团购卡券适用规则", "停车与到店路线"];
const PERSONAS: { title: Persona; detail: string; stamp: string }[] = [
  { title: "温和茶庄掌柜", detail: "用词得体、不卑不亢、温润有礼", stamp: "和" },
  { title: "新派主理人", detail: "热情干练、节奏轻快、熟客氛围", stamp: "新" },
  { title: "恭谦院落执事", detail: "注重礼节、细致周全、仪式感强", stamp: "敬" },
];
const VARIABLES = ["客官称谓", "本店招牌菜", "剩余包厢数", "停车券规则"];
const DEFAULT_CONFIG: RuleConfig = {
  platforms: ["抖音", "大众点评", "美团外卖"],
  hours: "营业时段（10:00 - 22:00）",
  intents: ["包厢与桌位预订", "排队时长咨询", "团购卡券适用规则"],
  keywords: ["有包间吗", "能带狗吗", "开发票", "WiFi"],
  persona: "温和茶庄掌柜",
  template:
    "{客官称谓}您好，感谢您来问。{本店招牌菜}今日备得妥当，当前还余 {剩余包厢数} 间包厢。到店后可凭团购券核销，若需停车，掌柜也为您备好 {停车券规则}。",
  grounding: true,
  sentiment: 65,
  redlines: ["食物中毒", "异物/头发", "发票拒开", "态度极差", "市监局投诉"],
  loopLimit: 2,
  alerts: ["企微/微信消息推送", "桌面端高亮弹窗提醒"],
};
const PRESETS: Record<Preset, { label: string; hint: string; config: RuleConfig }> = {
  peak: {
    label: "晚市高峰 · 极速应答",
    hint: "优先处理订位、排队与到店问题",
    config: { ...DEFAULT_CONFIG, hours: "自定义高峰期", sentiment: 55, loopLimit: 1 },
  },
  regular: {
    label: "常规营业 · 温润待客",
    hint: "平衡自动应答与掌柜介入",
    config: DEFAULT_CONFIG,
  },
  closed: {
    label: "闭门打烊 · 留言指引",
    hint: "收束对话，留下次日回访线索",
    config: {
      ...DEFAULT_CONFIG,
      hours: "闭店打烊时段",
      intents: ["包厢与桌位预订", "停车与到店路线"],
      template: "{客官称谓}，小院今日已闭门歇息。您可留下到店日期与人数，掌柜明日 10:00 后第一时间为您安排。",
      sentiment: 45,
      loopLimit: 1,
    },
  },
};

function cloneConfig(config: RuleConfig): RuleConfig {
  return { ...config, platforms: [...config.platforms], intents: [...config.intents], keywords: [...config.keywords], redlines: [...config.redlines], alerts: [...config.alerts] };
}
function sameConfig(a: RuleConfig, b: RuleConfig) {
  return JSON.stringify(a) === JSON.stringify(b);
}

function SectionHeading({ eyebrow, title, description, icon }: { eyebrow: string; title: string; description: string; icon: ReactNode }) {
  return (
    <div className="mb-5 flex items-start gap-3">
      <div className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-[#B8D3CD] bg-[#EDF4F2] text-[#234D45]">{icon}</div>
      <div>
        <p className="mb-1 font-mono text-[10px] uppercase tracking-[0.18em] text-[#7B8E89]">{eyebrow}</p>
        <h2 className="font-serif text-[19px] font-semibold tracking-[0.02em] text-[#1C3532]">{title}</h2>
        <p className="mt-1 text-[12px] leading-5 text-[#71817E]">{description}</p>
      </div>
    </div>
  );
}
function Toggle({ checked, onChange, label }: { checked: boolean; onChange: () => void; label: string }) {
  return (
    <button aria-label={label} role="switch" aria-checked={checked} type="button" onClick={onChange} className={`relative h-6 w-11 shrink-0 rounded-full border transition-colors ${checked ? "border-[#1C3532] bg-[#1C3532]" : "border-[#C5D2CE] bg-[#E8EEEB]"}`}>
      <motion.span animate={{ x: checked ? 20 : 2 }} transition={{ duration: 0.18 }} className="absolute left-0 top-0.5 h-5 w-5 rounded-full bg-white shadow-[0_1px_3px_rgba(28,53,50,.25)]" />
    </button>
  );
}
function SoftCard({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <section className={`rounded-2xl border border-[#E2EAE7] bg-white shadow-[0_8px_26px_rgba(28,53,50,.045)] ${className}`}>{children}</section>;
}
function PlatformSelector({ selected, onToggle }: { selected: Platform[]; onToggle: (platform: Platform) => void }) {
  return <div className="grid grid-cols-2 gap-2">{PLATFORM_META.map((platform) => {
    const active = selected.includes(platform.label);
    return <button type="button" key={platform.label} onClick={() => onToggle(platform.label)} className={`group relative min-h-[66px] rounded-xl border px-3 py-2.5 text-left transition ${active ? "border-[#B8D3CD] bg-[#EBF3F0]" : "border-[#E2EAE7] bg-[#FCFDFC] hover:border-[#B8D3CD]"}`}>
      <span className={`absolute right-2 top-2 flex h-4 w-4 items-center justify-center rounded-full border text-[10px] ${active ? "border-[#1C3532] bg-[#1C3532] text-white" : "border-[#D4DFDC] text-transparent"}`}><Check size={10} strokeWidth={2.5} /></span>
      <span className={`mb-2 flex h-7 w-7 items-center justify-center rounded-lg font-serif text-[13px] font-semibold ${active ? "bg-[#1C3532] text-white" : "bg-[#EDF4F2] text-[#52726B]"}`}>{platform.mark}</span>
      <span className="block text-[12px] font-semibold text-[#2B4641]">{platform.label}</span>
      <span className="mt-0.5 block text-[10px] text-[#91A09D]">{platform.sub}</span>
    </button>;
  })}</div>;
}
function RuleCard({ number, title, children }: { number: string; title: string; children: ReactNode }) {
  return <div className="border-t border-[#E2EAE7] pt-5 first:border-t-0 first:pt-0"><div className="mb-3 flex items-center gap-2"><span className="font-mono text-[10px] tracking-[0.15em] text-[#B2C1BD]">{number}</span><span className="text-[12px] font-semibold text-[#35504B]">{title}</span></div>{children}</div>;
}

export default function ServiceRulesWorkbench() {
  const [saved, setSaved] = useState<RuleConfig>(() => cloneConfig(DEFAULT_CONFIG));
  const [draft, setDraft] = useState<RuleConfig>(() => cloneConfig(DEFAULT_CONFIG));
  const [preset, setPreset] = useState<Preset>("regular");
  const [managed, setManaged] = useState(true);
  const [keywordInput, setKeywordInput] = useState("");
  const [redlineInput, setRedlineInput] = useState("");
  const [showGrounding, setShowGrounding] = useState(false);
  const [toast, setToast] = useState("");
  const [toastKind, setToastKind] = useState<"saved" | "info">("saved");
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const dirty = !sameConfig(saved, draft) || managed !== true;

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(""), 2600);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const update = <K extends keyof RuleConfig>(key: K, value: RuleConfig[K]) => setDraft((current) => ({ ...current, [key]: value }));
  const toggleArray = <T extends string>(key: "intents" | "alerts" | "platforms", value: T) => {
    setDraft((current) => {
      const values = current[key] as T[];
      return { ...current, [key]: values.includes(value) ? values.filter((item) => item !== value) : [...values, value] };
    });
  };
  const addTag = (key: "keywords" | "redlines", value: string) => {
    const clean = value.trim();
    if (!clean) return;
    setDraft((current) => current[key].includes(clean) ? current : { ...current, [key]: [...current[key], clean] });
    if (key === "keywords") setKeywordInput(""); else setRedlineInput("");
  };
  const removeTag = (key: "keywords" | "redlines", value: string) => update(key, draft[key].filter((item) => item !== value));
  const insertVariable = (variable: string) => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    const token = `{${variable}}`;
    const start = textarea.selectionStart ?? draft.template.length;
    const end = textarea.selectionEnd ?? start;
    const next = `${draft.template.slice(0, start)}${token}${draft.template.slice(end)}`;
    update("template", next);
    window.requestAnimationFrame(() => {
      textarea.focus();
      const cursor = start + token.length;
      textarea.setSelectionRange(cursor, cursor);
    });
  };
  const applyPreset = (nextPreset: Preset) => {
    setPreset(nextPreset);
    setDraft(cloneConfig(PRESETS[nextPreset].config));
    setToast(`${PRESETS[nextPreset].label}已载入`);
    setToastKind("info");
  };
  const discard = () => {
    setDraft(cloneConfig(saved));
    setManaged(true);
    setToast("已恢复上次钤印配置");
    setToastKind("info");
  };
  const save = () => {
    setSaved(cloneConfig(draft));
    setManaged(true);
    setToast("配置已钤印生效，灵犀开始按新规度值守");
    setToastKind("saved");
  };

  return (
    <div className="service-rules-workbench min-h-full bg-[#F8F6F1] text-[#1C3532]" style={{ backgroundImage: "radial-gradient(rgba(28,53,50,.035) .7px, transparent .7px)", backgroundSize: "13px 13px" }}>
      <div className="mx-auto max-w-[1540px] px-5 py-6 sm:px-8 lg:px-10 lg:py-8">
        <header className="mb-7 border-b border-[#DDE7E3] pb-6">
          <div className="flex flex-col gap-5 2xl:flex-row 2xl:items-end 2xl:justify-between">
            <div className="min-w-0">
              <div className="mb-4 flex items-center gap-2 text-[11px] text-[#7B8E89]"><span>客服中枢</span><span className="text-[#C7D4D0]">/</span><span className="text-[#1C3532]">规则与流转</span><span className="ml-1 rounded-full border border-[#B8D3CD] bg-[#EDF4F2] px-2 py-0.5 font-mono text-[9px] tracking-[0.12em] text-[#234D45]">RULES 2.4</span></div>
              <div className="flex flex-wrap items-end gap-x-5 gap-y-3">
                <div><h1 className="font-serif text-[clamp(28px,4vw,44px)] font-semibold leading-[1.08] tracking-[0.015em] text-[#1C3532]">客服规度与流转设置</h1><p className="mt-3 text-[13px] tracking-[0.04em] text-[#71817E]">以意图为经，辞章为纬，掌柜断席自如。</p></div>
                <div className="hidden h-12 w-px bg-[#DDE7E3] sm:block" />
                <div className="mb-0.5 flex items-center gap-2 text-[11px] text-[#70837F]"><span className="h-2 w-2 rounded-full bg-[#4F8B78] shadow-[0_0_0_4px_rgba(79,139,120,.1)]" />今日已承接 <strong className="font-mono text-[#1C3532]">286</strong> 次问询</div>
              </div>
            </div>
            <div className="flex w-full flex-col gap-3 lg:flex-row lg:items-center lg:justify-end 2xl:w-auto">
              <div className="flex items-center justify-between gap-3 rounded-xl border border-[#B8D3CD] bg-[#EDF4F2] px-3.5 py-2.5 lg:flex-1 2xl:flex-none"><div><div className="flex items-center gap-2 text-[12px] font-semibold text-[#234D45]"><Sparkles size={14} /> 一方灵犀 · 全局自动值守中</div><div className="mt-1 text-[10px] text-[#5F7C75]">已接入 3 个平台 · 规则实时生效</div></div><Toggle label="全局自动值守" checked={managed} onChange={() => setManaged((value) => !value)} /></div>
              <div className="flex items-center gap-2"><button type="button" onClick={discard} disabled={!dirty} className="inline-flex h-10 items-center justify-center gap-2 rounded-xl border border-[#BFD0CB] bg-white px-4 text-[12px] font-medium text-[#4A615C] transition hover:bg-[#F4F8F6] disabled:cursor-not-allowed disabled:opacity-45"><RotateCcw size={14} />放弃修改</button><motion.button type="button" onClick={save} animate={dirty ? { boxShadow: ["0 0 0 0 rgba(196,61,42,0)", "0 0 0 6px rgba(196,61,42,.1)", "0 0 0 0 rgba(196,61,42,0)"] } : { boxShadow: "0 0 0 0 rgba(196,61,42,0)" }} transition={{ duration: 2.4, repeat: dirty ? Infinity : 0 }} className="service-rules-primary-action inline-flex h-10 items-center justify-center gap-2 rounded-xl bg-[#C43D2A] px-4 text-[12px] font-semibold text-white shadow-[0_5px_12px_rgba(196,61,42,.16)] transition hover:bg-[#AC3425]"><Save size={14} />钤印生效 <span className="hidden font-normal opacity-70 sm:inline">· 保存配置</span></motion.button></div>
            </div>
          </div>
          <div className="mt-6 flex flex-col gap-3 rounded-xl border border-[#E2EAE7] bg-white/75 p-3 sm:flex-row sm:items-center sm:justify-between"><div className="flex shrink-0 items-center gap-2 text-[11px] font-semibold text-[#4C6560]"><Zap size={14} className="text-[#CE8F2A]" />策略预设模板</div><div className="flex flex-1 flex-wrap gap-2 sm:justify-end">{(Object.keys(PRESETS) as Preset[]).map((key) => <button type="button" key={key} onClick={() => applyPreset(key)} className={`rounded-lg border px-3 py-2 text-left transition ${preset === key ? "border-[#B8D3CD] bg-[#EBF3F0] text-[#1C3532]" : "border-[#E2EAE7] bg-white text-[#74837F] hover:border-[#B8D3CD]"}`}><span className="block text-[11px] font-semibold">{PRESETS[key].label}</span><span className="mt-0.5 block text-[10px] text-[#8E9D99]">{PRESETS[key].hint}</span></button>)}</div><ChevronDown size={15} className="hidden text-[#8FA09B] sm:block" /></div>
        </header>

        <LayoutGroup>
          <main className="grid grid-cols-1 items-start gap-6 xl:grid-cols-3">
            <SoftCard className="p-5 xl:p-6"><SectionHeading eyebrow="01 · TRIGGER CRITERIA" title="触机 · 触发条件" description="先定何时听见，再定听见什么。" icon={<Zap size={17} />} /><div className="space-y-6"><RuleCard number="01" title="适用平台" ><PlatformSelector selected={draft.platforms} onToggle={(platform) => toggleArray("platforms", platform)} /></RuleCard><RuleCard number="02" title="生效时段规约"><div className="space-y-2">{(["全天 24 小时", "营业时段（10:00 - 22:00）", "闭店打烊时段", "自定义高峰期"] as OperatingHours[]).map((hours) => <label key={hours} className={`flex cursor-pointer items-center gap-3 rounded-xl border px-3 py-2.5 transition ${draft.hours === hours ? "border-[#B8D3CD] bg-[#EDF4F2]" : "border-[#E2EAE7] hover:border-[#CBDAD6]"}`}><input type="radio" name="hours" value={hours} checked={draft.hours === hours} onChange={() => update("hours", hours)} className="h-3.5 w-3.5 accent-[#1C3532]" /><span className="text-[12px] text-[#4A625D]">{hours}</span>{hours === "自定义高峰期" && <span className="ml-auto rounded-md bg-[#FAF4E8] px-1.5 py-0.5 font-mono text-[9px] text-[#CE8F2A]">18:00—20:30</span>}</label>)}</div></RuleCard><RuleCard number="03" title="顾客意图识别集"><div className="space-y-2">{INTENTS.map((intent) => <label key={intent} className="flex items-center justify-between gap-3 rounded-xl border border-[#E2EAE7] px-3 py-2.5"><span className="flex items-center gap-2 text-[12px] text-[#4A625D]"><span className={`h-1.5 w-1.5 rounded-full ${draft.intents.includes(intent) ? "bg-[#4F8B78]" : "bg-[#CFD9D5]"}`} />{intent}</span><input type="checkbox" checked={draft.intents.includes(intent)} onChange={() => toggleArray("intents", intent)} className="h-4 w-4 accent-[#1C3532]" /></label>)}</div></RuleCard><RuleCard number="04" title="精准关键词捕获池"><div className="rules-field rounded-xl border border-[#E2EAE7] bg-[#FCFDFC] p-3"><LayoutGroup><div className="flex flex-wrap gap-2"><AnimatePresence initial={false}>{draft.keywords.map((keyword) => <motion.span layout initial={{ opacity: 0, scale: .75 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: .7 }} key={keyword} className="inline-flex items-center gap-1.5 rounded-full border border-[#B8D3CD] bg-[#EDF4F2] px-2.5 py-1 text-[11px] font-medium text-[#234D45]">{keyword}<button type="button" onClick={() => removeTag("keywords", keyword)} aria-label={`删除关键词 ${keyword}`} className="text-[#6F8F87] hover:text-[#C43D2A]"><X size={12} /></button></motion.span>)}</AnimatePresence><input value={keywordInput} onChange={(event) => setKeywordInput(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") { event.preventDefault(); addTag("keywords", keywordInput); } }} placeholder="输入关键词，回车添加" className="min-w-[130px] flex-1 bg-transparent px-1 py-1 text-[11px] text-[#1C3532] outline-none placeholder:text-[#A5B2AE]" /></div></LayoutGroup><p className="mt-3 flex items-center gap-1.5 text-[10px] text-[#9AA8A4]"><Info size={12} />关键词命中后，将进入对应意图的回复路径</p></div></RuleCard></div></SoftCard>

            <SoftCard className="p-5 xl:p-6"><SectionHeading eyebrow="02 · RESPONSE & PERSONA" title="辞章 · 话术与人设" description="让自动应答像一位熟悉门店的掌柜。" icon={<MessageSquareText size={17} />} /><div className="space-y-6"><RuleCard number="01" title="AI 掌柜语气人设"><div className="space-y-2">{PERSONAS.map((persona) => <button type="button" key={persona.title} onClick={() => update("persona", persona.title)} className={`flex w-full items-center gap-3 rounded-xl border p-3 text-left transition ${draft.persona === persona.title ? "border-[#B8D3CD] bg-[#EBF3F0]" : "border-[#E2EAE7] hover:border-[#B8D3CD]"}`}><span className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg font-serif text-[14px] font-semibold ${draft.persona === persona.title ? "bg-[#1C3532] text-white" : "bg-[#F0F4F2] text-[#52726B]"}`}>{persona.stamp}</span><span className="min-w-0 flex-1"><span className="block text-[12px] font-semibold text-[#2D4742]">{persona.title}</span><span className="mt-1 block text-[10px] leading-4 text-[#82918D]">{persona.detail}</span></span>{draft.persona === persona.title ? <span className="flex h-5 w-5 items-center justify-center rounded-full bg-[#1C3532] text-white"><Check size={12} /></span> : <span className="h-5 w-5 rounded-full border border-[#C8D5D1]" />}</button>)}</div></RuleCard><RuleCard number="02" title="动态回复模板编排"><div className="mb-2 flex flex-wrap gap-1.5">{VARIABLES.map((variable) => <button type="button" key={variable} onClick={() => insertVariable(variable)} className="rounded-full border border-[#B8D3CD] bg-[#EDF4F2] px-2.5 py-1 text-[10px] font-medium text-[#234D45] transition hover:bg-[#DDECE7]">+ {variable}</button>)}</div><div className="rules-field rounded-xl border border-[#E2EAE7] bg-[#FBFDFC] p-3"><textarea ref={textareaRef} value={draft.template} onChange={(event) => update("template", event.target.value)} rows={7} className="w-full resize-none bg-transparent text-[12px] leading-6 text-[#35504B] outline-none placeholder:text-[#9BAAA6]" /><div className="flex items-center justify-between border-t border-[#E2EAE7] pt-2 text-[10px] text-[#94A39F]"><span>支持动态变量 · 回复前自动校验事实依据</span><span className="font-mono">{draft.template.length}/280</span></div></div></RuleCard><RuleCard number="03" title="知识库信源锚定"><div className="rounded-xl border border-[#B8D3CD] bg-[#EDF4F2] p-3.5"><div className="flex items-start gap-3"><BookOpen size={17} className="mt-0.5 shrink-0 text-[#234D45]" /><div className="min-w-0 flex-1"><div className="flex items-center justify-between gap-2"><p className="text-[12px] font-semibold text-[#234D45]">已深度关联 2 份门店规约</p><Toggle label="知识库信源锚定" checked={draft.grounding} onChange={() => update("grounding", !draft.grounding)} /></div><p className="mt-1 text-[10px] leading-5 text-[#5E7A73]">《秋季菜单及过敏原公示》 · 《包间定金规约》</p><button type="button" onClick={() => setShowGrounding(true)} className="mt-2 text-[10px] font-semibold text-[#234D45] underline decoration-[#9DBEB5] underline-offset-4">查看信源摘要 →</button></div></div></div></RuleCard></div></SoftCard>

            <SoftCard className="p-5 xl:p-6"><SectionHeading eyebrow="03 · HUMAN ESCALATION & SAFETY" title="接席 · 转人工与熔断" description="让风险在抵达客人之前，被掌柜接住。" icon={<ShieldAlert size={17} />} /><div className="space-y-6"><RuleCard number="01" title="负面情绪熔断阈值"><div className="rounded-xl border border-[#F2C2BB] bg-[#FDF2F0] p-3.5"><div className="mb-4 flex items-end justify-between"><div><p className="text-[11px] text-[#81544D]">推测负向情绪达到</p><p className="mt-1 font-mono text-[27px] font-semibold leading-none text-[#C43D2A]">{draft.sentiment}<span className="text-[14px]">%</span></p></div><span className="rounded-md bg-white/70 px-2 py-1 text-[10px] text-[#A4675E]">立即挂起 · 转人工</span></div><input aria-label="负面情绪熔断阈值" type="range" min={30} max={90} value={draft.sentiment} onChange={(event) => update("sentiment", Number(event.target.value))} className="w-full accent-[#C43D2A]" /><div className="mt-2 flex justify-between font-mono text-[9px] text-[#AD817A]"><span>30% 温和提醒</span><span>60% 谨慎</span><span>90% 高危</span></div></div></RuleCard><RuleCard number="02" title="朱砂高危红线词库"><div className="rules-field rules-field-danger rounded-xl border border-[#F2C2BB] bg-[#FDF2F0] p-3"><div className="flex flex-wrap gap-2"><AnimatePresence initial={false}>{draft.redlines.map((word) => <motion.span layout initial={{ opacity: 0, scale: .75 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: .7 }} key={word} className="inline-flex items-center gap-1.5 rounded-full border border-[#F2C2BB] bg-white/70 px-2.5 py-1 text-[11px] font-medium text-[#C43D2A]">{word}<button type="button" onClick={() => removeTag("redlines", word)} aria-label={`删除高危词 ${word}`}><X size={12} /></button></motion.span>)}</AnimatePresence><input value={redlineInput} onChange={(event) => setRedlineInput(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") { event.preventDefault(); addTag("redlines", redlineInput); } }} placeholder="追加专属高危词" className="min-w-[115px] flex-1 bg-transparent px-1 py-1 text-[11px] outline-none placeholder:text-[#C18A82]" /></div><div className="mt-3 flex items-center gap-1.5 text-[10px] text-[#B26A61]"><AlertTriangle size={12} />命中红线词时，AI 将停止生成并通知掌柜</div></div></RuleCard><RuleCard number="03" title="轮次阻滞熔断"><div className="flex items-center justify-between rounded-xl border border-[#E2EAE7] bg-[#FCFDFC] p-3"><div><p className="text-[12px] font-semibold text-[#35504B]">同一问题连续未达成意向</p><p className="mt-1 text-[10px] text-[#91A09D]">超过轮次后强制转人工会话</p></div><div className="flex items-center gap-2 rounded-lg border border-[#D5E1DD] bg-white p-1"><button type="button" aria-label="减少轮次" onClick={() => update("loopLimit", Math.max(1, draft.loopLimit - 1))} className="flex h-7 w-7 items-center justify-center rounded-md text-[#58716A] hover:bg-[#EDF4F2]"><Minus size={13} /></button><span className="min-w-8 text-center font-mono text-[14px] text-[#1C3532]">{draft.loopLimit}</span><button type="button" aria-label="增加轮次" onClick={() => update("loopLimit", Math.min(5, draft.loopLimit + 1))} className="flex h-7 w-7 items-center justify-center rounded-md text-[#58716A] hover:bg-[#EDF4F2]"><Plus size={13} /></button></div></div></RuleCard><RuleCard number="04" title="掌柜唤醒通道"><div className="space-y-2">{(["企微/微信消息推送", "紧急短信通知", "桌面端高亮弹窗提醒"] as AlertChannel[]).map((alert) => <label key={alert} className="flex items-center justify-between gap-3 rounded-xl border border-[#E2EAE7] px-3 py-2.5"><span className="flex items-center gap-2 text-[12px] text-[#4A625D]"><Bell size={13} className="text-[#CE8F2A]" />{alert}</span><input type="checkbox" checked={draft.alerts.includes(alert)} onChange={() => toggleArray("alerts", alert)} className="h-4 w-4 accent-[#1C3532]" /></label>)}</div></RuleCard></div></SoftCard>
          </main>
        </LayoutGroup>

        <footer className="mt-6 flex flex-col gap-3 rounded-2xl border border-[#E2EAE7] bg-white/70 px-5 py-4 text-[11px] text-[#7B8E89] sm:flex-row sm:items-center sm:justify-between"><div className="flex items-center gap-2"><span className="flex h-5 w-5 items-center justify-center rounded-full bg-[#EBF3F0] text-[#234D45]"><Check size={12} /></span>当前配置已通过「门店知识一致性」预检</div><div className="flex items-center gap-4"><span>最近钤印：今天 14:32</span><span className="hidden h-3 w-px bg-[#D8E3DF] sm:block" /><span className="font-mono text-[10px] tracking-[0.08em] text-[#9AA8A4]">YFZ / CS-ROUTING</span></div></footer>
      </div>

      <AnimatePresence>{showGrounding && <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="fixed inset-0 z-50 flex items-center justify-center bg-[#1C3532]/25 p-5 backdrop-blur-[2px]" onClick={() => setShowGrounding(false)}><motion.div initial={{ y: 12, opacity: 0, scale: .98 }} animate={{ y: 0, opacity: 1, scale: 1 }} exit={{ y: 8, opacity: 0, scale: .98 }} onClick={(event) => event.stopPropagation()} className="w-full max-w-[520px] rounded-2xl border border-[#D5E2DE] bg-white p-6 shadow-[0_20px_60px_rgba(28,53,50,.16)]"><div className="flex items-start justify-between gap-4"><div><p className="font-mono text-[10px] tracking-[0.15em] text-[#7B8E89]">GROUNDING SOURCES</p><h3 className="mt-2 font-serif text-[21px] font-semibold text-[#1C3532]">门店知识信源摘要</h3></div><button type="button" onClick={() => setShowGrounding(false)} aria-label="关闭摘要" className="rounded-lg p-1.5 text-[#78908A] hover:bg-[#EDF4F2]"><X size={17} /></button></div><div className="mt-5 space-y-3"><div className="rounded-xl border border-[#B8D3CD] bg-[#EDF4F2] p-4"><p className="text-[12px] font-semibold text-[#234D45]">《秋季菜单及过敏原公示》</p><p className="mt-2 text-[11px] leading-5 text-[#5D7972]">已核验：季节菜品、主要过敏原、停售提醒与招牌菜可售时段。最近同步：今天 09:16。</p></div><div className="rounded-xl border border-[#B8D3CD] bg-[#EDF4F2] p-4"><p className="text-[12px] font-semibold text-[#234D45]">《包间定金规约》</p><p className="mt-2 text-[11px] leading-5 text-[#5D7972]">已核验：包间数量、定金退改边界、迟到保留时长与团购券核销规则。最近同步：昨天 18:42。</p></div></div><button type="button" onClick={() => setShowGrounding(false)} className="mt-5 inline-flex h-10 w-full items-center justify-center gap-2 rounded-xl bg-[#1C3532] text-[12px] font-semibold text-white hover:bg-[#294B46]"><BookOpen size={14} />返回规则配置</button></motion.div></motion.div>}</AnimatePresence>
      <AnimatePresence>{toast && <motion.div initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 12 }} className={`fixed bottom-5 right-5 z-[60] flex max-w-[360px] items-center gap-2 rounded-xl border px-4 py-3 text-[12px] shadow-[0_10px_30px_rgba(28,53,50,.12)] ${toastKind === "saved" ? "border-[#B8D3CD] bg-[#EDF4F2] text-[#234D45]" : "border-[#E2EAE7] bg-white text-[#4B625D]"}`}><span className={`flex h-5 w-5 items-center justify-center rounded-full ${toastKind === "saved" ? "bg-[#1C3532] text-white" : "bg-[#FAF4E8] text-[#CE8F2A]"}`}>{toastKind === "saved" ? <Check size={12} /> : <Info size={12} />}</span>{toast}</motion.div>}</AnimatePresence>
    </div>
  );
}
