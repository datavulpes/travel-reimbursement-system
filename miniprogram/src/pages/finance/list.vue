<template>
  <view class="page page-tab">
    <view class="tabs">
      <text :class="['tab', tab === 'pending' ? 'on' : '']" @click="switchTab('pending')">待复核</text>
      <text :class="['tab', tab === 'payable' ? 'on' : '']" @click="switchTab('payable')">待付款</text>
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
      <text v-else class="fcount">共 {{ L.total }} 单</text>
    </view>

    <FilterDrawer
      v-if="drawerShow" :groups="groups" :values="L.filters"
      local-note="关键字交给后端查；预检结论、异常票、实付金额、提交日期、排序在已取回的报销单里筛。待复核 / 待付款由上面的 tab 决定，不重复筛"
      @close="drawerShow = false" @apply="onApply" />

    <view v-if="L.total === 0 && !loading" class="empty">
      <text>{{ activeCnt ? '没有符合条件的报销单，点上方的 × 放宽条件' : (tab === 'pending' ? '没有待复核的报销单' : '没有待付款的报销单') }}</text>
      <text v-if="!activeCnt" class="empty-sub">演示库里 12 张报销单都已付完。要看复核流程，用员工账号新提一张报销单</text>
    </view>

    <view v-for="r in L.pagedRows" :key="r.reportNo" class="card" @click="open(r.reportNo)">
      <view class="row1">
        <text class="no">{{ r.reportNo }}</text>
        <text :class="['tag', tagOf(EXPENSE_STATUS, r.status)]">{{ textOf(EXPENSE_STATUS, r.status) }}</text>
      </view>
      <text class="reason">{{ r.reason || '—' }}</text>
      <view class="row2">
        <text class="meta">{{ r.applicant }} · {{ r.deptName }} · {{ r.invoiceCnt }} 张票<text v-if="r.issueCount"> · {{ r.issueCount }} 个异常</text></text>
        <text class="money">¥ {{ fmtMoney(r.netAmount) }}</text>
      </view>
      <view class="row2">
        <text class="meta">{{ r.applyNo }}<text v-if="r.precheckResult"> · {{ textOf(PRECHECK_RESULT, r.precheckResult) }}</text></text>
        <text class="note">{{ r.createdAt || '—' }}</text>
      </view>
      <text v-if="r.totalAmount !== r.netAmount" class="note">票面 ¥ {{ fmtMoney(r.totalAmount) }}，冲抵借款 ¥ {{ fmtMoney(r.offsetAmount) }}</text>
    </view>

    <Pager
      v-if="!loading && L.total > 0"
      :total="L.total" :page-num="L.pageNum" :page-size="L.pageSize"
      @page="L.onPage" @size="L.onSize" />

    <view v-if="loading" class="empty"><text>加载中…</text></view>

    <TabBar current="finance" />
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onLoad, onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { financePending, financePayable } from '../../api'
import { EXPENSE_STATUS, PRECHECK_RESULT, tagOf, textOf, fmtMoney } from '../../utils/dict'
import { usePagedList, inBand, inDate, uniqOptions, activeKeys } from '../../utils/paged'
import FilterDrawer from '../../components/FilterDrawer.vue'
import Pager from '../../components/Pager.vue'
import TabBar from '../../components/TabBar.vue'
import { hasTab } from '../../utils/tabs'

const tab = ref('pending')
const loading = ref(false)
const drawerShow = ref(false)

const BAND = {
  '': '不限',
  '0-500': '5 百以下',
  '500-2000': '5 百 – 2 千',
  '2000-5000': '2 千 – 5 千',
  '5000-0': '5 千以上'
}
const SORT = { '': '最新在前', oldest: '最早在前', amtDesc: '实付高 → 低', amtAsc: '实付低 → 高' }
const ISSUE = { '': '不限', has: '有异常票', none: '没有异常' }

// 后端只认 keyword（复用 ApplyQuery），其余在已取回的列表里筛
function localFilter (list, f) {
  let out = list.slice()
  if (f.applicant) out = out.filter((r) => r.applicant === f.applicant)
  if (f.precheck) out = out.filter((r) => r.precheckResult === f.precheck)
  if (f.issue === 'has') out = out.filter((r) => (Number(r.issueCount) || 0) > 0)
  if (f.issue === 'none') out = out.filter((r) => (Number(r.issueCount) || 0) === 0)
  if (f.band) out = out.filter((r) => inBand(Number(r.netAmount) || 0, f.band))
  if (f.date.from || f.date.to) out = out.filter((r) => inDate(r.createdAt, f.date.from, f.date.to))
  const byAmt = (a, b) => (Number(a.netAmount) || 0) - (Number(b.netAmount) || 0)
  if (f.sort === 'oldest') out = out.sort((a, b) => (a.createdAt || '').localeCompare(b.createdAt || ''))
  if (f.sort === 'amtDesc') out = out.sort((a, b) => byAmt(b, a))
  if (f.sort === 'amtAsc') out = out.sort(byAmt)
  return out
}

const L = usePagedList(localFilter, {
  keyword: '', applicant: '', precheck: '', issue: '', band: '',
  date: { from: '', to: '' }, sort: ''
})

const groups = computed(() => [
  { key: 'keyword', label: '关键字（报销单号 / 申请单号）', type: 'text', placeholder: '报销单号或申请单号' },
  { key: 'applicant', label: '报销人', type: 'select', options: uniqOptions(L.all, 'applicant') },
  { key: 'precheck', label: '预检结论', type: 'select', options: [
    { value: '', label: '全部' },
    { value: 'PASS', label: '预检通过' },
    { value: 'WARN', label: '有提醒' },
    { value: 'FORCE', label: '需填强制说明' }
  ] },
  { key: 'issue', label: '异常票', type: 'select', options: Object.keys(ISSUE).map((k) => ({ value: k, label: ISSUE[k] })) },
  { key: 'band', label: '实付金额', type: 'select', options: Object.keys(BAND).map((k) => ({ value: k, label: BAND[k] })) },
  { key: 'date', label: '提交日期', type: 'date' },
  { key: 'sort', label: '排序', type: 'select', options: Object.keys(SORT).map((k) => ({ value: k, label: SORT[k] })) }
])

const chips = computed(() => activeKeys(L.filters, [
  { key: 'keyword', label: '关键字' },
  { key: 'applicant', label: '报销人' },
  { key: 'precheck', label: '预检', text: (v) => textOf(PRECHECK_RESULT, v) },
  { key: 'issue', label: '异常票', text: (v) => ISSUE[v] || v },
  { key: 'band', label: '金额', text: (v) => BAND[v] || v },
  { key: 'date', label: '日期', type: 'date' },
  { key: 'sort', label: '排序', text: (v) => SORT[v] || v }
]))
const activeCnt = computed(() => chips.value.length)

const FETCH_SIZE = 100
let backendKey = ''

async function load (force) {
  const key = `${tab.value}|${L.filters.keyword || ''}`
  if (!force && key === backendKey && L.all.length > 0) return
  loading.value = true
  try {
    const params = { pageNum: 1, pageSize: FETCH_SIZE }
    if (L.filters.keyword) params.keyword = L.filters.keyword
    const page = tab.value === 'pending'
      ? await financePending(params)
      : await financePayable(params)
    L.setList(page.list || [])
    backendKey = key
  } catch (e) { /* toast 已弹 */ }
  loading.value = false
  uni.stopPullDownRefresh()
}

function onApply (f) {
  L.filters = f
  drawerShow.value = false
  L.resetPaging()
  load()
}
function clearChip (k) {
  const next = { ...L.filters }
  if (k === 'date') next.date = { from: '', to: '' }
  else next[k] = ''
  L.filters = next
  L.resetPaging()
  load()
}
const switchTab = (t) => {
  if (t === tab.value) return
  tab.value = t
  L.resetPaging()
  load(true)
}

// 工作台按待办类型直投进来：?tab=pending 待复核 / ?tab=payable 待付款
onLoad((o) => { if (o && o.tab) tab.value = o.tab })
onShow(() => {
  // 财务这一页只挂财务角色的导航，别的角色进来送回工作台
  if (!hasTab('finance')) {
    uni.showToast({ title: '财务处理是财务的功能', icon: 'none' })
    uni.redirectTo({ url: '/pages/home/home' })
    return
  }
  load(true)
})
onPullDownRefresh(() => load(true))

const open = (no) => uni.navigateTo({ url: `/pages/finance/detail?reportNo=${encodeURIComponent(no)}` })
</script>

<style>
.tabs { display: flex; background: #fff; border-radius: 16rpx; padding: 8rpx; margin-bottom: 24rpx; }
.tab { flex: 1; text-align: center; font-size: 28rpx; color: #5f6773; padding: 16rpx 0; border-radius: 12rpx; }
.tab.on { background: #2f6fed; color: #fff; }

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
.row2 { display: flex; justify-content: space-between; margin-top: 12rpx; }
.no { font-size: 26rpx; color: #8a919c; }
.reason { display: block; margin-top: 14rpx; font-size: 30rpx; color: #1f2328; }
.meta { font-size: 24rpx; color: #8a919c; }
.money { font-size: 28rpx; color: #d83931; font-weight: 500; }
.note { display: block; margin-top: 8rpx; font-size: 24rpx; color: #8a919c; }
.tag { font-size: 22rpx; padding: 4rpx 16rpx; border-radius: 8rpx; }
.tag-green { background: #e8f5e9; color: #2e7d32; }
.tag-blue { background: #eef3fe; color: #2f6fed; }
.tag-amber { background: #fff7e6; color: #ad6800; }
.tag-red { background: #fdecec; color: #d83931; }
.tag-gray { background: #f0f1f3; color: #5f6773; }
.empty { text-align: center; color: #9aa2ad; font-size: 26rpx; padding: 80rpx 0; }
.empty-sub { display: block; margin-top: 16rpx; font-size: 24rpx; color: #b0b7c0; padding: 0 60rpx; }
</style>
