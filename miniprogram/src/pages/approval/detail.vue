<template>
  <view class="page">
    <view v-if="!v" class="empty"><text>加载中…</text></view>

    <template v-if="v">
      <view class="card">
        <view class="row1">
          <text class="no">{{ a.applyNo }}</text>
          <text :class="['tag', statusCls(a.status)]">{{ statusText(a.status) }}</text>
        </view>
        <text class="reason">{{ a.reason || '—' }}</text>
        <view class="grid">
          <view class="g-item"><text class="g-lbl">申请人</text><text class="g-val">{{ a.applicant }} · {{ a.deptName }}</text></view>
          <view class="g-item"><text class="g-lbl">预估合计</text><text class="g-val money">¥ {{ fmtMoney(a.estTotal) }}</text></view>
          <view class="g-item"><text class="g-lbl">出差类型</text><text class="g-val">{{ dash(a.tripType) }}</text></view>
          <view class="g-item"><text class="g-lbl">关联对象</text><text class="g-val">{{ dash(a.partnerName) }}</text></view>
        </view>
      </view>

      <!-- 差标比对：一段一行，超了标红 -->
      <view class="card" v-if="(v.standardHints || []).length">
        <text class="sec">差标比对</text>
        <view v-for="(h, i) in v.standardHints" :key="i" class="it">
          <text :class="['it-line', h.result === 'EXCEED' ? 'bad' : (h.result === 'HIGH' ? 'warn' : '')]">
            {{ h.cityLevel }} / {{ h.trafficType }}：上限 ¥ {{ fmtMoney(h.maxAmount) }}，本单 ¥ {{ fmtMoney(h.estAmount) }}
          </text>
        </view>
      </view>

      <!-- 同类参考 -->
      <view class="card" v-if="v.costRef">
        <text class="sec">同类花费参考</text>
        <text class="ref-line">{{ v.costRef.toCity || '—' }} · {{ v.costRef.tripType || '—' }} · {{ v.costRef.sampleCnt }} 单{{ v.costRef.relaxed ? '（已放宽）' : '' }}</text>
        <text class="ref-line">区间 ¥ {{ fmtMoney(v.costRef.minAmount) }} ~ {{ fmtMoney(v.costRef.maxAmount) }}，均值 ¥ {{ fmtMoney(v.costRef.avgAmount) }}</text>
        <text v-if="v.costRef.lowSample" class="note">样本偏少，只作参考</text>
      </view>

      <!-- 评价 -->
      <view class="card" v-if="v.eval">
        <view class="row1"><text class="sec">事前评价</text><text class="tag tag-amber">{{ v.eval.level }} 级 · {{ v.eval.totalScore }}</text></view>
        <view v-for="(f, i) in (v.eval.factors || [])" :key="i" class="it">
          <text class="it-line">{{ f.name }}（权重 {{ f.weight }}）得分 {{ f.score }}</text>
          <text class="it-sub">{{ f.value }}</text>
        </view>
      </view>

      <!-- AI 风险提示 -->
      <view class="card">
        <view class="row1"><text class="sec">AI 风险提示</text><text class="dim">{{ risk ? (risk.local ? '本地规则' : '模型输出') : '' }}</text></view>
        <button class="btn-ghost btn" :disabled="risking" @click="loadRisk">{{ risking ? '分析中…' : '跑一次风险分析' }}</button>
        <view v-if="risk" class="mt">
          <text v-for="(p, i) in (risk.points || [])" :key="i" :class="['ai-line', p.level]">· {{ p.text }}</text>
          <text v-if="risk.summary" class="note">{{ risk.summary }}</text>
        </view>
      </view>

      <!-- 票据 -->
      <view class="card">
        <view class="row1"><text class="sec">票据</text><text class="dim">{{ (iv.invoices || []).length }} 张</text></view>
        <view v-if="!(iv.invoices || []).length" class="empty"><text>—</text></view>
        <view v-else class="imgs">
          <view v-for="(inv, i) in iv.invoices" :key="inv.id || i" class="img-wrap">
            <image :src="inv.thumbUrl || inv.imgUrl" class="img" mode="aspectFill"
              @click="preview(i)" @error="refreshUrl(inv)" />
            <text class="img-cap">¥ {{ fmtMoney(inv.amount) }}</text>
            <text class="img-cap">{{ inv.seller || dash(inv.invoiceNo) }}</text>
          </view>
        </view>
      </view>

      <!-- 决策 -->
      <view class="card" v-if="v.canApprove">
        <text class="sec">审批意见</text>
        <textarea v-model="opinion" class="ta" maxlength="200" placeholder="通过可留空，驳回必填" placeholder-class="ph" />
        <text class="note">顺手把这趟成果填了也行，不填就不回填</text>
        <picker :range="RESULT_TYPES" @change="(e) => rf.resultType = RESULT_TYPES[e.detail.value]">
          <view class="cell"><text class="lbl">成果类型</text><text class="val">{{ rf.resultType || '不回填' }}</text></view>
        </picker>
        <view class="cell"><text class="lbl">折算产出</text><input v-model="rf.outputAmount" class="val ipt" type="digit" placeholder="0.00" /></view>
        <view class="btn-row">
          <button class="btn btn-primary" @click="decide('APPROVED')">通过</button>
          <button class="btn btn-danger" @click="decide('REJECTED')">驳回</button>
        </view>
      </view>
      <view class="card" v-else>
        <text class="note">{{ v.readonlyReason || '这张单子现在不用你处理' }}</text>
      </view>

      <!-- 流转记录 -->
      <view class="card" v-if="(v.records || []).length">
        <text class="sec">流转记录</text>
        <view v-for="(r, i) in v.records" :key="i" class="it">
          <text class="it-line">{{ r.step }} · {{ r.result }} · {{ r.approver || '—' }}</text>
          <text class="it-sub">{{ r.opinion || '—' }}（{{ r.approvedAt }}）</text>
        </view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import {
  approvalDetail, approvalRisk, approvalDecision, invoiceList, invoiceUrl
} from '../../api'
import { APPLY_STATUS, RESULT_TYPES, fmtMoney, dash } from '../../utils/dict'

const v = ref(null)
const iv = ref({ invoices: [] })
const risk = ref(null)
const risking = ref(false)
const opinion = ref('')
const rf = ref({ resultType: '', resultDesc: '', outputAmount: '' })
const applyNo = ref('')

const a = computed(() => (v.value && v.value.apply) || {})

const statusText = (s) => APPLY_STATUS[s] || s || '—'
const statusCls = (s) => ({
  DRAFT: 'tag-gray', DEPT_REVIEW: 'tag-amber', FINAL_REVIEW: 'tag-amber',
  APPROVED: 'tag-green', REJECTED: 'tag-red', CLOSED: 'tag-gray'
}[s] || 'tag-gray')

onLoad(async (q) => {
  applyNo.value = q.applyNo
  try {
    v.value = await approvalDetail(q.applyNo)
    iv.value = await invoiceList(q.applyNo)
  } catch (e) { /* toast 已弹 */ }
})

async function loadRisk () {
  risking.value = true
  try { risk.value = await approvalRisk(applyNo.value) } catch (e) { /* toast 已弹 */ }
  risking.value = false
}

async function decide (result) {
  if (result === 'REJECTED' && !opinion.value.trim()) {
    uni.showToast({ title: '驳回要写意见', icon: 'none' })
    return
  }
  try {
    await approvalDecision(applyNo.value, {
      result,
      opinion: opinion.value || null,
      resultType: rf.value.resultType || null,
      resultDesc: rf.value.resultDesc || null,
      outputAmount: rf.value.outputAmount ? Number(rf.value.outputAmount) : null
    })
    uni.showToast({ title: result === 'APPROVED' ? '已通过' : '已驳回', icon: 'success' })
    setTimeout(() => uni.navigateBack(), 800)
  } catch (e) { /* toast 已弹 */ }
}

function preview (i) {
  const urls = (iv.value.invoices || []).map(x => x.imgUrl || x.thumbUrl).filter(Boolean)
  if (!urls.length) return
  uni.previewImage({ urls, current: i })
}

async function refreshUrl (item) {
  try {
    const fresh = await invoiceUrl(item.id)
    if (fresh) Object.assign(item, { imgUrl: fresh.imgUrl, thumbUrl: fresh.thumbUrl })
  } catch (e) { /* 保持原样 */ }
}
</script>

<style>
.no { font-size: 26rpx; color: #8a919c; }
.reason { display: block; margin-top: 14rpx; font-size: 30rpx; color: #1f2328; }
.it { margin-top: 16rpx; }
.it-line { display: block; font-size: 27rpx; color: #1f2328; }
.it-line.bad { color: #d83931; }
.it-line.warn { color: #ad6800; }
.it-sub { display: block; font-size: 24rpx; color: #8a919c; margin-top: 4rpx; }
.ref-line { display: block; font-size: 26rpx; color: #1f2328; margin-top: 6rpx; }
.note { display: block; margin-top: 8rpx; font-size: 24rpx; color: #8a919c; }
.ta { width: 100%; height: 140rpx; margin: 12rpx 0; font-size: 28rpx; }
.ph { color: #b9c0ca; font-size: 28rpx; }
.cell { display: flex; justify-content: space-between; align-items: center; min-height: 88rpx; border-bottom: 1rpx solid #f0f1f3; }
.lbl { font-size: 28rpx; color: #5f6773; }
.val { font-size: 28rpx; color: #1f2328; }
.ipt { flex: 1; margin-left: 24rpx; text-align: right; }
.ai-line { display: block; font-size: 26rpx; color: #1f2328; margin-top: 8rpx; }
.ai-line.high { color: #d83931; }
.ai-line.mid { color: #ad6800; }
.ai-line.low { color: #5f6773; }
.img-wrap { width: 200rpx; }
</style>
