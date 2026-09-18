export const DEMO_NOW = '2026-09-17T10:20:48+08:00'
export const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
export const stores = ['湖滨银泰店', '西湖武林店', '滨江星光店']
export const planAccounts = [
  { id: 'dy', platform: '抖音', name: '食遇 · 杭州探店', detail: '品牌官方号' },
  { id: 'xhs', platform: '小红书', name: '食遇的美味日记', detail: '生活方式号' },
  { id: 'wx', platform: '视频号', name: '食遇餐厅', detail: '门店官方号' },
]
export const bands = [
  { id: 'lunch', label: '同城午市', time: '11:30', range: '11:00 — 13:00', note: '午市高峰 11:30' },
  { id: 'dinner', label: '同城晚市', time: '18:00', range: '17:00 — 19:00', note: '晚市爆款 18:00' },
  { id: 'night', label: '夜宵闲时', time: '21:30', range: '20:00 — 22:00', note: '夜宵种草 21:30' },
]
export const drafts = [
  { id: 'd1', title: '工作日午餐，39.9 元吃得好一点', kind: '午市套餐', image: '/images/product-wall/pasta-special.png', platforms: ['抖音', '视频号'] },
  { id: 'd2', title: '杭州探店｜藏在湖滨的宝藏餐厅', kind: '晚市探店', image: '/images/publishing/restaurant.jpg', platforms: ['抖音', '视频号'] },
  { id: 'd3', title: '周末约会打卡！这家店真的很出片', kind: '打卡图文', image: '/images/publishing/restaurant.jpg', platforms: ['小红书'] },
  { id: 'd4', title: '刚出炉的牛肉披萨，趁热开吃', kind: '新品推荐', image: '/images/product-wall/beef-pizza.png', platforms: ['抖音', '小红书', '视频号'] },
  { id: 'd5', title: '下班后的快乐，是这份双人晚餐', kind: '晚市套餐', image: '/images/product-wall/pasta-special.png', platforms: ['抖音', '小红书', '视频号'] },
  { id: 'd6', title: '杭州夜宵地图，又多了一个好去处', kind: '夜宵种草', image: '/images/product-wall/beef-pizza.png', platforms: ['抖音', '小红书', '视频号'] },
]
export function seedPlans() {
  return [
    { id: 'r1', name: '工作日午市套餐种草', store: stores[0], accounts: ['dy', 'wx'], days: [0, 1, 2, 3, 4], time: '11:30', source: '顺序消耗', autoFill: true, enabled: true, lastRun: '09/16 11:38 · 成功分发 2 条' },
    { id: 'r2', name: '周末晚市探店冲榜计划', store: stores[0], accounts: ['dy', 'xhs'], days: [4, 5, 6], time: '18:00', source: '随机消耗', autoFill: true, enabled: true, lastRun: '09/13 18:08 · 成功分发 2 条' },
    { id: 'r3', name: '小红书城市打卡日记', store: stores[1], accounts: ['xhs'], days: [1, 3, 5], time: '18:00', source: '顺序消耗', autoFill: false, enabled: true, lastRun: '09/15 18:06 · 成功分发 1 条' },
    { id: 'r4', name: '夜宵时段温暖加餐', store: stores[2], accounts: ['dy', 'wx'], days: [0, 1, 2, 3, 4, 5, 6], time: '21:30', source: '随机消耗', autoFill: true, enabled: true, lastRun: '09/16 21:37 · 成功分发 2 条' },
  ]
}
export function seedTasks() {
  const rows = [
    [14, '11:38', 'dy', 0, 'r1'], [14, '18:06', 'xhs', 2, 'r3'], [14, '21:36', 'wx', 5, 'r4'],
    [15, '11:35', 'dy', 0, 'r1'], [15, '18:10', 'xhs', 2, 'r3'],
    [16, '11:38', 'wx', 0, 'r1'], [16, '18:08', 'dy', 1, 'r2'], [16, '21:37', 'dy', 5, 'r4'],
    [17, '11:45', 'dy', 0, 'r1'], [17, '11:52', 'wx', 0, 'r1'], [17, '12:08', 'xhs', 3, 'r3'],
    [17, '18:05', 'dy', 1, 'r2'], [17, '18:12', 'xhs', 2, 'r3'], [17, '18:20', 'wx', 4, 'r2'],
    [17, '21:35', 'dy', 5, 'r4'], [17, '21:42', 'wx', 5, 'r4'],
    [18, '11:36', 'dy', 0, 'r1'], [18, '18:07', 'dy', 1, 'r2'], [18, '21:39', 'wx', 5, 'r4'],
    [19, '11:40', 'xhs', 3, 'r3'], [19, '18:08', 'dy', 1, 'r2'], [19, '18:16', 'xhs', 2, 'r2'],
    [20, '11:42', 'wx', 3, 'r1'], [20, '18:06', 'xhs', 4, 'r2'], [20, '21:38', 'dy', 5, 'r4'],
  ]
  const plans = seedPlans()
  return rows.map(([day, time, accountId, draftIndex, planId], i) => {
    const plan = plans.find(p => p.id === planId)
    // Off-cycle examples represent manually scheduled works, independent of rules.
    const linked = plan.days.includes((day - 14) % 7) && plan.accounts.includes(accountId) && time.slice(0, 2) === plan.time.slice(0, 2)
    return {
    id: `seed-${i}`, date: `2026-09-${day}`, time, accountId, planId: linked ? planId : null,
    draftId: drafts[draftIndex].id, title: drafts[draftIndex].title, image: drafts[draftIndex].image,
    kind: drafts[draftIndex].kind, store: linked ? plan.store : stores[day % 3],
    status: day < 17 ? 'published' : day === 20 && time === '18:06' ? 'blocked' : 'pending',
    error: day === 20 && time === '18:06' ? '门店 POI 信息待确认，请微调作品后重新排期。' : '',
    }
  })
}
