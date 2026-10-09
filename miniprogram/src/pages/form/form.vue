<template>
  <view class="page">
    <view class="card">
      <text class="sec">出差事由</text>
      <textarea v-model="form.reason" class="ta" maxlength="200" placeholder="写清楚去干什么，审批人看的就是这段" placeholder-class="ph" />
      <button class="btn-ai" :disabled="aiLoading" @click="runAdvice">
        {{ aiLoading ? 'AI 分析中…' : 'AI 分析（帮我看看怎么填）' }}
      </button>
      <view v-if="advice" class="ai-box">
        <text v-if="advice.sourceNote" class="note">{{ advice.sourceNote }}</text>
        <text v-for="(p, i) in (advice.items || [])" :key="i" class="ai-line">· {{ p.text }}</text>
        <text v-if="advice.rewrite" class="ai-line rewrite">事由改写：{{ advice.rewrite }}</text>
      </view>

      <picker :range="TRIP_TYPES" @change="(e) => form.tripType = TRIP_TYPES[e.detail.value]">
        <view class="cell"><text class="lbl">出差类型</text><text :class="form.tripType ? 'val' : 'ph'">{{ form.tripType || '请选择' }}</text></view>
      </picker>
      <view v-if="form.tripType === '其他'" class="cell">
        <text class="lbl">类型补充</text>
        <input v-model="form.tripTypeNote" class="val ipt" placeholder="选了「其他」时补充说明" placeholder-class="ph" />
      </view>

      <picker :range="partners" range-key="name" @change="(e) => form.partnerId = partners[e.detail.value].id">
        <view class="cell"><text class="lbl">关联对象</text><text :class="form.partnerId ? 'val' : 'ph'">{{ partnerName || '请选择' }}</text></view>
      </picker>
    </view>

    <view class="card">
      <view class="row1">
        <text class="sec">行程</text>
        <text class="add" @click="addSeg">+ 加一段</text>
      </view>
      <view v-for="(t, i) in trips" :key="i" class="seg">
        <view class="row1">
          <text class="seg-title">第 {{ i + 1 }} 段</text>
          <text v-if="trips.length > 1" class="del" @click="delSeg(i)">删除</text>
        </view>
        <picker :range="CITY_LEVELS" @change="(e) => t.cityLevel = CITY_LEVELS[e.detail.value]">
          <view class="cell"><text class="lbl">城市等级</text><text :class="t.cityLevel ? 'val' : 'ph'">{{ t.cityLevel || '请选择' }}</text></view>
        </picker>
        <picker :range="TRAFFIC_TYPES" @change="(e) => t.trafficType = TRAFFIC_TYPES[e.detail.value]">
          <view class="cell"><text class="lbl">交通方式</text><text :class="t.trafficType ? 'val' : 'ph'">{{ t.trafficType || '请选择' }}</text></view>
        </picker>
        <view class="cell"><text class="lbl">出发地</text><input v-model="t.fromCity" class="val ipt" placeholder="如 广州" placeholder-class="ph" /></view>
        <view class="cell"><text class="lbl">目的地</text><input v-model="t.toCity" class="val ipt" placeholder="如 佛山" placeholder-class="ph" /></view>
        <picker mode="date" @change="(e) => t.startDate = e.detail.value">
          <view class="cell"><text class="lbl">开始日期</text><text :class="t.startDate ? 'val' : 'ph'">{{ t.startDate || '请选择' }}</text></view>
        </picker>
        <picker mode="date" @change="(e) => t.endDate = e.detail.value">
          <view class="cell"><text class="lbl">结束日期</text><text :class="t.endDate ? 'val' : 'ph'">{{ t.endDate || '请选择' }}</text></view>
        </picker>
      </view>
    </view>

    <view class="card">
      <text class="sec">预估费用</text>
      <view class="cell"><text class="lbl">预估交通</text><input v-model="form.estTraffic" class="val ipt" type="digit" placeholder="0.00" placeholder-class="ph" /></view>
      <view class="cell"><text class="lbl">预估住宿</text><input v-model="form.estHotel" class="val ipt" type="digit" placeholder="0.00" placeholder-class="ph" /></view>
      <view class="cell"><text class="lbl">合计</text><text class="val money">¥ {{ total }}</text></view>
      <button class="btn-ref" @click="loadRef">查同类花费参考</button>
      <view v-if="refData" class="ref-box">
        <text class="ref-line">近 12 个月同类单据 {{ refData.sampleCnt }} 单{{ refData.relaxed ? '（已放宽口径）' : '' }}</text>
        <text class="ref-line">交通 ¥ {{ fmtMoney(refData.minTraffic) }} ~ {{ fmtMoney(refData.maxTraffic) }}（均 {{ fmtMoney(refData.avgTraffic) }}）</text>
        <text class="ref-line">住宿 ¥ {{ fmtMoney(refData.minHotel) }} ~ {{ fmtMoney(refData.maxHotel) }}（均 {{ fmtMoney(refData.avgHotel) }}）</text>
        <text class="ref-line">你这单 ¥ {{ fmtMoney(refData.estAmount) }}，落在参考区间 {{ refData.posInRange == null ? '—' : refData.posInRange }}</text>
        <text v-if="refData.lowSample" class="note">样本偏少，仅供参考</text>
        <text class="note">合计区间不等于交通与住宿相加，分位数不可加</text>
      </view>
    </view>

    <view class="card" v-if="applyNo">
      <text class="sec">票据</text>
      <text class="note">这个单子还在填。票据要等审批通过后在详情页上传，后端按单据状态拦。</text>
    </view>

    <view class="foot">
      <button class="btn ghost" :disabled="saving" @click="save(true)">存草稿</button>
      <button class="btn" :disabled="saving" @click="save(false)">{{ applyNo ? '重新提交' : '提交审批' }}</button>
    </view>
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import {
  partnerList, saveDraft, submitApply, resubmitApply, applyDetail, applyAdvice, costRef
} from '../../api'
import { TRIP_TYPES, TRAFFIC_TYPES, CITY_LEVELS, fmtMoney } from '../../utils/dict'

const form = ref({ reason: '', tripType: '', tripTypeNote: '', partnerId: null, estTraffic: '', estHotel: '' })
const trips = ref([blankTrip()])
const partners = ref([])
const applyNo = ref('')
const saving = ref(false)
const advice = ref(null)
const aiLoading = ref(false)
// 别叫 ref：会遮蔽 vue 的 ref 函数
const refData = ref(null)

function blankTrip () {
  return { seq: 1, fromCity: '', toCity: '', startDate: '', endDate: '', trafficType: '', cityLevel: '' }
}

const total = computed(() => {
  const t = Number(form.value.estTraffic || 0) + Number(form.value.estHotel || 0)
  return t.toFixed(2)
})
const partnerName = computed(() => {
  const p = partners.value.find(x => x.id === form.value.partnerId)
  return p ? p.name : ''
})

onLoad(async (q) => {
  try { partners.value = (await partnerList()) || [] } catch (e) { /* 拉不到留空，不回退假名单 */ }
  if (q && q.applyNo) {
    applyNo.value = q.applyNo
    try {
      const d = await applyDetail(q.applyNo)
      form.value = {
        reason: d.reason || '',
        tripType: d.tripType || '',
        tripTypeNote: d.tripTypeNote || '',
        partnerId: d.partnerId || null,
        estTraffic: d.estTraffic == null ? '' : String(d.estTraffic),
        estHotel: d.estHotel == null ? '' : String(d.estHotel)
      }
      if ((d.itineraries || []).length) {
        trips.value = d.itineraries.map((x, i) => ({
          seq: i + 1, fromCity: x.fromCity || '', toCity: x.toCity || '',
          startDate: x.startDate || '', endDate: x.endDate || '',
          trafficType: x.trafficType || '', cityLevel: x.cityLevel || ''
        }))
      }
    } catch (e) { /* 拉不到就按新建走 */ }
  }
})

const addSeg = () => trips.value.push(blankTrip())
const delSeg = (i) => trips.value.splice(i, 1)

function body () {
  const f = form.value
  return {
    applyNo: applyNo.value || undefined,
    reason: f.reason,
    partnerId: f.partnerId,
    tripType: f.tripType,
    tripTypeNote: f.tripType === '其他' ? (f.tripTypeNote || null) : null,
    estTraffic: Number(f.estTraffic || 0),
    estHotel: Number(f.estHotel || 0),
    estTotal: Number(total.value),
    itineraries: trips.value.map((t, i) => ({
      seq: i + 1,
      startDate: t.startDate, endDate: t.endDate,
      fromCity: t.fromCity, toCity: t.toCity,
      trafficType: t.trafficType, cityLevel: t.cityLevel
    }))
  }
}

function validate () {
  const f = form.value
  if (!f.reason) return '请填写出差事由'
  if (!f.tripType) return '请选择出差类型'
  for (const t of trips.value) {
    if (!t.cityLevel || !t.trafficType) return '每段都要选城市等级与交通方式'
    if (!t.fromCity || !t.toCity) return '每段都要填出发地和目的地'
    if (!t.startDate || !t.endDate) return '每段都要选起止日期'
    if (t.endDate < t.startDate) return '结束日期不能早于开始日期'
  }
  return ''
}

async function save (asDraft) {
  const err = validate()
  if (err) { uni.showToast({ title: err, icon: 'none' }); return }
  saving.value = true
  try {
    const b = body()
    const r = asDraft
      ? await saveDraft(b)
      : (applyNo.value ? await resubmitApply(applyNo.value, b) : await submitApply(b))
    applyNo.value = r.applyNo || applyNo.value
    if (asDraft) {
      uni.showToast({ title: '草稿已保存', icon: 'none' })
    } else {
      uni.showToast({ title: '已提交审批', icon: 'success' })
      setTimeout(() => uni.redirectTo({ url: `/pages/detail/detail?applyNo=${encodeURIComponent(applyNo.value)}` }), 800)
    }
  } catch (e) { /* toast 已弹 */ }
  saving.value = false
}

// AI 只给语义建议，金额一律后端算，所以这里把它算出来的金额填回表单是安全的
async function runAdvice () {
  aiLoading.value = true
  try {
    advice.value = await applyAdvice(body())
  } catch (e) { /* toast 已弹 */ }
  aiLoading.value = false
}

async function loadRef () {
  const t = trips.value[0]
  try {
    refData.value = await costRef({
      toCity: t.toCity || undefined,
      tripType: form.value.tripType || undefined,
      cityLevel: t.cityLevel || undefined,
      trafficType: t.trafficType || undefined,
      estTraffic: Number(form.value.estTraffic || 0),
      estHotel: Number(form.value.estHotel || 0)
    })
  } catch (e) { /* toast 已弹 */ }
}
</script>

<style>
.cell { display: flex; justify-content: space-between; align-items: center; min-height: 88rpx; border-bottom: 1rpx solid #f0f1f3; }
.lbl { font-size: 28rpx; color: #5f6773; flex-shrink: 0; }
.val { font-size: 28rpx; color: #1f2328; text-align: right; }
.ph { color: #b9c0ca; font-size: 28rpx; }
.ipt { flex: 1; margin-left: 24rpx; text-align: right; }
.ta { width: 100%; height: 140rpx; margin: 16rpx 0; font-size: 28rpx; }
.sec { display: block; font-size: 28rpx; font-weight: 500; color: #1f2328; margin-bottom: 8rpx; }
.note { display: block; margin-top: 8rpx; font-size: 24rpx; color: #8a919c; }
.add { font-size: 26rpx; color: #2f6fed; }
.del { font-size: 26rpx; color: #d83931; }
.seg { margin-top: 20rpx; border-top: 1rpx solid #f0f1f3; padding-top: 12rpx; }
.seg-title { font-size: 26rpx; color: #5f6773; }
.btn-ai { margin-top: 12rpx; background: #eef4fe; color: #2f6fed; font-size: 28rpx; border-radius: 12rpx; }
.btn-ref { margin-top: 16rpx; background: #f3f5f8; color: #3a4149; font-size: 28rpx; border-radius: 12rpx; }
.ai-box { margin-top: 16rpx; padding: 20rpx; background: #f7f9fc; border-radius: 12rpx; border-left: 6rpx solid #2f6fed; }
.ai-line { display: block; font-size: 26rpx; color: #1f2328; margin-top: 8rpx; }
.ai-line.rewrite { color: #2e7d32; }
.ref-box { margin-top: 16rpx; padding: 20rpx; background: #f7f9fc; border-radius: 12rpx; }
.ref-line { display: block; font-size: 26rpx; color: #1f2328; margin-top: 6rpx; }
.money { color: #d83931; font-weight: 500; }
.foot { position: fixed; left: 0; right: 0; bottom: 0; display: flex; gap: 20rpx;
  padding: 20rpx 32rpx calc(20rpx + env(safe-area-inset-bottom)); background: #fff; }
.btn { flex: 1; background: #2f6fed; color: #fff; font-size: 30rpx; border-radius: 14rpx; }
.btn.ghost { background: #eef4fe; color: #2f6fed; }
</style>
