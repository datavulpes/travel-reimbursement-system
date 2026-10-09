<template>
  <view class="page">
    <view v-if="!d" class="empty"><text>加载中…</text></view>

    <template v-if="d">
      <view class="card">
        <view class="row1">
          <text class="no">{{ d.reportNo }}</text>
          <text :class="['tag', tagOf(EXPENSE_STATUS, d.status)]">{{ textOf(EXPENSE_STATUS, d.status) }}</text>
        </view>
        <text class="reason">{{ d.reason || '—' }}</text>
        <view class="grid">
          <view class="g-item"><text class="g-lbl">票面合计</text><text class="g-val money">¥ {{ fmtMoney(d.totalAmount) }}</text></view>
          <view class="g-item"><text class="g-lbl">借款冲抵</text><text class="g-val">¥ {{ fmtMoney(d.offsetAmount) }}</text></view>
          <view class="g-item"><text class="g-lbl">应付金额</text><text class="g-val money">¥ {{ fmtMoney(d.netAmount) }}</text></view>
          <view class="g-item"><text class="g-lbl">报销人</text><text class="g-val">{{ d.applicant }} · {{ d.deptName }}</text></view>
        </view>
        <text v-if="d.forceReason" class="note">强制说明：{{ d.forceReason }}</text>
      </view>

      <!-- 四维校验 -->
      <view class="card">
        <view class="row1">
          <text class="sec">四维校验</text>
          <text class="dim">{{ chk ? ('异常 ' + (chk.issueCount || 0) + ' 项') : '' }}</text>
        </view>
        <button class="btn-ghost btn" @click="loadCheck">跑一次校验</button>
        <template v-if="chk">
          <!-- v-if 不能和 v-for 写在同一个元素上：Vue 3 里 v-if 先算，g 还没定义 -->
          <template v-for="g in dims" :key="g.title">
            <view v-if="g.list.length" class="mt">
              <text class="sec">{{ g.title }}</text>
              <view v-for="(x, i) in g.list" :key="i" class="chk">
                <text :class="['tag', x.level === 'BLOCK' ? 'tag-red' : 'tag-amber']">{{ x.issueCode }}</text>
                <text class="chk-desc">{{ x.desc }}</text>
              </view>
            </view>
          </template>
          <text v-if="!chk.issueCount" class="note">四个维度都没查出问题</text>
        </template>
      </view>

      <!-- 可疑分析 -->
      <view class="card">
        <view class="row1"><text class="sec">可疑票据分析</text><text class="dim">{{ susp ? (susp.local ? '本地规则' : '模型输出') : '' }}</text></view>
        <button class="btn-ghost btn" :disabled="susping" @click="loadSusp">{{ susping ? '分析中…' : '跑一次分析' }}</button>
        <view v-if="susp" class="mt">
          <text class="note">共 {{ susp.invoiceCnt }} 张票参加分析</text>
          <text v-for="(p, i) in (susp.points || [])" :key="i" :class="['ai-line', p.level]">· {{ p.text }}</text>
          <text v-if="susp.summary" class="note">{{ susp.summary }}</text>
        </view>
      </view>

      <!-- 票据 -->
      <view class="card">
        <view class="row1"><text class="sec">票据</text><text class="dim">{{ (d.invoices || []).length }} 张</text></view>
        <view v-if="!(d.invoices || []).length" class="empty"><text>—</text></view>
        <view v-else class="imgs">
          <view v-for="(inv, i) in d.invoices" :key="inv.id || i" class="img-wrap">
            <image :src="inv.thumbUrl || inv.imgUrl" class="img" mode="aspectFill"
              @click="preview(i)" @error="refreshUrl(inv)" />
            <text class="img-cap">¥ {{ fmtMoney(inv.amount) }}</text>
            <text class="img-cap">{{ inv.seller || dash(inv.invoiceNo) }}</text>
            <text v-if="inv.dupOf" class="img-cap dup">疑似重复：{{ inv.dupOf }}</text>
          </view>
        </view>
      </view>

      <!-- 复核：待复核状态才给按钮 -->
      <view class="card" v-if="d.status === 'PENDING'">
        <text class="sec">复核结论</text>
        <view class="cell"><text class="lbl">通过（无异常）</text><text class="val">{{ rv.result === 'APPROVED' ? '已选' : '' }}</text></view>
        <view class="cell"><text class="lbl">带异常通过</text><text class="val">{{ rv.result === 'PASS_WITH_ISSUE' ? '已选' : '' }}</text></view>
        <view class="cell"><text class="lbl">退回</text><text class="val">{{ rv.result === 'RETURNED' ? '已选' : '' }}</text></view>
        <picker :range="reviewOpts" range-key="label" @change="(e) => rv.result = reviewOpts[e.detail.value].value">
          <view class="cell"><text class="lbl">选择结论</text><text class="val">{{ reviewLabel || '请选择' }}</text></view>
        </picker>
        <textarea v-model="rv.reason" class="ta" maxlength="200" :placeholder="rv.result === 'APPROVED' ? '选填' : '带异常或退回必填'" placeholder-class="ph" />
        <button class="btn btn-primary" @click="doReview">提交复核结论</button>
      </view>

      <!-- 付款：复核通过后才给 -->
      <view class="card" v-if="d.status === 'APPROVED' || d.status === 'PASS_WITH_ISSUE'">
        <text class="sec">付款</text>
        <view class="cell"><text class="lbl">付款金额</text><input v-model="pay.payAmount" class="val ipt" type="digit" placeholder="0.00" /></view>
        <view class="cell"><text class="lbl">备注</text><input v-model="pay.payRemark" class="val ipt" placeholder="选填" /></view>
        <button class="btn btn-primary" @click="doPay">确认付款</button>
        <text class="note">付完自动结案对应的申请单</text>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import {
  expenseDetail, financeCheck, financeSuspicion, financeReview, financePay, invoiceUrl
} from '../../api'
import { EXPENSE_STATUS, tagOf, textOf, fmtMoney, dash } from '../../utils/dict'

const d = ref(null)
const chk = ref(null)
const susp = ref(null)
const susping = ref(false)
const reportNo = ref('')
const rv = ref({ result: '', reason: '' })
const pay = ref({ payAmount: '', loanId: null, payRemark: '' })

const reviewOpts = [
  { label: '通过（无异常）', value: 'APPROVED' },
  { label: '带异常通过', value: 'PASS_WITH_ISSUE' },
  { label: '退回', value: 'RETURNED' }
]
const reviewLabel = computed(() => {
  const hit = reviewOpts.find(x => x.value === rv.value.result)
  return hit ? hit.label : ''
})

const dims = computed(() => {
  const c = chk.value || {}
  return [
    { title: '人维', list: c.personIssues || [] },
    { title: '事维', list: c.matterIssues || [] },
    { title: '票维', list: c.invoiceIssues || [] },
    { title: '账维', list: c.accountIssues || [] }
  ]
})

onLoad(async (q) => {
  reportNo.value = q.reportNo
  await load()
})

async function load () {
  try { d.value = await expenseDetail(reportNo.value) } catch (e) { /* toast 已弹 */ }
}

async function loadCheck () {
  try { chk.value = await financeCheck(reportNo.value) } catch (e) { /* toast 已弹 */ }
}

async function loadSusp () {
  susping.value = true
  try { susp.value = await financeSuspicion(reportNo.value) } catch (e) { /* toast 已弹 */ }
  susping.value = false
}

async function doReview () {
  if (!rv.value.result) { uni.showToast({ title: '先选复核结论', icon: 'none' }); return }
  if (rv.value.result !== 'APPROVED' && !rv.value.reason.trim()) {
    uni.showToast({ title: '带异常通过或退回要写说明', icon: 'none' })
    return
  }
  try {
    await financeReview(reportNo.value, { result: rv.value.result, reason: rv.value.reason || null })
    uni.showToast({ title: '已提交', icon: 'success' })
    await load()
  } catch (e) { /* toast 已弹 */ }
}

async function doPay () {
  if (!pay.value.payAmount) { uni.showToast({ title: '填付款金额', icon: 'none' }); return }
  try {
    const r = await financePay(reportNo.value, {
      payAmount: Number(pay.value.payAmount),
      loanId: d.value.loan ? d.value.loan.id : null,
      payRemark: pay.value.payRemark || null
    })
    uni.showToast({ title: '付款成功', icon: 'success' })
    await load()
  } catch (e) { /* toast 已弹 */ }
}

function preview (i) {
  const urls = (d.value.invoices || []).map(x => x.imgUrl || x.thumbUrl).filter(Boolean)
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
.note { display: block; margin-top: 8rpx; font-size: 24rpx; color: #8a919c; }
.sec { display: block; font-size: 28rpx; font-weight: 500; color: #1f2328; margin-bottom: 8rpx; }
.chk { display: flex; align-items: flex-start; margin-top: 12rpx; }
.chk-desc { flex: 1; margin-left: 16rpx; font-size: 26rpx; color: #1f2328; }
.ai-line { display: block; font-size: 26rpx; color: #1f2328; margin-top: 8rpx; }
.ai-line.high { color: #d83931; }
.ai-line.mid { color: #ad6800; }
.ai-line.low { color: #5f6773; }
.cell { display: flex; justify-content: space-between; align-items: center; min-height: 80rpx; border-bottom: 1rpx solid #f0f1f3; }
.lbl { font-size: 28rpx; color: #5f6773; }
.val { font-size: 28rpx; color: #1f2328; }
.ipt { flex: 1; margin-left: 24rpx; text-align: right; }
.ta { width: 100%; height: 140rpx; margin: 12rpx 0; font-size: 28rpx; }
.ph { color: #b9c0ca; font-size: 28rpx; }
.img-wrap { width: 200rpx; }
.dup { color: #d83931; }
</style>
