<template>
  <!--
    全局 AI 助手入口（网页端 AiAssistBall + AiAssistPanel 的 uni-app 重写）。
    右下角可拖动的小球：拖到顺手的位置，点一下从底部弹出问答面板。
    位置记在本地（uni.setStorageSync），「顺手放哪」是偏好，不占后端一张表。

    按角色决定问什么：
    - 经理 → 问经营数据（dataQa）
    - 员工 / 主管 / 财务 → 问差旅规定（policyQa）
    - 系统管理员 → 不显示小球，改从首页进「AI 运行状况」页

    每页问答用 panelGet/Save/Clear 按 pageKey 暂存，跟网页端一致：
    切走时存掉当前页，回来原样恢复，不会「在 A 页问完切到 B 页看到 A 的答案」。
  -->
  <view v-if="visible" class="wrap">
    <!-- 可拖动小球 -->
    <view
      class="ball"
      :style="{ left: x + 'px', top: y + 'px' }"
      @touchstart="onDown" @touchmove="onMove" @touchend="onUp"
    >
      <text class="ball-txt">AI</text>
    </view>

    <!-- 底部弹层 -->
    <view v-if="open" class="mask" @click="close">
      <view class="sheet" @click.stop>
        <view class="sh-hd">
          <text class="sh-mark">AI</text>
          <text class="sh-title">{{ mode === 'data' ? 'AI 助手 · 经营数据' : 'AI 助手 · 差旅规定' }}</text>
          <text class="sh-close" @click="close">收起</text>
        </view>

        <view class="sh-bd">
          <view class="sh-ask">
            <input class="sh-input" v-model="question" :maxlength="mode === 'data' ? 200 : 100"
              :placeholder="mode === 'data' ? '比如：9 月技术部费效比多少？' : '比如：去深圳坐飞机能报多少？'"
              placeholder-style="color:#b6bcc4" confirm-type="search" @confirm="ask" />
            <text class="sh-btn" :class="{ disabled: !question.trim() || loading }" @click="ask">提问</text>
          </view>

          <view v-if="result.question" class="sh-answer">
            <view class="line">
              <text class="mk">问</text>
              <text class="txt">{{ result.question }}</text>
            </view>
            <view class="line" :class="{ miss: result.answerable === false }">
              <text class="mk" :class="{ miss: result.answerable === false }">答</text>
              <view class="ans">
                <text class="txt">{{ result.answer }}</text>
                <text v-if="result.local" class="local">本地规则</text>
              </view>
            </view>

            <template v-if="mode === 'policy'">
              <view v-if="(result.facts || []).length" class="facts">
                <text class="facts-t">依据</text>
                <view v-for="(f, i) in result.facts" :key="i" class="fact">{{ f }}</view>
              </view>
              <view v-if="(result.allFacts || []).length" class="facts">
                <text class="facts-t">库里现行的规定（{{ result.allFacts.length }} 条）</text>
                <view v-for="(f, i) in result.allFacts" :key="i" class="fact dim">{{ f }}</view>
              </view>
            </template>

            <template v-else>
              <view v-if="scalarRows.length" class="facts">
                <text class="facts-t">数据明细</text>
                <view class="kv">
                  <view v-for="(r, i) in scalarRows" :key="i" class="kv-cell">
                    <text class="kv-k">{{ r.label }}</text>
                    <text class="kv-v">{{ r.value }}</text>
                  </view>
                </view>
              </view>
              <view v-if="tableRows.length" class="facts">
                <text class="facts-t">列表</text>
                <view v-for="(row, i) in tableRows" :key="i" class="t-row">
                  <view v-for="c in tableCols" :key="c" class="t-cell">
                    <text class="t-k">{{ LABELS[c] || c }}</text>
                    <text class="t-v">{{ row[c] == null ? '—' : row[c] }}</text>
                  </view>
                </view>
              </view>
              <view v-if="statusRows.length" class="facts">
                <text class="facts-t">按状态分布</text>
                <view v-for="(s, i) in statusRows" :key="i" class="fact">{{ s[0] }}：{{ s[1] }} 单</view>
              </view>
            </template>
          </view>
        </view>

        <view v-if="result.question" class="sh-ft">
          <text class="sh-clear" @click="clearAll">清空</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { policyQa, dataQa, panelGet, panelSave, panelClear } from '../api'
import { isManager, isAdmin, role } from '../utils/session'

const SIZE = 96          // 小球边长（px）
const POS_KEY = 'aiBallPos'
const visible = ref(false)
const open = ref(false)

const x = ref(0)
const y = ref(0)
let screenW = 375
let screenH = 667

const question = ref('')
const loading = ref(false)
const result = reactive({})

const mode = computed(() => (isManager() ? 'data' : 'policy'))

const LABELS = {
  month: '月份', scope: '范围', totalPay: '投入', totalOutput: '产出',
  ratio: '费效比', attentionRatio: '可疑占比', fillRate: '回填率',
  applyCnt: '出差单数', paidCnt: '已付款单数', deptName: '部门', empName: '员工',
  totalEst: '预估合计', monthlyBudget: '月度预算', used: '已使用', left: '余额',
  totalBudget: '总预算', totalUsed: '总使用', totalLeft: '总余额',
  count: '在审单数', overdueCount: '超时单数', totalInvest: '总投入',
  partnerName: '对象', tripCnt: '出差次数', applyNo: '单号', reason: '事由',
  status: '状态', waitedHours: '已等待(小时)', overdueHours: '超时(小时)', overdue: '是否超时'
}

const scalarRows = computed(() => {
  const dd = result.data
  if (!dd) return []
  const out = []
  for (const [k, v] of Object.entries(dd)) {
    if (['rows', 'items', 'byStatus', 'noSpend', 'scope'].includes(k)) continue
    if (v === null || v === undefined) continue
    out.push({ label: LABELS[k] || k, value: typeof v === 'object' ? JSON.stringify(v) : String(v) })
  }
  return out
})
const tableRows = computed(() => (result.data ? (result.data.rows || result.data.items || []) : []))
const tableCols = computed(() => (tableRows.value.length ? Object.keys(tableRows.value[0]) : []))
const statusRows = computed(() => {
  const dd = result.data
  return (dd && dd.byStatus) ? Object.entries(dd.byStatus) : []
})

// 后端 AiPanelState 只认 4 个键（empApply/empList/empInvoice/empExpense），
// 那是网页端按页嵌入的面板用的。移动端这个球是全局单一助手，没有「每页一份」的概念，
// 所以所有页面共用一个合法键做暂存——问完关掉，下次进来还能看到刚才那句，跨页共享正是预期。
const PERSIST_KEY = 'empApply'

/* ---------- 拖动与点击：同一组 touch 事件区分 ---------- */
let dragging = false
let moved = false
let startX = 0
let startY = 0
let originX = 0
let originY = 0

function clampX (v) {
  const max = Math.max(8, screenW - SIZE - 8)
  return Math.min(Math.max(v, 8), max)
}
function clampY (v) {
  const min = 80
  const max = Math.max(min, screenH - SIZE - 24)
  return Math.min(Math.max(v, min), max)
}

function onDown (e) {
  dragging = true
  moved = false
  const t = e.touches[0]
  startX = t.clientX
  startY = t.clientY
  originX = x.value
  originY = y.value
}
function onMove (e) {
  if (!dragging) return
  const t = e.touches[0]
  const dx = t.clientX - startX
  const dy = t.clientY - startY
  if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved = true
  x.value = clampX(originX + dx)
  y.value = clampY(originY + dy)
}
function onUp () {
  if (!dragging) return
  dragging = false
  uni.setStorageSync(POS_KEY, { x: x.value, y: y.value })
  if (!moved) openPanel()
}

/* ---------- 面板：暂存（全局单一助手，共用一个合法键） ---------- */
function snapshot () {
  return { question: question.value, result: JSON.parse(JSON.stringify(result)) }
}
function applySnap (s) {
  question.value = s?.question || ''
  Object.keys(result).forEach((k) => delete result[k])
  if (s?.result) Object.assign(result, s.result)
}
function hasContent (s) {
  return !!(s && s.result && s.result.question)
}
async function flush () {
  const s = snapshot()
  try {
    if (hasContent(s)) await panelSave(PERSIST_KEY, JSON.stringify(s))
    else await panelClear(PERSIST_KEY)
  } catch (e) { /* 存不上不打扰人 */ }
}

async function ask () {
  const q = question.value.trim()
  if (!q || loading.value) return
  loading.value = true
  try {
    Object.keys(result).forEach((k) => delete result[k])
    const fn = mode.value === 'data' ? dataQa : policyQa
    Object.assign(result, await fn(q))
    await flush()
  } catch (e) {
    uni.showToast({ title: '这次没答上来，稍后再试', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function clearAll () {
  question.value = ''
  Object.keys(result).forEach((k) => delete result[k])
  try { await panelClear(PERSIST_KEY) } catch (e) { /* 忽略 */ }
}

function openPanel () {
  panelGet(PERSIST_KEY).then((vo) => {
    applySnap(vo && vo.content ? JSON.parse(vo.content) : { question: '', result: {} })
  }).catch(() => {
    applySnap({ question: '', result: {} })
  }).finally(() => {
    open.value = true
  })
}

function close () {
  // 收起即保存
  flush()
  open.value = false
}

// 登录态 + 非系统管理员才显示。App.vue 只在启动时挂载一次，
// 所以登录 / 登出要主动通知本组件重算（login.vue / mine.vue 发 'auth-changed'）
function updateVisible () {
  const token = uni.getStorageSync('travel_token')
  const r = role()
  visible.value = !!token && r && r !== 'SYS_ADMIN'
}

onMounted(() => {
  updateVisible()
  if (!visible.value) return

  let info = {}
  try { info = uni.getWindowInfo ? uni.getWindowInfo() : uni.getSystemInfoSync() } catch (e) { info = {} }
  screenW = info.windowWidth || info.screenWidth || 375
  screenH = info.windowHeight || info.screenHeight || 667

  const saved = uni.getStorageSync(POS_KEY)
  if (saved && typeof saved.x === 'number') {
    x.value = clampX(saved.x)
    y.value = clampY(saved.y)
  } else {
    // 默认右下角，压在 tabBar 上方
    x.value = clampX(screenW - SIZE - 30)
    y.value = clampY(screenH - SIZE - 220)
  }

  uni.$on('auth-changed', updateVisible)
})

onUnmounted(() => {
  uni.$off('auth-changed', updateVisible)
})
</script>

<style scoped>
.wrap { position: fixed; left: 0; top: 0; right: 0; bottom: 0; z-index: 2100; pointer-events: none; }
.ball {
  position: fixed;
  z-index: 2100;
  width: 96rpx;
  height: 96rpx;
  border-radius: 50%;
  background: #2f6fed;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6rpx 24rpx rgba(0, 0, 0, .22);
  pointer-events: auto;
}
.ball-txt { font-size: 30rpx; font-weight: 700; letter-spacing: 1rpx; }

.mask { position: fixed; left: 0; top: 0; right: 0; bottom: 0; z-index: 2200;
  background: rgba(0, 0, 0, .35); pointer-events: auto; }
.sheet { position: fixed; left: 0; right: 0; bottom: 0; z-index: 2201;
  max-height: 76vh; display: flex; flex-direction: column;
  background: #fff; border-radius: 24rpx 24rpx 0 0;
  padding-bottom: env(safe-area-inset-bottom); pointer-events: auto; }
.sh-hd { display: flex; align-items: center; gap: 12rpx; padding: 24rpx 28rpx;
  border-bottom: 2rpx solid #eef0f3; }
.sh-mark { flex: none; width: 40rpx; height: 40rpx; border-radius: 8rpx; background: #e8f1fd;
  color: #1a56a8; font-size: 22rpx; line-height: 40rpx; text-align: center; }
.sh-title { flex: 1; font-size: 28rpx; font-weight: 600; color: #1f2328; }
.sh-close { font-size: 24rpx; color: #8a919c; }

.sh-bd { flex: 1; overflow: auto; padding: 24rpx 28rpx; }
.sh-ask { display: flex; align-items: center; gap: 16rpx; }
.sh-input { flex: 1; height: 76rpx; padding: 0 20rpx; font-size: 26rpx; color: #1f2328;
  background: #f5f6f8; border: 2rpx solid #e6e8eb; border-radius: 12rpx; }
.sh-btn { flex: none; height: 76rpx; line-height: 76rpx; padding: 0 32rpx; font-size: 26rpx;
  color: #fff; background: #2f6fed; border-radius: 12rpx; }
.sh-btn.disabled { opacity: .45; }

.sh-answer { margin-top: 20rpx; }
.line { display: flex; align-items: flex-start; gap: 14rpx; }
.line + .line { margin-top: 16rpx; padding-top: 16rpx; border-top: 2rpx dashed #e6e8eb; }
.line.miss .txt { color: #8a919c; }
.mk { flex: none; width: 40rpx; height: 40rpx; border-radius: 8rpx; background: #e8f1fd;
  color: #1a56a8; font-size: 22rpx; line-height: 40rpx; text-align: center; }
.mk.miss { background: #f0f2f5; color: #8a919c; }
.txt { font-size: 27rpx; line-height: 1.7; color: #1f2328; }
.ans { flex: 1; }
.local { display: inline-block; margin-top: 8rpx; font-size: 20rpx; color: #8a919c;
  background: #f0f2f5; padding: 2rpx 12rpx; border-radius: 6rpx; }

.facts { margin-top: 16rpx; }
.facts-t { display: block; font-size: 24rpx; color: #8a919c; margin-bottom: 8rpx; }
.fact { font-size: 25rpx; color: #4a5159; line-height: 1.9; }
.fact.dim { color: #8a919c; }
.kv { display: flex; flex-wrap: wrap; gap: 12rpx; }
.kv-cell { width: calc(50% - 6rpx); display: flex; justify-content: space-between; gap: 10rpx;
  padding: 12rpx 16rpx; border: 2rpx solid #e6e8eb; border-radius: 10rpx; background: #fff; }
.kv-k { font-size: 23rpx; color: #8a919c; }
.kv-v { font-size: 25rpx; font-weight: 600; color: #1f2328; }
.t-row { padding: 14rpx 16rpx; border: 2rpx solid #eef0f3; border-radius: 10rpx; margin-bottom: 12rpx; background: #fff; }
.t-cell { display: flex; justify-content: space-between; gap: 10rpx; padding: 4rpx 0; }
.t-k { font-size: 23rpx; color: #8a919c; }
.t-v { font-size: 25rpx; color: #1f2328; }

.sh-ft { padding: 16rpx 28rpx; border-top: 2rpx solid #eef0f3; text-align: right; }
.sh-clear { font-size: 24rpx; color: #8a919c; }
</style>
