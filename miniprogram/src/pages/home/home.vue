<template>
  <view class="page page-tab">
    <view class="hero">
      <text class="hi">{{ greet }}，{{ user.realName || '—' }}</text>
      <text class="role">{{ roleText }} · {{ user.deptName || '—' }}</text>
    </view>

    <!-- 第一屏就该回答一句：有什么事在等我 -->
    <view class="card">
      <text class="card-t">待你处理</text>
      <view v-if="loading" class="ph">正在看有什么事要办…</view>
      <template v-else>
        <view v-for="t in todos" :key="t.key" class="todo" @click="nav(t)">
          <text class="todo-n">{{ t.n }}</text>
          <view class="todo-m">
            <text class="todo-l">{{ t.label }}</text>
            <text class="todo-s">{{ t.hint }}</text>
          </view>
          <text class="arrow">›</text>
        </view>
        <view v-if="!todos.length" class="ph">暂时没有需要你动手的事</view>
        <!-- 审批中的不算待办，但得能点进去看进度，不然这一屏就空了 -->
        <view v-if="ongoing" class="todo todo-wait" @click="nav(ongoing)">
          <text class="todo-n todo-n-gray">{{ ongoing.n }}</text>
          <view class="todo-m">
            <text class="todo-l">在审批中</text>
            <text class="todo-s">不用你操作，点进去看进度</text>
          </view>
          <text class="arrow">›</text>
        </view>
      </template>
    </view>

    <!-- 管理层才有的数字。本月没数据就退回上个月，别给一张空卡 -->
    <view class="card" v-if="kpi">
      <view class="card-h">
        <text class="card-t">{{ kpi.month }} 经营概览</text>
        <text class="card-x" @click="go('/pages/report/report')">看详情 ›</text>
      </view>
      <view class="kpi">
        <view class="kpi-i">
          <text :class="['kpi-n', kpi.cls]">{{ kpi.ratioText }}</text>
          <text class="kpi-l">费效比（越低越好）</text>
        </view>
        <view class="kpi-i">
          <text class="kpi-n">{{ kpi.payText }}</text>
          <text class="kpi-l">已付投入</text>
        </view>
        <view class="kpi-i">
          <text class="kpi-n">{{ kpi.outText }}</text>
          <text class="kpi-l">产出</text>
        </view>
      </view>
      <text class="foot">
        关注 {{ kpi.attentionCnt }} 单 · 已付款 {{ kpi.paidCnt }} 单 · 成果回填率 {{ kpi.fillText }}
      </text>
    </view>

    <!-- 这一区的入口与底部导航同源：角色有哪些功能区，这里就只列哪些 -->
    <view class="card">
      <text class="card-t">全部功能</text>

      <view v-if="isEmployee()" class="entry" @click="go('/pages/form/form')">
        <text class="entry-ic">＋</text>
        <view><text class="entry-t">新建申请单</text><text class="entry-s">填事由、行程与预估费用</text></view>
      </view>

      <view v-for="e in entries" :key="e.key" class="entry" @click="goTab(e)">
        <text class="entry-ic">{{ e.ic }}</text>
        <view><text class="entry-t">{{ e.title }}</text><text class="entry-s">{{ e.sub }}</text></view>
      </view>

      <!-- 网页端新增的 AI 能力，按角色给入口，与底部导航同源 -->
      <view v-if="isEmployee()" class="entry" @click="go('/pages/ai/policy')">
        <text class="entry-ic">AI</text>
        <view><text class="entry-t">AI 助手</text><text class="entry-s">问差旅 / 报销规定，照库里现行规定答</text></view>
      </view>
      <view v-else-if="isManager()" class="entry" @click="go('/pages/ai/data')">
        <text class="entry-ic">AI</text>
        <view><text class="entry-t">AI 数据问答</text><text class="entry-s">用大白话问经营数据</text></view>
      </view>
      <view v-else-if="isAdmin()" class="entry" @click="go('/pages/admin/aistatus')">
        <text class="entry-ic">AI</text>
        <view><text class="entry-t">AI 运行状况</text><text class="entry-s">调用量 / 成功率 / 降级与留痕</text></view>
      </view>
    </view>

    <TabBar current="home" />
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  applyPage, expensePage, approvalTodo,
  financePending, financePayable, analyticsSummary
} from '../../api'
import { ROLE } from '../../utils/dict'
import { readUser, isEmployee, isFinance, isManager, isAdmin, canApprove } from '../../utils/session'
import { tabsOf, hasTab } from '../../utils/tabs'
import TabBar from '../../components/TabBar.vue'

// 底部导航里除「工作台」「我的」以外的功能区，首页照着列一遍，两边永远一致
const ENTRY_META = {
  apply: { ic: '单', title: '申请单', sub: '草稿、审批中、已通过，进去能传票', tab: true },
  expense: { ic: '报', title: '我的报销单', sub: '提交前先过一遍预检', tab: true },
  approval: { ic: '批', title: '审批', sub: '看参考值、差标比对与评价，再下结论', tab: true },
  finance: { ic: '财', title: '财务处理', sub: '待复核、待付款', tab: true },
  report: { ic: '表', title: '经营概览', sub: '投入产出、费效比、月度洞察', tab: true },
  admin: { ic: '档', title: '基础档案', sub: '往来对象与用户账号', tab: true }
}
const entries = computed(() => tabsOf()
  .filter((t) => t.key !== 'home' && t.key !== 'mine')
  .map((t) => {
    const m = ENTRY_META[t.key] || { ic: '·', sub: '' }
    return {
      key: t.key, url: t.url, ic: m.ic, sub: m.sub, tab: m.tab,
      title: t.key === 'approval' ? (isManager() ? '待我复审' : '待我审批') : (m.title || t.text)
    }
  }))

const user = ref({})
const todos = ref([])
const ongoing = ref(null)
const kpi = ref(null)
const loading = ref(false)

const roleText = computed(() => ROLE[user.value.role] || user.value.role || '—')
const greet = computed(() => {
  const h = new Date().getHours()
  return h < 6 ? '夜深了' : h < 11 ? '早上好' : h < 14 ? '中午好' : h < 18 ? '下午好' : '晚上好'
})

const money = (n) => n == null ? '—' : Number(n).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
const pct = (n) => n == null ? '—' : Math.round(n * 100) + '%'

// 底部导航是自研组件，切页走 redirectTo（tab 之间不压栈）；它不带参数，筛选条件用 storage 传
function toTab (url, status) {
  if (status) uni.setStorageSync('travel_apply_status', status)
  else uni.removeStorageSync('travel_apply_status')
  uni.redirectTo({ url })
}
const go = (url) => uni.navigateTo({ url })

function nav (t) {
  if (t.tab) return toTab(t.url, t.status || '')
  uni.navigateTo({ url: t.url })
}
const goTab = (e) => (e.tab ? toTab(e.url, '') : go(e.url))

async function load () {
  user.value = readUser()
  if (!uni.getStorageSync('travel_token')) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  loading.value = true
  todos.value = []
  try {
    if (isEmployee()) await employeeTodos()
    else if (canApprove() || isFinance()) await reviewerTodos()
    // 经营概览只挂管理层的导航，别的角色不加载这几个数
    if (hasTab('report')) await loadKpi()
  } finally {
    loading.value = false
  }
}

/* 员工：真正卡在他手上的只有四件事——没提交的草稿、被驳回的、
   通过了还没报销的、报销被打回来的 */
async function employeeTodos () {
  const [draft, rejected, approved, closed, expenses] = await Promise.all([
    applyPage({ status: 'DRAFT', pageNum: 1, pageSize: 1 }).catch(() => null),
    applyPage({ status: 'REJECTED', pageNum: 1, pageSize: 1 }).catch(() => null),
    applyPage({ status: 'APPROVED', pageNum: 1, pageSize: 50 }).catch(() => null),
    applyPage({ status: 'CLOSED', pageNum: 1, pageSize: 50 }).catch(() => null),
    expensePage({ pageNum: 1, pageSize: 50 }).catch(() => null)
  ])

  const out = []
  if (draft && draft.total) {
    out.push({ key: 'draft', n: draft.total, label: '草稿还没提交', hint: '填完要点提交，否则审批看不到', tab: true, status: 'DRAFT', url: '/pages/list/list' })
  }
  if (rejected && rejected.total) {
    out.push({ key: 'rejected', n: rejected.total, label: '被驳回，等你改', hint: '改完在原单上重提', tab: true, status: 'REJECTED', url: '/pages/list/list' })
  }

  // 能报销的是已通过和已结案两种，减掉已经报过的
  const expList = (expenses && expenses.list) || []
  const reported = new Set(expList.map((x) => x.applyNo))
  const billable = [...((approved && approved.list) || []), ...((closed && closed.list) || [])]
    .filter((x) => !reported.has(x.applyNo))
  if (billable.length) {
    out.push({
      key: 'tobill', n: billable.length,
      label: '通过还没报销', hint: billable.length === 1 ? '就一张，直接进去传票' : '进去挑一张先报',
      tab: billable.length > 1, status: '',
      url: billable.length === 1
        ? `/pages/detail/detail?applyNo=${encodeURIComponent(billable[0].applyNo)}`
        : '/pages/expense/list'
    })
  }

  const returned = expList.filter((x) => x.status === 'RETURNED')
  if (returned.length) {
    out.push({ key: 'returned', n: returned.length, label: '报销被打回', hint: '看复核意见改完再交', tab: true, status: '', url: '/pages/expense/list' })
  }

  todos.value = out

  // 审批中的不算待办，但得让他知道有几单在别人手上
  const [dept, final] = await Promise.all([
    applyPage({ status: 'DEPT_REVIEW', pageNum: 1, pageSize: 1 }).catch(() => null),
    applyPage({ status: 'FINAL_REVIEW', pageNum: 1, pageSize: 1 }).catch(() => null)
  ])
  const waiting = (dept?.total || 0) + (final?.total || 0)
  ongoing.value = waiting
    ? { n: waiting, label: '在审批中', tab: true, status: 'DEPT_REVIEW', url: '/pages/list/list' }
    : null
}

/* 主管 / 管理层 / 财务：待办就是别人交上来在等他点一下的单子 */
async function reviewerTodos () {
  const out = []
  if (canApprove()) {
    const p = await approvalTodo({ listType: 'todo', pageNum: 1, pageSize: 1 }).catch(() => null)
    if (p && p.total) {
      out.push({
        key: 'approve', n: p.total,
        label: isManager() ? '待我复审' : '待我审批',
        hint: '路上就能批，看参考值和差标比对', tab: false, url: '/pages/approval/todo'
      })
    }
  }
  if (isFinance()) {
    const [pend, pay] = await Promise.all([
      financePending({ pageNum: 1, pageSize: 1 }).catch(() => null),
      financePayable({ pageNum: 1, pageSize: 1 }).catch(() => null)
    ])
    if (pend && pend.total) {
      out.push({ key: 'review', n: pend.total, label: '待复核', hint: '四维校验过一遍再下结论', tab: false, url: '/pages/finance/list?tab=pending' })
    }
    if (pay && pay.total) {
      out.push({ key: 'pay', n: pay.total, label: '待付款', hint: '复核通过了的，付款后自动结案', tab: false, url: '/pages/finance/list?tab=payable' })
    }
  }
  todos.value = out
}

/* 本月没数据就退回上个月，演示库的数据落在 8、9 月 */
async function loadKpi () {
  const now = new Date()
  const thisMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
  const pm = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  const lastMonth = `${pm.getFullYear()}-${String(pm.getMonth() + 1).padStart(2, '0')}`

  let d = await analyticsSummary(thisMonth).catch(() => null)
  if (!d || !d.paidCnt) d = await analyticsSummary(lastMonth).catch(() => null)
  if (!d || d.ratio == null) { kpi.value = null; return }

  // 费效比口径：≤0.30 绿、≤0.45 黄、>0.45 红
  const cls = d.ratio <= 0.30 ? 'k-green' : d.ratio <= 0.45 ? 'k-amber' : 'k-red'
  kpi.value = {
    month: d.month || lastMonth,
    ratioText: d.ratio.toFixed(4),
    payText: money(d.totalPay),
    outText: money(d.totalOutput),
    attentionCnt: d.attentionCnt ?? 0,
    paidCnt: d.paidCnt ?? 0,
    fillText: pct(d.fillRate),
    cls
  }
}

onShow(load)
</script>

<style>
.page { min-height: 100vh; padding: 32rpx 32rpx 180rpx; box-sizing: border-box;
  background: linear-gradient(180deg, #e2ecfb 0%, #eef4fc 40%, #f5f8fd 100%); }
.hero { padding: 24rpx 8rpx 32rpx; }
.hi { display: block; font-size: 44rpx; font-weight: 600; color: #1f2328; }
.role { display: block; margin-top: 8rpx; font-size: 26rpx; color: #8a919c; }

.card { background: #fff; border-radius: 16rpx; padding: 28rpx 32rpx; margin-bottom: 24rpx; }
.card-h { display: flex; justify-content: space-between; align-items: center; }
.card-t { font-size: 30rpx; font-weight: 500; color: #1f2328; }
.card-x { font-size: 24rpx; color: #2f6fed; }
.foot { display: block; margin-top: 16rpx; font-size: 24rpx; color: #8a919c; }
.ph { padding: 32rpx 0; text-align: center; font-size: 26rpx; color: #9aa2ad; }

.todo { display: flex; align-items: center; padding: 24rpx 0; border-bottom: 2rpx solid #f0f1f3; }
.todo:last-child { border-bottom: none; }
.todo-n { width: 88rpx; font-size: 44rpx; font-weight: 600; color: #d83931; }
.todo-wait { border-top: 2rpx solid #f0f1f3; }
.todo-n-gray { color: #8a919c; }
.todo-m { flex: 1; }
.todo-l { display: block; font-size: 30rpx; color: #1f2328; }
.todo-s { display: block; margin-top: 6rpx; font-size: 24rpx; color: #8a919c; }
.arrow { font-size: 36rpx; color: #c4c9d0; }

.kpi { display: flex; margin-top: 20rpx; }
.kpi-i { flex: 1; }
.kpi-n { display: block; font-size: 36rpx; font-weight: 600; color: #1f2328; }
.kpi-l { display: block; margin-top: 6rpx; font-size: 22rpx; color: #8a919c; }
.k-green { color: #2e7d32; }
.k-amber { color: #ad6800; }
.k-red { color: #d83931; }

.entry { display: flex; align-items: center; padding: 24rpx 0; border-top: 2rpx solid #f0f1f3; }
.entry-ic { width: 72rpx; height: 72rpx; border-radius: 16rpx; background: #eef3fe; color: #2f6fed;
  font-size: 32rpx; display: flex; align-items: center; justify-content: center; margin-right: 24rpx; }
.entry-t { display: block; font-size: 30rpx; color: #1f2328; }
.entry-s { display: block; margin-top: 6rpx; font-size: 24rpx; color: #8a919c; }
</style>
