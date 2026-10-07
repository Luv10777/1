/**
 * 门店档案：交通指引、配套服务、特殊营业安排。
 * 这里只做表单和展示用的整理，规则以后端 StoreDtos 为准。
 */

export const AMENITY_PRESETS = ['免费停车', 'Wi-Fi', '包间', '宠物友好', '充电宝', '无障碍通道', '可开发票', '支持预约']
export const WEEKDAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
export const AMENITY_MAX_LENGTH = 20
export const AMENITY_MAX_COUNT = 20
export const SPECIAL_HOUR_MAX_COUNT = 30

export const blankSpecialHour = () => ({ scope: 'WEEKLY', weekday: 1, date: '', closed: true, opensAt: '10:00', closesAt: '22:00', note: '' })

/** 加一项配套服务：去掉首尾空格，不重复，不超长，不超过上限。返回新的列表。 */
export function addAmenity(list, value) {
  const name = String(value ?? '').trim().slice(0, AMENITY_MAX_LENGTH)
  if (!name || list.includes(name) || list.length >= AMENITY_MAX_COUNT) return list
  return [...list, name]
}

/** 一条安排的说法，和直播拿到的门店资料用同一种写法，方便对照。 */
export function describeSpecialHour(rule) {
  let when
  if (rule.scope === 'WEEKLY') {
    when = `每${WEEKDAYS[rule.weekday - 1] || ''}`
  } else {
    const [year, month, day] = String(rule.date || '').split('-').map(Number)
    when = year && month && day ? `${year}年${month}月${day}日` : '未选日期'
  }
  const what = rule.closed ? '休息' : `营业 ${rule.opensAt || '--:--'} 至 ${rule.closesAt || '--:--'}`
  const note = String(rule.note ?? '').trim()
  return `${when}${what}${note ? `（${note}）` : ''}`
}

/** 提交前检查每条安排；有问题时抛出给用户看的话。 */
function specialHourPayload(rule, index) {
  const where = `特殊营业安排第 ${index + 1} 条：`
  const weekly = rule.scope === 'WEEKLY'
  if (weekly && !(rule.weekday >= 1 && rule.weekday <= 7)) throw new Error(`${where}请选择星期几`)
  if (!weekly && !/^\d{4}-\d{2}-\d{2}$/.test(rule.date || '')) throw new Error(`${where}请选择日期`)
  if (!rule.closed && (!rule.opensAt || !rule.closesAt)) throw new Error(`${where}请填写这一天的营业时间，或改为休息`)
  const payload = { scope: weekly ? 'WEEKLY' : 'DATE', closed: Boolean(rule.closed) }
  if (weekly) payload.weekday = Number(rule.weekday)
  else payload.date = rule.date
  if (!rule.closed) { payload.opensAt = rule.opensAt; payload.closesAt = rule.closesAt }
  const note = String(rule.note ?? '').trim()
  if (note) payload.note = note
  return payload
}

/**
 * 档案三项的提交内容。三项都带上：空字符串和空列表表示清空。
 * （后端对"没带"和"带空值"区别对待，完整的门店表单总是带上。）
 */
export function profilePayload(form) {
  return {
    transportGuide: String(form.transportGuide ?? '').trim(),
    amenities: [...new Set((form.amenities || []).map(item => String(item).trim()).filter(Boolean))],
    specialHours: (form.specialHours || []).map(specialHourPayload),
  }
}

/** 从接口返回的门店生成表单里的档案部分；没填过的项给出空值。 */
export function profileForm(store) {
  return {
    transportGuide: store?.transportGuide || '',
    amenities: [...(store?.amenities || [])],
    specialHours: (store?.specialHours || []).map(rule => ({ ...blankSpecialHour(), ...rule, date: rule.date || '', note: rule.note || '' })),
  }
}
