// Calendar and engine dates always use the merchant's Asia/Shanghai timezone.
export function dateKey(value) {
  return new Date(new Date(value).getTime() + 8 * 3600000).toISOString().slice(0, 10)
}
export function weekDates(value, offset = 0) {
  const date = new Date(`${dateKey(value)}T00:00:00+08:00`)
  const weekday = new Date(date.getTime() + 8 * 3600000).getUTCDay()
  date.setUTCDate(date.getUTCDate() - (weekday + 6) % 7 + offset * 7)
  return Array.from({ length: 7 }, (_, i) => dateKey(date.getTime() + i * 86400000))
}
export function taskTime(task) { return new Date(`${task.date}T${task.time}:00+08:00`).getTime() }
export function bandFor(time) { return time < '15:00' ? 'lunch' : time < '20:00' ? 'dinner' : 'night' }
export function nextOccurrence(plan, now) {
  for (let i = 0; i < 8; i++) {
    const day = dateKey(new Date(now).getTime() + i * 86400000)
    const weekday = (new Date(`${day}T12:00:00+08:00`).getUTCDay() + 6) % 7
    const time = taskTime({ date: day, time: plan.time })
    if (plan.days.includes(weekday) && time > new Date(now).getTime()) return { date: day, time: plan.time }
  }
  return null
}
export function matchDraft(pool, tasks, account, source, random = Math.random) {
  const used = new Set(tasks.filter(t => t.accountId === account.id).map(t => t.draftId))
  const available = pool.filter(d => d.platforms.includes(account.platform) && !used.has(d.id))
  return available[source === '随机消耗' ? Math.floor(random() * available.length) : 0] || null
}
export function dueTasks(tasks, plans, now) {
  return tasks.filter(t => t.status === 'pending' && taskTime(t) <= new Date(now).getTime() && (!t.planId || plans.some(p => p.id === t.planId && p.enabled)))
}
