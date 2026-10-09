<template>
  <view class="page page-tab">
    <view class="card">
      <text class="sec">AI 助手 · 经营数据</text>
      <text class="note">用大白话问经营数据，系统把真实数字算出来讲成一句人话；数字来自本库，模型只做叙事。</text>

      <view class="ask-row">
        <input class="ask-input" v-model="question" maxlength="200"
          placeholder="比如：9 月技术部费效比多少？哪个客户最不划算？"
          placeholder-style="color:#b6bcc4" confirm-type="search" @confirm="ask" />
        <text class="ask-btn" :class="{ disabled: !question.trim() || loading }" @click="ask">问一句</text>
      </view>

      <view class="quick">
        <text class="quick-l">试试：</text>
        <text v-for="q in samples" :key="q" class="quick-t" @click="question = q; ask()">{{ q }}</text>
      </view>

      <view v-if="result.question" class="answer">
        <view class="line">
          <text class="mk">问</text>
          <text class="txt">{{ result.question }}</text>
        </view>
        <view class="line" :class="{ miss: result.answerable === false }">
          <text class="mk" :class="{ miss: result.answerable === false }">答</text>
          <text class="txt">{{ result.answer }}</text>
        </view>

        <view v-if="scalarRows.length" class="block">
          <text class="block-t">数据明细</text>
          <view class="kv">
            <view v-for="(r, i) in scalarRows" :key="i" class="kv-cell">
              <text class="kv-k">{{ r.label }}</text>
              <text class="kv-v">{{ r.value }}</text>
            </view>
          </view>
        </view>

        <view v-if="tableRows.length" class="block">
          <text class="block-t">{{ tableColsLabel }}</text>
          <view v-for="(row, i) in tableRows" :key="i" class="t-row">
            <view v-for="c in tableCols" :key="c" class="t-cell">
              <text class="t-k">{{ LABELS[c] || c }}</text>
              <text class="t-v">{{ row[c] == null ? '—' : row[c] }}</text>
            </view>
          </view>
        </view>

        <view v-if="statusRows.length" class="block">
          <text class="block-t">按状态分布</text>
          <view v-for="(s, i) in statusRows" :key="i" class="stat-line">{{ s[0] }}：{{ s[1] }} 单</view>
        </view>
      </view>
    </view>

    <TabBar current="ai" />
  </view>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { dataQa } from '../../api'
import TabBar from '../../components/TabBar.vue'

const question = ref('')
const loading = ref(false)
const result = reactive({ data: null })

const samples = [
  '9 月费效比多少',
  '哪个客户最不划算',
  '我 9 月出差几次',
  '技术部 9 月预算还剩多少',
  '各部门 9 月预算还剩多少',
  '有哪些单超时没批'
]

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

// 顶层标量字段（排除数组 / 对象）渲染成「键：值」网格
const scalarRows = computed(() => {
  const d = result.data
  if (!d) return []
  const out = []
  for (const [k, v] of Object.entries(d)) {
    if (['rows', 'items', 'byStatus', 'noSpend', 'scope'].includes(k)) continue
    if (v === null || v === undefined) continue
    out.push({ label: LABELS[k] || k, value: typeof v === 'object' ? JSON.stringify(v) : String(v) })
  }
  return out
})

// 数组字段（rows / items）渲染成卡片列表
const tableRows = computed(() => {
  const d = result.data
  if (!d) return []
  return d.rows || d.items || []
})
const tableCols = computed(() => {
  if (!tableRows.value.length) return []
  return Object.keys(tableRows.value[0])
})
const tableColsLabel = computed(() => (result.data && result.data.rows ? '列表' : '明细列表'))

const statusRows = computed(() => {
  const d = result.data
  if (!d || !d.byStatus) return []
  return Object.entries(d.byStatus)
})

async function ask () {
  const q = question.value.trim()
  if (!q || loading.value) return
  loading.value = true
  try {
    Object.keys(result).forEach((k) => delete result[k])
    Object.assign(result, await dataQa(q))
  } catch (e) {
    uni.showToast({ title: '这次没答上来，稍后再试', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onShow(() => {})
</script>

<style>
.sec { display: block; font-size: 30rpx; font-weight: 500; color: #1f2328; }
.note { display: block; margin-top: 8rpx; font-size: 24rpx; color: #8a919c; line-height: 1.6; }
.ask-row { display: flex; align-items: center; gap: 16rpx; margin-top: 24rpx; }
.ask-input { flex: 1; height: 76rpx; padding: 0 20rpx; font-size: 26rpx; color: #1f2328;
  background: #f5f6f8; border: 2rpx solid #e6e8eb; border-radius: 12rpx; }
.ask-btn { flex: none; height: 76rpx; line-height: 76rpx; padding: 0 32rpx; font-size: 26rpx;
  color: #fff; background: #2f6fed; border-radius: 12rpx; }
.ask-btn.disabled { opacity: .45; }
.quick { display: flex; flex-wrap: wrap; align-items: center; gap: 12rpx; margin-top: 16rpx; }
.quick-l { font-size: 24rpx; color: #8a919c; }
.quick-t { font-size: 22rpx; color: #1a56a8; background: #e8f1fd; padding: 6rpx 16rpx; border-radius: 8rpx; }

.answer { margin-top: 24rpx; padding: 24rpx; border: 2rpx solid #e6e8eb; border-radius: 12rpx; background: #fbfcfe; }
.line { display: flex; align-items: flex-start; gap: 14rpx; }
.line + .line { margin-top: 18rpx; padding-top: 18rpx; border-top: 2rpx dashed #e6e8eb; }
.line.miss .txt { color: #8a919c; }
.mk { flex: none; width: 40rpx; height: 40rpx; border-radius: 8rpx; background: #e8f1fd;
  color: #1a56a8; font-size: 22rpx; line-height: 40rpx; text-align: center; }
.mk.miss { background: #f0f2f5; color: #8a919c; }
.txt { font-size: 28rpx; line-height: 1.7; color: #1f2328; }

.block { margin-top: 18rpx; }
.block-t { display: block; font-size: 24rpx; color: #8a919c; margin-bottom: 10rpx; }
.kv { display: flex; flex-wrap: wrap; gap: 12rpx; }
.kv-cell { width: calc(50% - 6rpx); display: flex; justify-content: space-between; gap: 10rpx;
  padding: 12rpx 16rpx; border: 2rpx solid #e6e8eb; border-radius: 10rpx; background: #fff; }
.kv-k { font-size: 24rpx; color: #8a919c; }
.kv-v { font-size: 26rpx; font-weight: 600; color: #1f2328; }
.t-row { padding: 14rpx 16rpx; border: 2rpx solid #eef0f3; border-radius: 10rpx; margin-bottom: 12rpx; background: #fff; }
.t-cell { display: flex; justify-content: space-between; gap: 10rpx; padding: 4rpx 0; }
.t-k { font-size: 24rpx; color: #8a919c; }
.t-v { font-size: 26rpx; color: #1f2328; }
.stat-line { font-size: 25rpx; color: #4a5159; line-height: 1.9; }
</style>
