<template>
  <view class="page">
    <view class="head">
      <text class="head-title">我的报销单</text>
    </view>

    <view class="fbar">
      <view class="fbtn" @click="drawerShow = true">
        <text class="fbtn-ic">☰</text>
        <text class="fbtn-t">筛选</text>
        <text v-if="activeCnt" class="fbadge">{{ activeCnt }}</text>
      </view>
      <view v-if="activeCnt" class="fchips">
        <view v-for="c in chips" :key="c.k" class="fchip" @click="clearChip(c.k)">
          <text class="fchip-t">{{ c.t }}</text>
          <text class="fchip-x">×</text>
        </view>
      </view>
      <text v-else class="fcount">共 {{ total }} 单</text>
    </view>

    <FilterDrawer
      v-if="drawerShow" :groups="groups" :values="filters"
      local-note="状态与关键字交给后端查；预检结论、实付金额、提交日期、排序在已取回的报销单里筛，超出这个范围的单子不会出现在结果里"
      @close="drawerShow = false" @apply="onApply" />

    <view v-if="total === 0 && !loading" class="empty">
      <text>{{ activeCnt ? '没有符合条件的报销单，点上方的 × 放宽条件' : '还没有报销单。申请单审批通过、票据挂好之后，在单据详情里点「去报销」' }}</text>
    </view>

    <view v-for="r in pagedRows" :key="r.reportNo" class="card" @click="open(r.reportNo)">
      <view class="row1">
        <text class="no">{{ r.reportNo }}</text>
        <text :class="['tag', tagOf(EXPENSE_STATUS, r.status)]">{{ textOf(EXPENSE_STATUS, r.status) }}</text>
      </view>
      <text class="reason">{{ r.reason || '—' }}</text>
      <view class="row2">
        <text class="meta">{{ r.applyNo }} · {{ r.invoiceCnt }} 张票<text v-if="r.issueCount"> · {{ r.issueCount }} 个异常</text><text v-if="r.precheckResult"> · {{ textOf(PRECHECK_RESULT, r.precheckResult) }}</text></text>
        <text class="money">¥ {{ fmtMoney(r.netAmount) }}</text>
      </view>
      <text class="note">{{ r.createdAt || '—' }}</text>
      <text v-if="r.totalAmount !== r.netAmount" class="note">票面 ¥ {{ fmtMoney(r.totalAmount) }}，冲抵借款 ¥ {{ fmtMoney(r.offsetAmount) }}</text>
    </view>

    <Pager
      v-if="!loading && total > 0"
      :total="total" :page-num="pageNum" :page-size="pageSize"
      @page="onPage" @size="onSize" />

    <view v-if="loading" class="empty"><text>加载中…</text></view>

    <TabBar current="expense" />
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { expensePage } from '../../api'
import { EXPENSE_STATUS, PRECHECK_RESULT, tagOf, textOf, fmtMoney } from '../../utils/dict'
import FilterDrawer from '../../components/FilterDrawer.vue'
import Pager from '../../components/Pager.vue'
import TabBar from '../../components/TabBar.vue'
import { isEmployee } from '../../utils/session'

const all = ref([])
const filtered = computed(() => localFilter(all.value))
const total = computed(() => filtered.value.length)
const pageNum = ref(1)
const pageSize = ref(10)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
// 页码可能因为筛完条数变少而越界，取的时候夹一下
const pagedRows = computed(() => {
  const p = Math.min(pageNum.value, totalPages.value)
  const start = (p - 1) * pageSize.value
  return filtered.value.slice(start, start + pageSize.value)
})
const loading = ref(false)
const drawerShow = ref(false)

// status / keyword 走后端（复用 ApplyQuery，只有这两项），其余在已取回的列表里筛
const filters = ref({
  status: '', keyword: '', precheck: '', band: '',
  date: { from: '', to: '' }, sort: ''
})

const BAND = {
  '': '不限',
  '0-500': '5 百以下',
  '500-2000': '5 百 – 2 千',
  '2000-5000': '2 千 – 5 千',
  '5000-0': '5 千以上'
}
const SORT = { '': '最新在前', oldest: '最早在前', amtDesc: '实付高 → 低', amtAsc: '实付低 → 高' }

const groups = [
  { key: 'keyword', label: '关键字（报销单号 / 申请单号）', type: 'text', placeholder: '报销单号或申请单号' },
  { key: 'status', label: '状态', type: 'select', options: [
    { value: '', label: '全部' },
    { value: 'PENDING', label: '待复核' },
    { value: 'APPROVED', label: '待付款' },
    { value: 'PASS_WITH_ISSUE', label: '带异常通过' },
    { value: 'RETURNED', label: '已退回' },
    { value: 'PAID', label: '已付款' }
  ] },
  { key: 'precheck', label: '预检结论', type: 'select', options: [
    { value: '', label: '全部' },
    { value: 'PASS', label: '预检通过' },
    { value: 'WARN', label: '有提醒' },
    { value: 'FORCE', label: '需填强制说明' }
  ] },
  { key: 'band', label: '实付金额', type: 'select', options: Object.keys(BAND).map((k) => ({ value: k, label: BAND[k] })) },
  { key: 'date', label: '提交日期', type: 'date' },
  { key: 'sort', label: '排序', type: 'select', options: Object.keys(SORT).map((k) => ({ value: k, label: SORT[k] })) }
]

const activeCnt = computed(() => {
  const f = filters.value
  let n = 0
  if (f.status) n++
  if (f.keyword) n++
  if (f.precheck) n++
  if (f.band) n++
  if (f.date.from || f.date.to) n++
  if (f.sort) n++
  return n
})
const chips = computed(() => {
  const f = filters.value
  const out = []
  if (f.status) out.push({ k: 'status', t: '状态：' + textOf(EXPENSE_STATUS, f.status) })
  if (f.keyword) out.push({ k: 'keyword', t: '「' + f.keyword + '」' })
  if (f.precheck) out.push({ k: 'precheck', t: '预检：' + textOf(PRECHECK_RESULT, f.precheck) })
  if (f.band) out.push({ k: 'band', t: '实付：' + (BAND[f.band] || f.band) })
  if (f.date.from || f.date.to) {
    out.push({ k: 'date', t: (f.date.from || '不限') + ' 至 ' + (f.date.to || '不限') })
  }
  if (f.sort) out.push({ k: 'sort', t: SORT[f.sort] || f.sort })
  return out
})

function onApply (f) {
  filters.value = f
  drawerShow.value = false
  pageNum.value = 1
  load()
}
function clearChip (k) {
  const next = { ...filters.value }
  if (k === 'date') next.date = { from: '', to: '' }
  else next[k] = ''
  filters.value = next
  pageNum.value = 1
  load()
}
function onPage (n) {
  pageNum.value = n
}
function onSize (n) {
  pageSize.value = n
  pageNum.value = 1
}

function inBand (v, band) {
  if (!band) return true
  const p = String(band).split('-')
  const lo = Number(p[0])
  const hi = Number(p[1])
  return v >= lo && (hi > 0 ? v < hi : true)
}

function localFilter (list) {
  const f = filters.value
  let out = list.slice()
  if (f.precheck) out = out.filter((r) => r.precheckResult === f.precheck)
  if (f.band) out = out.filter((r) => inBand(Number(r.netAmount) || 0, f.band))
  if (f.date.from) out = out.filter((r) => (r.createdAt || '').slice(0, 10) >= f.date.from)
  if (f.date.to) out = out.filter((r) => (r.createdAt || '').slice(0, 10) <= f.date.to)
  const byAmt = (a, b) => (Number(a.netAmount) || 0) - (Number(b.netAmount) || 0)
  if (f.sort === 'oldest') out = out.slice().sort((a, b) => (a.createdAt || '').localeCompare(b.createdAt || ''))
  if (f.sort === 'amtDesc') out = out.slice().sort((a, b) => byAmt(b, a))
  if (f.sort === 'amtAsc') out = out.slice().sort(byAmt)
  return out
}

// 后端一次最多给 100 条，翻完为止。只有「状态 / 关键字」变了才需要重新拉，
// 其余条件是本地筛，改它们不请求
const FETCH_SIZE = 100
const FETCH_MAX_PAGE = 10
let backendKey = ''

async function load (force) {
  const key = (filters.value.status || '') + '|' + (filters.value.keyword || '')
  if (!force && key === backendKey && all.value.length > 0) return
  loading.value = true
  try {
    const out = []
    for (let n = 1; n <= FETCH_MAX_PAGE; n++) {
      const params = { pageNum: n, pageSize: FETCH_SIZE }
      if (filters.value.status) params.status = filters.value.status
      if (filters.value.keyword) params.keyword = filters.value.keyword
      const page = await expensePage(params)
      const list = page.list || []
      out.push(...list)
      const cnt = Number(page.total) || 0
      if (list.length === 0 || out.length >= cnt || list.length < FETCH_SIZE) break
    }
    all.value = out
    backendKey = key
  } catch (e) { /* toast 已弹 */ }
  loading.value = false
  uni.stopPullDownRefresh()
}

onShow(() => {
  // 这一页只挂在员工的导航上，别的角色误闯直接送回工作台
  if (!isEmployee()) {
    uni.showToast({ title: '报销是员工的功能', icon: 'none' })
    uni.redirectTo({ url: '/pages/home/home' })
    return
  }
  load(true)
})
onPullDownRefresh(() => load(true))

const open = (no) => uni.navigateTo({ url: `/pages/expense/detail?reportNo=${encodeURIComponent(no)}` })
</script>

<style>
.page { min-height: 100vh; padding: 32rpx 32rpx 200rpx; box-sizing: border-box;
  background: linear-gradient(180deg, #e2ecfb 0%, #eef4fc 40%, #f5f8fd 100%); }
.head { margin-bottom: 20rpx; }
.head-title { font-size: 40rpx; font-weight: 600; color: #1f2328; }

.fbar { display: flex; align-items: center; flex-wrap: wrap; gap: 16rpx; margin-bottom: 24rpx; }
.fbtn { display: flex; align-items: center; background: #fff; border-radius: 12rpx;
  padding: 14rpx 24rpx; position: relative; }
.fbtn-ic { font-size: 30rpx; color: #2f6fed; margin-right: 10rpx; }
.fbtn-t { font-size: 26rpx; color: #1f2328; }
.fbadge { position: absolute; top: -12rpx; right: -12rpx; min-width: 32rpx; height: 32rpx;
  border-radius: 16rpx; background: #d83931; color: #fff; font-size: 20rpx;
  display: flex; align-items: center; justify-content: center; padding: 0 8rpx; box-sizing: border-box; }
.fchips { display: flex; flex-wrap: wrap; gap: 12rpx; flex: 1; }
.fchip { display: flex; align-items: center; background: #eef3fe; color: #2f6fed;
  border-radius: 999rpx; padding: 8rpx 20rpx; font-size: 22rpx; }
.fchip-x { margin-left: 8rpx; font-size: 26rpx; }
.fcount { font-size: 24rpx; color: #8a919c; }

.card { background: #fff; border-radius: 16rpx; padding: 28rpx 32rpx; margin-bottom: 24rpx; }
.row1 { display: flex; justify-content: space-between; align-items: center; }
.no { font-size: 26rpx; color: #8a919c; }
.reason { display: block; margin-top: 14rpx; font-size: 30rpx; color: #1f2328; }
.row2 { display: flex; justify-content: space-between; margin-top: 16rpx; }
.meta { font-size: 26rpx; color: #8a919c; }
.money { font-size: 28rpx; color: #d83931; font-weight: 500; }
.note { display: block; margin-top: 8rpx; font-size: 24rpx; color: #b0b7c0; }
.tag { font-size: 22rpx; padding: 4rpx 16rpx; border-radius: 8rpx; }
.tag-green { background: #e8f5e9; color: #2e7d32; }
.tag-blue { background: #eef3fe; color: #2f6fed; }
.tag-amber { background: #fff7e6; color: #ad6800; }
.tag-red { background: #fdecec; color: #d83931; }
.tag-gray { background: #f0f1f3; color: #5f6773; }
.empty { text-align: center; color: #9aa2ad; font-size: 26rpx; padding: 120rpx 0; }
</style>
