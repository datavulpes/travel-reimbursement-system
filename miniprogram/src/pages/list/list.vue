<template>
  <view class="page">
    <view class="head">
      <text class="head-title">{{ pageTitle }}</text>
      <text class="head-user">{{ realName }}</text>
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
      local-note="状态与关键字交给后端查；对象、差标结论、金额档位、提交日期、排序在已取回的申请单里筛，超出这个范围的单子不会出现在结果里"
      @close="drawerShow = false" @apply="onApply" />

    <view v-if="total === 0 && !loading" class="empty">
      <text>{{ activeCnt ? '没有符合条件的单子，点上方的 × 放宽条件' : '还没有申请单，点右下角 + 新建' }}</text>
    </view>

    <view v-for="r in pagedRows" :key="r.applyNo" class="card" @click="open(r.applyNo)">
      <view class="row1">
        <text class="no">{{ r.applyNo }}</text>
        <text :class="['tag', statusCls(r.status)]">{{ statusText(r.status) }}</text>
      </view>
      <text class="reason">{{ r.reason || '—' }}</text>
      <view class="row2">
        <text class="meta">{{ r.partnerName || '—' }}<text v-if="r.standardResult"> · {{ textOf(STANDARD_RESULT, r.standardResult) }}</text></text>
        <text class="money">¥ {{ fmtMoney(r.estTotal) }}</text>
      </view>
      <text class="note">{{ r.createdAt || '—' }}</text>
    </view>

    <Pager
      v-if="!loading && total > 0"
      :total="total" :page-num="pageNum" :page-size="pageSize"
      @page="onPage" @size="onSize" />

    <view v-if="loading" class="empty"><text>加载中…</text></view>

    <!-- 只有员工能新建申请单，审批人进来是看范围的 -->
    <view v-if="isEmployee()" class="fab" @click="goForm">+</view>

    <TabBar current="apply" />
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { applyPage } from '../../api'
import { APPLY_STATUS, STANDARD_RESULT, textOf, fmtMoney } from '../../utils/dict'
import FilterDrawer from '../../components/FilterDrawer.vue'
import Pager from '../../components/Pager.vue'
import TabBar from '../../components/TabBar.vue'
import { isEmployee } from '../../utils/session'

// 审批角色也会进这一页（看自己可见范围），标题跟着角色走，别写成「我的」
const pageTitle = computed(() => (isEmployee() ? '我的申请单' : '申请单'))

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
const realName = ref('')
const drawerShow = ref(false)

// status / keyword 走后端（ApplyQuery 只认这两个），其余在已取回的列表里筛
const filters = ref({
  status: '', keyword: '', partner: '', standard: '', band: '',
  date: { from: '', to: '' }, sort: ''
})

const BAND = {
  '': '不限',
  '0-1000': '1 千以下',
  '1000-5000': '1 千 – 5 千',
  '5000-10000': '5 千 – 1 万',
  '10000-0': '1 万以上'
}
const SORT = { '': '最新在前', oldest: '最早在前', amtDesc: '金额高 → 低', amtAsc: '金额低 → 高' }

const statusText = (s) => APPLY_STATUS[s] || s
const statusCls = (s) => ({
  DRAFT: 'tag-gray', DEPT_REVIEW: 'tag-amber', FINAL_REVIEW: 'tag-amber',
  APPROVED: 'tag-green', REJECTED: 'tag-red', CLOSED: 'tag-gray'
}[s] || 'tag-gray')

// 往来对象从已取回的单子里去重，库里没有这个对象的下拉接口
const partnerOptions = computed(() => {
  const seen = []
  for (const r of all.value) {
    const n = r.partnerName
    if (n && seen.indexOf(n) < 0) seen.push(n)
  }
  return [{ value: '', label: '全部' }].concat(seen.map((n) => ({ value: n, label: n })))
})

const groups = computed(() => [
  { key: 'keyword', label: '关键字（单号 / 事由 / 对象）', type: 'text', placeholder: '单号或事由关键字' },
  { key: 'status', label: '状态', type: 'select', options: [
    { value: '', label: '全部' },
    { value: 'DRAFT', label: '草稿' },
    { value: 'DEPT_REVIEW', label: '部门审批' },
    { value: 'FINAL_REVIEW', label: '复审' },
    { value: 'APPROVED', label: '已通过' },
    { value: 'REJECTED', label: '已驳回' },
    { value: 'CLOSED', label: '已结案' }
  ] },
  { key: 'partner', label: '往来对象', type: 'select', options: partnerOptions.value },
  { key: 'standard', label: '差标结论', type: 'select', options: [
    { value: '', label: '全部' },
    { value: 'NORMAL', label: '符合差标' },
    { value: 'HIGH', label: '接近上限' },
    { value: 'EXCEED', label: '超出差标' }
  ] },
  { key: 'band', label: '预估金额', type: 'select', options: Object.keys(BAND).map((k) => ({ value: k, label: BAND[k] })) },
  { key: 'date', label: '提交日期', type: 'date' },
  { key: 'sort', label: '排序', type: 'select', options: Object.keys(SORT).map((k) => ({ value: k, label: SORT[k] })) }
])

const activeCnt = computed(() => {
  const f = filters.value
  let n = 0
  if (f.status) n++
  if (f.keyword) n++
  if (f.partner) n++
  if (f.standard) n++
  if (f.band) n++
  if (f.date.from || f.date.to) n++
  if (f.sort) n++
  return n
})
const chips = computed(() => {
  const f = filters.value
  const out = []
  if (f.status) out.push({ k: 'status', t: '状态：' + (APPLY_STATUS[f.status] || f.status) })
  if (f.keyword) out.push({ k: 'keyword', t: '「' + f.keyword + '」' })
  if (f.partner) out.push({ k: 'partner', t: '对象：' + f.partner })
  if (f.standard) out.push({ k: 'standard', t: '差标：' + textOf(STANDARD_RESULT, f.standard) })
  if (f.band) out.push({ k: 'band', t: '金额：' + (BAND[f.band] || f.band) })
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
  if (f.partner) out = out.filter((r) => r.partnerName === f.partner)
  if (f.standard) out = out.filter((r) => r.standardResult === f.standard)
  if (f.band) out = out.filter((r) => inBand(Number(r.estTotal) || 0, f.band))
  if (f.date.from) out = out.filter((r) => (r.createdAt || '').slice(0, 10) >= f.date.from)
  if (f.date.to) out = out.filter((r) => (r.createdAt || '').slice(0, 10) <= f.date.to)
  const byAmt = (a, b) => (Number(a.estTotal) || 0) - (Number(b.estTotal) || 0)
  if (f.sort === 'oldest') out = out.slice().sort((a, b) => (a.createdAt || '').localeCompare(b.createdAt || ''))
  if (f.sort === 'amtDesc') out = out.slice().sort((a, b) => byAmt(b, a))
  if (f.sort === 'amtAsc') out = out.slice().sort(byAmt)
  return out
}

// 后端一次最多给 100 条，翻完为止（演示库远不到 1000，够用）。
// 本地筛完再切页，所以只有「状态 / 关键字」这两个后端条件变了才需要重新拉
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
      const page = await applyPage(params)
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
  const u = uni.getStorageSync('travel_user')
  if (u) realName.value = (JSON.parse(u).realName) || ''
  uni.setNavigationBarTitle({ title: pageTitle.value })
  if (!uni.getStorageSync('travel_token')) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  // 工作台待办跳过来时带的筛选（tabBar 页没法用 query，走 storage），读完即清
  const st = uni.getStorageSync('travel_apply_status') || ''
  uni.removeStorageSync('travel_apply_status')
  if (st) filters.value = { ...filters.value, status: st }
  load(true)
})
onPullDownRefresh(() => load(true))

const open = (no) => uni.navigateTo({ url: `/pages/detail/detail?applyNo=${encodeURIComponent(no)}` })
const goForm = () => uni.navigateTo({ url: '/pages/form/form' })
</script>

<style>
.page { min-height: 100vh; padding: 32rpx 32rpx 200rpx; box-sizing: border-box;
  background: linear-gradient(180deg, #e2ecfb 0%, #eef4fc 40%, #f5f8fd 100%); }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20rpx; }
.head-title { font-size: 40rpx; font-weight: 600; color: #1f2328; }
.head-user { font-size: 26rpx; color: #8a919c; }

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
.tag-amber { background: #fff7e6; color: #ad6800; }
.tag-red { background: #fdecec; color: #d83931; }
.tag-gray { background: #f0f1f3; color: #5f6773; }
.empty { text-align: center; color: #9aa2ad; font-size: 26rpx; padding: 120rpx 0; }
/* 浮标抬到自研 tabBar 上方，不然被底部导航压住 */
.fab { position: fixed; right: 48rpx; bottom: 170rpx; width: 108rpx; height: 108rpx; border-radius: 50%;
  background: #2f6fed; color: #fff; font-size: 60rpx; display: flex; align-items: center; justify-content: center;
  box-shadow: 0 8rpx 24rpx rgba(47, 111, 237, 0.35); }
</style>
