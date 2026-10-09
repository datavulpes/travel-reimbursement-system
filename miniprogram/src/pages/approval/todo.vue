<template>
  <view class="page page-tab">
    <view class="tabs">
      <text :class="['tab', tab === 'todo' ? 'on' : '']" @click="switchTab('todo')">{{ isManager() ? '待我复审' : '待我审批' }}</text>
      <text :class="['tab', tab === 'done' ? 'on' : '']" @click="switchTab('done')">已办</text>
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
      local-note="关键字与状态交给后端查；申请人、部门、评价、金额档位、提交日期、排序在已取回的单子里筛"
      @close="drawerShow = false" @apply="onApply" />

    <view v-if="L.total === 0 && !loading" class="empty">
      <text>{{ activeCnt ? '没有符合条件的单子，点上方的 × 放宽条件' : (tab === 'todo' ? '没有待办' : '还没处理过单据') }}</text>
    </view>

    <view v-for="r in L.pagedRows" :key="r.applyNo" class="card" @click="open(r.applyNo)">
      <view class="row1">
        <text class="no">{{ r.applyNo }}</text>
        <text :class="['tag', statusCls(r.status)]">{{ statusText(r.status) }}</text>
      </view>
      <text class="reason">{{ r.reason || '—' }}</text>
      <view class="row2">
        <text class="meta">{{ r.applicant }} · {{ r.deptName }} · {{ r.partnerName || '—' }}</text>
        <text class="money">¥ {{ fmtMoney(r.estTotal) }}</text>
      </view>
      <view class="row2">
        <text class="meta">同类参考 ¥ {{ fmtMoney(r.avgAmount) }}<text v-if="r.minAmount != null">（{{ fmtMoney(r.minAmount) }} ~ {{ fmtMoney(r.maxAmount) }}）</text></text>
        <text class="meta">评价 {{ r.evalLevel || '—' }}</text>
      </view>
      <text class="note">{{ r.createdAt || '—' }}</text>
    </view>

    <Pager
      v-if="!loading && L.total > 0"
      :total="L.total" :page-num="L.pageNum" :page-size="L.pageSize"
      @page="L.onPage" @size="L.onSize" />

    <view v-if="loading" class="empty"><text>加载中…</text></view>

    <TabBar current="approval" />
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { approvalTodo } from '../../api'
import { APPLY_STATUS, EVAL_LEVEL, fmtMoney } from '../../utils/dict'
import { usePagedList, inBand, inDate, uniqOptions, activeKeys } from '../../utils/paged'
import FilterDrawer from '../../components/FilterDrawer.vue'
import Pager from '../../components/Pager.vue'
import TabBar from '../../components/TabBar.vue'
import { hasTab } from '../../utils/tabs'
import { isManager } from '../../utils/session'

const tab = ref('todo')
const loading = ref(false)
const drawerShow = ref(false)

const BAND = {
  '': '不限',
  '0-2000': '2 千以下',
  '2000-5000': '2 千 – 5 千',
  '5000-10000': '5 千 – 1 万',
  '10000-0': '1 万以上'
}
const SORT = { '': '最新在前', oldest: '最早在前', amtDesc: '金额高 → 低', amtAsc: '金额低 → 高' }

// 后端只认 listType / status / keyword，其余在已取回的列表里筛
function localFilter (list, f) {
  let out = list.slice()
  if (f.applicant) out = out.filter((r) => r.applicant === f.applicant)
  if (f.dept) out = out.filter((r) => r.deptName === f.dept)
  if (f.evalLevel) out = out.filter((r) => r.evalLevel === f.evalLevel)
  if (f.band) out = out.filter((r) => inBand(Number(r.estTotal) || 0, f.band))
  if (f.date.from || f.date.to) out = out.filter((r) => inDate(r.createdAt, f.date.from, f.date.to))
  const byAmt = (a, b) => (Number(a.estTotal) || 0) - (Number(b.estTotal) || 0)
  if (f.sort === 'oldest') out = out.sort((a, b) => (a.createdAt || '').localeCompare(b.createdAt || ''))
  if (f.sort === 'amtDesc') out = out.sort((a, b) => byAmt(b, a))
  if (f.sort === 'amtAsc') out = out.sort(byAmt)
  return out
}

const L = usePagedList(localFilter, {
  keyword: '', status: '', applicant: '', dept: '', evalLevel: '', band: '',
  date: { from: '', to: '' }, sort: ''
})

const statusText = (s) => APPLY_STATUS[s] || s || '—'
const statusCls = (s) => ({
  DRAFT: 'tag-gray', DEPT_REVIEW: 'tag-amber', FINAL_REVIEW: 'tag-amber',
  APPROVED: 'tag-green', REJECTED: 'tag-red', CLOSED: 'tag-gray'
}[s] || 'tag-gray')

// 待办里只可能出现这两个状态，已办里是批完的那三种
const statusOptions = computed(() => (tab.value === 'todo'
  ? [{ value: '', label: '全部' }, { value: 'DEPT_REVIEW', label: '部门审批' }, { value: 'FINAL_REVIEW', label: '复审' }]
  : [{ value: '', label: '全部' }, { value: 'APPROVED', label: '已通过' }, { value: 'REJECTED', label: '已驳回' }, { value: 'CLOSED', label: '已结案' }]))

const groups = computed(() => [
  { key: 'keyword', label: '关键字（单号 / 事由 / 对象）', type: 'text', placeholder: '单号、事由或对象名称' },
  { key: 'status', label: '状态', type: 'select', options: statusOptions.value },
  { key: 'applicant', label: '申请人', type: 'select', options: uniqOptions(L.all, 'applicant') },
  { key: 'dept', label: '部门', type: 'select', options: uniqOptions(L.all, 'deptName') },
  { key: 'evalLevel', label: '评价等级', type: 'select', options: [
    { value: '', label: '全部' },
    { value: 'A', label: 'A 优先' }, { value: 'B', label: 'B 正常' },
    { value: 'C', label: 'C 观察' }, { value: 'D', label: 'D 暂缓' }
  ] },
  { key: 'band', label: '预估金额', type: 'select', options: Object.keys(BAND).map((k) => ({ value: k, label: BAND[k] })) },
  { key: 'date', label: '提交日期', type: 'date' },
  { key: 'sort', label: '排序', type: 'select', options: Object.keys(SORT).map((k) => ({ value: k, label: SORT[k] })) }
])

const chips = computed(() => activeKeys(L.filters, [
  { key: 'keyword', label: '关键字', type: 'text' },
  { key: 'status', label: '状态', text: (v) => APPLY_STATUS[v] || v },
  { key: 'applicant', label: '申请人' },
  { key: 'dept', label: '部门' },
  { key: 'evalLevel', label: '评价', text: (v) => EVAL_LEVEL[v] || v },
  { key: 'band', label: '金额', text: (v) => BAND[v] || v },
  { key: 'date', label: '日期', type: 'date' },
  { key: 'sort', label: '排序', text: (v) => SORT[v] || v }
]))
const activeCnt = computed(() => chips.value.length)

const FETCH_SIZE = 100
let backendKey = ''

async function load (force) {
  const key = `${tab.value}|${L.filters.status || ''}|${L.filters.keyword || ''}`
  if (!force && key === backendKey && L.all.length > 0) return
  loading.value = true
  try {
    const params = { listType: tab.value, pageNum: 1, pageSize: FETCH_SIZE }
    if (L.filters.status) params.status = L.filters.status
    if (L.filters.keyword) params.keyword = L.filters.keyword
    const page = await approvalTodo(params)
    L.setList(page.list || [])
    backendKey = key
  } catch (e) { /* toast 已弹 */ }
  loading.value = false
  uni.stopPullDownRefresh()
}

function onApply (f) {
  // 换 tab 后状态选项整组换掉，旧的选中值留着会筛出空列表
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
  L.filters.status = ''
  L.resetPaging()
  load(true)
}

onShow(() => {
  // 只有主管和管理层的导航里有这一页，别的角色手输路径进来就送回去
  if (!hasTab('approval')) {
    uni.showToast({ title: '审批不是你这个角色的功能', icon: 'none' })
    uni.redirectTo({ url: '/pages/home/home' })
    return
  }
  // 主管走部门审批、管理层走复审，同一个页面，说法不一样
  uni.setNavigationBarTitle({ title: isManager() ? '复审' : '审批' })
  load(true)
})
onPullDownRefresh(() => load(true))

const open = (no) => uni.navigateTo({ url: `/pages/approval/detail?applyNo=${encodeURIComponent(no)}` })
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
.note { display: block; margin-top: 8rpx; font-size: 24rpx; color: #b0b7c0; }
.tag { font-size: 22rpx; padding: 4rpx 16rpx; border-radius: 8rpx; }
.tag-green { background: #e8f5e9; color: #2e7d32; }
.tag-amber { background: #fff7e6; color: #ad6800; }
.tag-red { background: #fdecec; color: #d83931; }
.tag-gray { background: #f0f1f3; color: #5f6773; }
.empty { text-align: center; color: #9aa2ad; font-size: 26rpx; padding: 80rpx 0; }
</style>
