// Names are sent as the request `style`; the server keeps the matching art-direction notes.
export const POSTER_STYLES = [
  { key: 'editorial', name: '杂志封面', fit: '招牌产品、新品' },
  { key: 'quiet', name: '日式留白', fit: '咖啡、甜品、轻食' },
  { key: 'ink', name: '新中式', fit: '茶饮、节气、老字号' },
  { key: 'hongkong', name: '港风复古', fit: '夜宵、冰室、小酒馆' },
  { key: 'bold', name: '大字报', fit: '促销、限时活动' },
  { key: 'film', name: '胶片生活感', fit: '到店氛围、服务' },
  { key: 'drawn', name: '手绘插画', fit: '甜品、亲子、烘焙' },
  { key: 'lowkey', name: '暗调质感', fit: '酒吧、精品、高客单' },
]

export const SEASONAL_PREFIX = '应时·'

// 寿星公式 (21st century): day = floor(Y * 0.2422 + C) - floor(Y / 4), with Y - 1 for January and February terms.
const SOLAR_TERMS = [
  ['小寒', 1, 5.4055], ['大寒', 1, 20.12], ['立春', 2, 3.87], ['雨水', 2, 18.73], ['惊蛰', 3, 5.63], ['春分', 3, 20.646],
  ['清明', 4, 4.81], ['谷雨', 4, 20.1], ['立夏', 5, 5.52], ['小满', 5, 21.04], ['芒种', 6, 5.678], ['夏至', 6, 21.37],
  ['小暑', 7, 7.108], ['大暑', 7, 22.83], ['立秋', 8, 7.5], ['处暑', 8, 23.13], ['白露', 9, 7.646], ['秋分', 9, 23.042],
  ['寒露', 10, 8.318], ['霜降', 10, 23.438], ['立冬', 11, 7.438], ['小雪', 11, 22.36], ['大雪', 12, 7.18], ['冬至', 12, 21.94],
]
const TERM_CORRECTIONS = { '2021冬至': -1, '2026雨水': -1, '2082大寒': 1, '2084春分': 1, '2089霜降': 1, '2089立冬': 1 }
// Lunar festivals (春节、端午、中秋) need a lunar calendar and are not listed yet.
const FIXED_DAYS = [['元旦', 1, 1], ['情人节', 2, 14], ['妇女节', 3, 8], ['劳动节', 5, 1], ['儿童节', 6, 1], ['国庆', 10, 1], ['双十一', 11, 11], ['圣诞', 12, 25]]

function momentsOf(year) {
  const y = year % 100
  const terms = SOLAR_TERMS.map(([name, month, c]) => {
    const leap = Math.floor((month <= 2 ? y - 1 : y) / 4)
    return [name, new Date(year, month - 1, Math.floor(y * 0.2422 + c) - leap + (TERM_CORRECTIONS[year + name] || 0))]
  })
  return [...terms, ...FIXED_DAYS.map(([name, month, day]) => [name, new Date(year, month - 1, day)])].sort((a, b) => a[1] - b[1])
}

/** The next solar term or fixed-date festival from `today` (inclusive), and the one after it. */
export function upcomingMoment(today = new Date()) {
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  const moments = [...momentsOf(start.getFullYear()), ...momentsOf(start.getFullYear() + 1)].filter(([, date]) => date >= start)
  const [[name, date], [next]] = moments
  return { name, month: date.getMonth() + 1, day: date.getDate(), days: Math.round((date - start) / 86400000), next }
}
