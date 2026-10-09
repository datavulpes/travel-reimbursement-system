// 列表页的「本地筛选 + 本地分页」。申请单、报销两页最早是各写一份，
// 现在审批、财务、档案页也要用，抽成这一个，口径统一：
//   - 数据一次取回来（后端每页最多 100 条，翻完为止），之后筛选与切页都在本地，
//     所以「共 N 条」是筛完的条数，不是库里总数；
//   - 只有后端认的条件变了才重新请求，改本地条件不碰网络；
//   - 改条件自动回第 1 页，取页时用 Math.min 夹一下，防止筛完条数变少还停在空页。
import { ref, computed, reactive } from 'vue'

/**
 * 返回一个 reactive 对象，页面直接 `L.total` / `L.pagedRows` 用。
 * 包 reactive 是为了让里面的 ref 自动解包——同一个页面要开两份时（档案页的对象与用户）
 * 解构成两个名字会把模板写得很啰嗦，而普通对象里的 ref 在模板里不会自动解包。
 */
export function usePagedList (filterFn, initialFilters = {}) {
  const all = ref([])
  const filters = ref({ ...initialFilters })
  const pageNum = ref(1)
  const pageSize = ref(10)

  const filtered = computed(() => filterFn(all.value, filters.value))
  const total = computed(() => filtered.value.length)
  const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
  const pagedRows = computed(() => {
    const p = Math.min(pageNum.value, totalPages.value)
    const start = (p - 1) * pageSize.value
    return filtered.value.slice(start, start + pageSize.value)
  })

  function onPage (n) { pageNum.value = n }
  function onSize (n) { pageSize.value = n; pageNum.value = 1 }
  function resetPaging () { pageNum.value = 1 }
  function setList (list) { all.value = list || [] }

  return reactive({
    all, filters, filtered, pageNum, pageSize,
    total, totalPages, pagedRows,
    onPage, onSize, resetPaging, setList
  })
}

/** 金额档位：value 形如 '1000-5000'，上限 0 表示不封顶 */
export function inBand (v, band) {
  if (!band) return true
  const p = String(band).split('-')
  const lo = Number(p[0])
  const hi = Number(p[1])
  return v >= lo && (hi > 0 ? v < hi : true)
}

/** 日期区间：只比到天，字段可能是 '2026-09-01 10:22:31' */
export function inDate (v, from, to) {
  const d = (v || '').slice(0, 10)
  if (!d) return !from && !to ? true : false
  if (from && d < from) return false
  if (to && d > to) return false
  return true
}

/** 从已取回的列表里去重出一列值，下拉没有后端接口时用这个兜底 */
export function uniqOptions (rows, field, labelPrefix = '') {
  const seen = []
  for (const r of rows || []) {
    const v = r[field]
    if (v && seen.indexOf(v) < 0) seen.push(v)
  }
  return [{ value: '', label: '全部' }].concat(
    seen.map((v) => ({ value: v, label: (labelPrefix || '') + v }))
  )
}

/** 生效条件的小标签：[{ k, t }]，点 × 时按 k 清掉 */
export function activeKeys (filters, defs) {
  const out = []
  for (const d of defs) {
    const v = filters[d.key]
    if (d.type === 'date') {
      if (v && (v.from || v.to)) {
        out.push({ k: d.key, t: `${d.label}：${v.from || '不限'} 至 ${v.to || '不限'}` })
      }
    } else if (v) {
      out.push({ k: d.key, t: `${d.label}：${d.text ? d.text(v) : v}` })
    }
  }
  return out
}
