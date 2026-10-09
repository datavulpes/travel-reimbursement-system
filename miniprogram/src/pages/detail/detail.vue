<template>
  <view class="page">
    <view v-if="!d" class="empty"><text>加载中…</text></view>

    <template v-if="d">
      <view class="card">
        <view class="row1">
          <text class="no">{{ d.applyNo }}</text>
          <text :class="['tag', statusCls(d.status)]">{{ statusText(d.status) }}</text>
        </view>
        <text class="reason">{{ d.reason || '—' }}</text>
        <view class="grid">
          <view class="g-item"><text class="g-lbl">预估合计</text><text class="g-val money">¥ {{ fmtMoney(d.estTotal) }}</text></view>
          <view class="g-item"><text class="g-lbl">差标结论</text><text class="g-val">{{ stdText(d.standardResult) }}</text></view>
          <view class="g-item"><text class="g-lbl">出差类型</text><text class="g-val">{{ dash(d.tripType) }}</text></view>
          <view class="g-item"><text class="g-lbl">关联对象</text><text class="g-val">{{ dash(d.partnerName) }}</text></view>
        </view>
        <text v-if="d.exceedReason" class="note">超标说明：{{ d.exceedReason }}</text>
        <text v-if="d.tripTypeNote" class="note">类型补充：{{ d.tripTypeNote }}</text>
      </view>

      <view class="card" v-if="(d.itineraries || []).length">
        <text class="sec">行程</text>
        <view v-for="(it, i) in d.itineraries" :key="i" class="it">
          <text class="it-line">{{ i + 1 }}. {{ it.fromCity }} → {{ it.toCity }}（{{ it.trafficType }} / {{ it.cityLevel }}）</text>
          <text class="it-sub">{{ it.startDate }} ~ {{ it.endDate }} · 该段 {{ it.invoiceCnt || 0 }} 张票</text>
        </view>
      </view>

      <!-- 评价结论 -->
      <view class="card" v-if="ev">
        <view class="row1"><text class="sec">事前评价</text><text :class="['tag', ev.level === 'A' ? 'tag-green' : (ev.level === 'D' ? 'tag-red' : 'tag-amber')]">{{ ev.level }} 级</text></view>
        <view class="grid">
          <view class="g-item"><text class="g-lbl">历史 0.35</text><text class="g-val">{{ dash(ev.fHistory) }}</text></view>
          <view class="g-item"><text class="g-lbl">成本 0.30</text><text class="g-val">{{ dash(ev.fCost) }}</text></view>
          <view class="g-item"><text class="g-lbl">频次 0.20</text><text class="g-val">{{ dash(ev.fFreq) }}</text></view>
          <view class="g-item"><text class="g-lbl">完整 0.15</text><text class="g-val">{{ dash(ev.fComplete) }}</text></view>
        </view>
        <text v-for="(h, i) in (ev.hints || [])" :key="i" class="note">· {{ h }}</text>
      </view>

      <!-- 预借款 -->
      <view class="card" v-if="loan">
        <text class="sec">预借款</text>
        <view class="grid">
          <view class="g-item"><text class="g-lbl">借款金额</text><text class="g-val money">¥ {{ fmtMoney(loan.amount) }}</text></view>
          <view class="g-item"><text class="g-lbl">已冲抵</text><text class="g-val">¥ {{ fmtMoney(loan.offsetAmount) }}</text></view>
          <view class="g-item"><text class="g-lbl">剩余</text><text class="g-val">¥ {{ fmtMoney(loan.remainAmount) }}</text></view>
          <view class="g-item"><text class="g-lbl">状态</text><text class="g-val">{{ dash(loan.status) }}</text></view>
        </view>
      </view>

      <!-- 票据：缩略图点开看原图，长按可删 -->
      <view class="card">
        <view class="row1">
          <text class="sec">票据</text>
          <text class="dim">{{ invoices.length }} 张 · 合计 ¥ {{ fmtMoney(invTotal) }}</text>
        </view>

        <button v-if="canUpload" class="btn btn-ghost" @click="shoot">拍照 / 选图传票</button>
        <text v-else-if="d.status === 'DRAFT' || d.status === 'DEPT_REVIEW' || d.status === 'FINAL_REVIEW'" class="note">
          审批通过后才能传票，后端按单子状态拦，不是页面限制
        </text>

        <view v-if="invoices.length === 0" class="empty"><text>—</text></view>
        <view v-else class="imgs">
          <view v-for="(inv, i) in invoices" :key="inv.id || i" class="img-wrap">
            <image :src="inv.thumbUrl || inv.imgUrl" class="img" mode="aspectFill"
              @click="preview(i)" @error="refreshUrl(inv)" @longpress="askDelete(inv)" />
            <text class="img-cap">¥ {{ fmtMoney(inv.amount) }}</text>
            <text class="img-cap">{{ inv.seller || dash(inv.invoiceNo) }}</text>
            <text v-if="inv.dupOf" class="img-cap dup">疑似重复：{{ inv.dupOf }}</text>
          </view>
        </view>

        <view v-if="missing.length" class="mt">
          <text class="sec">还缺票</text>
          <text v-for="(m, i) in missing" :key="i" class="note">· 第 {{ m.seq }} 段 {{ m.route }}：{{ m.ticketType || m.hint }}</text>
        </view>
      </view>

      <!-- 上传后确认挂接：OCR 结果可改，改完才落库 -->
      <view class="card" v-if="upPath">
        <text class="sec">确认这张票</text>
        <text class="dim">{{ upPath.ocrSuccess ? 'OCR 已识别，核对后挂接' : 'OCR 没认出来，请手工填写' }}</text>
        <view class="cell"><text class="lbl">票号</text><input v-model="upForm.invoiceNo" class="val ipt" placeholder="发票号码" /></view>
        <view class="cell"><text class="lbl">金额</text><input v-model="upForm.amount" class="val ipt" type="digit" placeholder="0.00" /></view>
        <view class="cell"><text class="lbl">开票日期</text><input v-model="upForm.invoiceDate" class="val ipt" placeholder="如 2026-08-12" /></view>
        <view class="cell"><text class="lbl">销售方</text><input v-model="upForm.seller" class="val ipt" placeholder="开票单位" /></view>
        <picker :range="segLabels" @change="(e) => upForm.itineraryId = segIds[e.detail.value]">
          <view class="cell"><text class="lbl">挂到哪段</text><text class="val">{{ segName || '请选择' }}</text></view>
        </picker>
        <text v-if="upPath.suggestReason" class="note">AI 建议：{{ upPath.suggestReason }}</text>
        <view class="btn-row">
          <button class="btn btn-ghost" @click="cancelUpload">取消</button>
          <button class="btn" @click="confirmUpload">确认挂接</button>
        </view>
      </view>

      <!-- 出差成果 -->
      <view class="card" v-if="d.status === 'APPROVED' || d.status === 'CLOSED'">
        <text class="sec">出差成果</text>
        <view v-if="(d.results || []).length === 0" class="empty"><text>—</text></view>
        <view v-for="(r, i) in (d.results || [])" :key="i" class="it">
          <text class="it-line">{{ r.resultType }} · 折算产出 ¥ {{ fmtMoney(r.outputAmount) }}</text>
          <text class="it-sub">{{ r.resultDesc || '—' }}（{{ r.filledBy }} {{ r.filledAt }}）</text>
        </view>
        <picker :range="RESULT_TYPES" @change="(e) => rf.resultType = RESULT_TYPES[e.detail.value]">
          <view class="cell"><text class="lbl">补填类型</text><text class="val">{{ rf.resultType || '请选择' }}</text></view>
        </picker>
        <view class="cell"><text class="lbl">折算产出</text><input v-model="rf.outputAmount" class="val ipt" type="digit" placeholder="0.00" /></view>
        <textarea v-model="rf.resultDesc" class="ta" maxlength="200" placeholder="成果说明" placeholder-class="ph" />
        <button class="btn btn-ghost" @click="doBackfill">提交成果</button>
      </view>

      <!-- 流转记录 -->
      <view class="card" v-if="(d.records || []).length">
        <text class="sec">审批流转</text>
        <view v-for="(r, i) in d.records" :key="i" class="it">
          <text class="it-line">{{ r.step }} · {{ r.result }} · {{ r.approver || '—' }}</text>
          <text class="it-sub">{{ r.opinion || '—' }}（{{ r.approvedAt }}）</text>
        </view>
      </view>

      <!-- 底部操作 -->
      <view class="ops" v-if="ops.length">
        <button v-for="(o, i) in ops" :key="i" :class="['btn', o.cls || 'btn-ghost']" @click="o.run">{{ o.text }}</button>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import {
  applyDetail, invoiceList, invoiceDelete, invoiceUrl, uploadInvoice, invoiceConfirm,
  deleteDraft, closeApply, backfillResult, evalGet, loanGet
} from '../../api'
import { APPLY_STATUS, STANDARD_RESULT, RESULT_TYPES, fmtMoney, dash } from '../../utils/dict'

const d = ref(null)
const inv = ref(null)
const ev = ref(null)
const loan = ref(null)
const upPath = ref(null)
const upForm = ref({ invoiceNo: '', amount: '', invoiceDate: '', seller: '', itineraryId: null })
const rf = ref({ resultType: '', resultDesc: '', outputAmount: '' })
const applyNo = ref('')

const invoices = computed(() => (inv.value && inv.value.invoices) || [])
const missing = computed(() => (inv.value && inv.value.missing) || [])
const invTotal = computed(() => invoices.value.reduce((s, x) => s + Number(x.amount || 0), 0))
const segs = computed(() => (inv.value && inv.value.itineraries) || (d.value && d.value.itineraries) || [])
const segIds = computed(() => segs.value.map(s => s.id))
const segLabels = computed(() => segs.value.map((s, i) => `第 ${i + 1} 段 ${s.fromCity}→${s.toCity}`))
const segName = computed(() => {
  const i = segIds.value.indexOf(upForm.value.itineraryId)
  return i >= 0 ? segLabels.value[i] : ''
})
// 后端按申请单状态拦：只有已通过 / 已关闭的单子能传票
const canUpload = computed(() => d.value && (d.value.status === 'APPROVED' || d.value.status === 'CLOSED'))

const statusText = (s) => APPLY_STATUS[s] || s || '—'
const statusCls = (s) => ({
  DRAFT: 'tag-gray', DEPT_REVIEW: 'tag-amber', FINAL_REVIEW: 'tag-amber',
  APPROVED: 'tag-green', REJECTED: 'tag-red', CLOSED: 'tag-gray'
}[s] || 'tag-gray')
const stdText = (s) => (STANDARD_RESULT[s] || {}).text || '—'

const ops = computed(() => {
  const s = d.value && d.value.status
  const list = []
  if (s === 'DRAFT') {
    list.push({ text: '删除草稿', cls: 'btn-danger', run: doDelete })
  }
  if (s === 'REJECTED') {
    list.push({ text: '修改后重提', run: goEdit })
  }
  if (s === 'APPROVED' || s === 'CLOSED') {
    list.push({ text: '去报销', run: goExpense })
  }
  if (s === 'APPROVED') {
    list.push({ text: '手工结案', cls: 'btn-danger', run: doClose })
  }
  return list
})

async function load () {
  try {
    d.value = await applyDetail(applyNo.value)
    inv.value = await invoiceList(applyNo.value)
  } catch (e) { /* toast 已弹 */ }
  try { ev.value = await evalGet(applyNo.value) } catch (e) { /* 没评价就不管 */ }
  try { loan.value = await loanGet(applyNo.value) } catch (e) { /* 没借款就不管 */ }
}

onLoad((q) => {
  applyNo.value = q.applyNo || ''
  load()
})
onShow(() => { if (applyNo.value) load() })

/* ---------- 票据 ---------- */

function shoot () {
  uni.chooseImage({
    count: 1,
    sourceType: ['camera', 'album'],
    success: async (res) => {
      uni.showLoading({ title: '上传识别中…' })
      try {
        const vo = await uploadInvoice(res.tempFilePaths[0], applyNo.value)
        upPath.value = vo
        upForm.value = {
          invoiceNo: (vo.ocr && vo.ocr.invoiceNo) || '',
          amount: (vo.ocr && vo.ocr.amount) || '',
          invoiceDate: (vo.ocr && vo.ocr.invoiceDate) || '',
          seller: (vo.ocr && vo.ocr.seller) || '',
          itineraryId: vo.suggestItineraryId || (segIds.value[0] || null)
        }
      } catch (e) { /* toast 已弹 */ }
      uni.hideLoading()
    }
  })
}

function cancelUpload () { upPath.value = null }

async function confirmUpload () {
  if (!upForm.value.amount) { uni.showToast({ title: '金额不能为空', icon: 'none' }); return }
  try {
    await invoiceConfirm({
      applyNo: applyNo.value,
      imgPath: upPath.value.tempPath,
      invoiceNo: upForm.value.invoiceNo || null,
      amount: Number(upForm.value.amount),
      invoiceDate: upForm.value.invoiceDate || null,
      seller: upForm.value.seller || null,
      itineraryId: upForm.value.itineraryId,
      ocrFlag: upPath.value.ocrSuccess ? 1 : 0
    })
    uni.showToast({ title: '票据已挂接', icon: 'success' })
    upPath.value = null
    inv.value = await invoiceList(applyNo.value)
  } catch (e) { /* toast 已弹 */ }
}

function preview (i) {
  const urls = invoices.value.map(x => x.imgUrl || x.thumbUrl).filter(Boolean)
  if (!urls.length) { uni.showToast({ title: '影像地址已过期，正在重取', icon: 'none' }); return }
  uni.previewImage({ urls, current: i })
}

// 影像地址带过期时间，页面停留久了会加载失败，这里换一条新的
async function refreshUrl (item) {
  try {
    const fresh = await invoiceUrl(item.id)
    if (fresh) Object.assign(item, { imgUrl: fresh.imgUrl, thumbUrl: fresh.thumbUrl })
  } catch (e) { /* 换不到就保持原样 */ }
}

function askDelete (item) {
  uni.showActionSheet({
    itemList: ['删除这张票据'],
    success: async (r) => {
      if (r.tapIndex !== 0) return
      await invoiceDelete(item.id)
      inv.value = await invoiceList(applyNo.value)
    }
  })
}

/* ---------- 单据操作 ---------- */

async function doDelete () {
  uni.showModal({
    title: '删除草稿',
    content: `确认删除 ${applyNo.value}？删了不能恢复`,
    success: async (r) => {
      if (!r.confirm) return
      await deleteDraft(applyNo.value)
      uni.showToast({ title: '已删除', icon: 'success' })
      setTimeout(() => uni.navigateBack(), 700)
    }
  })
}

async function doClose () {
  uni.showModal({
    title: '手工结案',
    content: '结案后不能再提交报销以外的操作。名下有在途报销单会被拦下。',
    success: async (r) => {
      if (!r.confirm) return
      await closeApply(applyNo.value)
      uni.showToast({ title: '已结案', icon: 'success' })
      load()
    }
  })
}

async function doBackfill () {
  if (!rf.value.resultType) { uni.showToast({ title: '请选择成果类型', icon: 'none' }); return }
  try {
    await backfillResult(applyNo.value, {
      partnerId: d.value.partnerId,
      resultType: rf.value.resultType,
      resultDesc: rf.value.resultDesc || null,
      outputAmount: Number(rf.value.outputAmount || 0)
    })
    uni.showToast({ title: '成果已回填', icon: 'success' })
    rf.value = { resultType: '', resultDesc: '', outputAmount: '' }
    load()
  } catch (e) { /* toast 已弹 */ }
}

const goEdit = () => uni.navigateTo({ url: `/pages/form/form?applyNo=${encodeURIComponent(applyNo.value)}` })
const goExpense = () => uni.navigateTo({ url: `/pages/expense/form?applyNo=${encodeURIComponent(applyNo.value)}` })
</script>

<style>
.cell { display: flex; justify-content: space-between; align-items: center; min-height: 88rpx; border-bottom: 1rpx solid #f0f1f3; }
.lbl { font-size: 28rpx; color: #5f6773; flex-shrink: 0; }
.val { font-size: 28rpx; color: #1f2328; }
.ipt { flex: 1; margin-left: 24rpx; text-align: right; }
.ph { color: #b9c0ca; font-size: 28rpx; }
.ta { width: 100%; height: 120rpx; margin: 16rpx 0; font-size: 28rpx; }
.reason { display: block; margin-top: 14rpx; font-size: 30rpx; color: #1f2328; }
.no { font-size: 26rpx; color: #8a919c; }
.note { display: block; margin-top: 8rpx; font-size: 24rpx; color: #8a919c; }
.it { margin-top: 16rpx; }
.it-line { display: block; font-size: 28rpx; color: #1f2328; }
.it-sub { display: block; font-size: 24rpx; color: #8a919c; margin-top: 4rpx; }
.img-wrap { width: 200rpx; }
.img-cap { display: block; font-size: 22rpx; color: #8a919c; }
.dup { color: #d83931; }
.ops { margin-top: 12rpx; }
</style>
