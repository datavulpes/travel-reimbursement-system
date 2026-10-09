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
          <view class="g-item"><text class="g-lbl">实付金额</text><text class="g-val money">¥ {{ fmtMoney(d.netAmount) }}</text></view>
          <view class="g-item"><text class="g-lbl">预检</text><text class="g-val">{{ preText(d.precheckResult) }}</text></view>
        </view>
        <text v-if="d.forceReason" class="note">强制说明：{{ d.forceReason }}</text>
        <text v-if="d.reviewReason" class="note">财务意见：{{ d.reviewReason }}</text>
      </view>

      <view class="card" v-if="(d.precheckIssues || []).length">
        <text class="sec">预检发现的问题</text>
        <view v-for="(c, i) in d.precheckIssues" :key="i" class="chk">
          <text :class="['tag', c.level === 'BLOCK' ? 'tag-red' : 'tag-amber']">{{ c.type }}</text>
          <text class="chk-desc">{{ c.desc }}</text>
        </view>
      </view>

      <view class="card" v-if="d.loan">
        <text class="sec">预借款冲抵</text>
        <view class="grid">
          <view class="g-item"><text class="g-lbl">借款金额</text><text class="g-val money">¥ {{ fmtMoney(d.loan.amount) }}</text></view>
          <view class="g-item"><text class="g-lbl">本次冲抵</text><text class="g-val">¥ {{ fmtMoney(d.loan.offsetAmount) }}</text></view>
          <view class="g-item"><text class="g-lbl">还剩</text><text class="g-val">¥ {{ fmtMoney(d.loan.remainAmount) }}</text></view>
          <view class="g-item"><text class="g-lbl">状态</text><text class="g-val">{{ dash(d.loan.status) }}</text></view>
        </view>
      </view>

      <view class="card">
        <view class="row1"><text class="sec">票据</text><text class="dim">{{ (d.invoices || []).length }} 张</text></view>
        <view v-if="!(d.invoices || []).length" class="empty"><text>—</text></view>
        <view v-else class="imgs">
          <view v-for="(inv, i) in d.invoices" :key="inv.id || i" class="img-wrap">
            <image :src="inv.thumbUrl || inv.imgUrl" class="img" mode="aspectFill"
              @click="preview(i)" @error="refreshUrl(inv)" />
            <text class="img-cap">¥ {{ fmtMoney(inv.amount) }}</text>
            <text class="img-cap">{{ inv.seller || dash(inv.invoiceNo) }}</text>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { expenseDetail, invoiceUrl } from '../../api'
import { EXPENSE_STATUS, tagOf, textOf, fmtMoney, dash } from '../../utils/dict'

const d = ref(null)

const preText = (s) => ({ PASS: '通过', WARN: '有提醒', FORCE: '强制提交' }[s] || s || '—')

onLoad(async (q) => {
  try { d.value = await expenseDetail(q.reportNo) } catch (e) { /* toast 已弹 */ }
})

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
.chk { display: flex; align-items: flex-start; margin-top: 16rpx; }
.chk-desc { flex: 1; margin-left: 16rpx; font-size: 26rpx; color: #1f2328; }
.img-wrap { width: 200rpx; }
</style>
