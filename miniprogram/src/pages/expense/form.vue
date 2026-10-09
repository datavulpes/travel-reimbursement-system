<template>
  <view class="page">
    <view class="card">
      <text class="sec">报销申请单</text>
      <text class="no">{{ applyNo || '—' }}</text>
      <button class="btn-ghost btn" @click="pickApply">从我的单据里选（已通过）</button>
    </view>

    <view class="card" v-if="pv">
      <view class="row1">
        <text class="sec">提交前预检</text>
        <text :class="['tag', preTag]">{{ preText }}</text>
      </view>
      <view class="grid">
        <view class="g-item"><text class="g-lbl">票面合计</text><text class="g-val money">¥ {{ fmtMoney(pv.totalAmount) }}</text></view>
        <view class="g-item"><text class="g-lbl">借款冲抵</text><text class="g-val">¥ {{ fmtMoney(pv.offsetAmount) }}</text></view>
        <view class="g-item"><text class="g-lbl">实付金额</text><text class="g-val money">¥ {{ fmtMoney(pv.netAmount) }}</text></view>
        <view class="g-item"><text class="g-lbl">异常项</text><text class="g-val">{{ (pv.checks || []).length }}</text></view>
      </view>

      <view v-if="(pv.checks || []).length" class="mt">
        <view v-for="(c, i) in pv.checks" :key="i" class="chk">
          <text :class="['tag', c.level === 'BLOCK' ? 'tag-red' : 'tag-amber']">{{ c.type }}</text>
          <text class="chk-desc">{{ c.desc }}</text>
        </view>
      </view>

      <view v-if="pv.needForceReason" class="mt">
        <text class="sec">强制说明（必填）</text>
        <textarea v-model="forceReason" class="ta" maxlength="200" placeholder="说清楚为什么明知有问题还要提交" placeholder-class="ph" />
      </view>

      <view class="mt">
        <text class="sec">备注</text>
        <textarea v-model="remark" class="ta" maxlength="200" placeholder="选填" placeholder-class="ph" />
      </view>

      <button class="btn btn-primary" :disabled="submitting" @click="submit">提交报销单</button>
    </view>

    <view v-else class="empty"><text>先选一个已通过的申请单，预检结果会在这里显示</text></view>
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { expensePreview, expenseSubmit, applyPage } from '../../api'
import { fmtMoney } from '../../utils/dict'

const applyNo = ref('')
const pv = ref(null)
const forceReason = ref('')
const remark = ref('')
const submitting = ref(false)

const preText = computed(() => ({
  PASS: '预检通过', WARN: '有提醒', FORCE: '需填强制说明'
}[pv.value.precheckResult] || pv.value.precheckResult || '—'))
const preTag = computed(() => ({
  PASS: 'tag-green', WARN: 'tag-amber', FORCE: 'tag-red'
}[pv.value.precheckResult] || 'tag-gray'))

onLoad(async (q) => {
  if (q && q.applyNo) { applyNo.value = q.applyNo; await preview() }
})

async function preview () {
  if (!applyNo.value) return
  try { pv.value = await expensePreview(applyNo.value) } catch (e) { /* toast 已弹 */ }
}

// 后端只拦「这个申请有没有提交过报销单」，不拦申请单状态，
// 所以这里把单据全列出来让人挑，重复提交时后端会给一句人话提示
function pickApply () {
  uni.showLoading({ title: '加载中…' })
  applyPage({ pageNum: 1, pageSize: 50 })
    .then((page) => {
      const list = page.list || []
      uni.hideLoading()
      if (!list.length) { uni.showToast({ title: '没有已通过的申请单', icon: 'none' }); return }
      uni.showActionSheet({
        itemList: list.map(x => `${x.applyNo} · ${x.reason || ''}`),
        success: async (r) => { applyNo.value = list[r.tapIndex].applyNo; await preview() }
      })
    })
    .catch(() => uni.hideLoading())
}

async function submit () {
  if (pv.value.needForceReason && !forceReason.value.trim()) {
    uni.showToast({ title: '预检有问题，先填强制说明', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const r = await expenseSubmit({
      applyNo: applyNo.value,
      forceReason: forceReason.value || null,
      remark: remark.value || null
    })
    uni.showToast({ title: '已提交', icon: 'success' })
    setTimeout(() => uni.redirectTo({ url: `/pages/expense/detail?reportNo=${encodeURIComponent(r.reportNo)}` }), 800)
  } catch (e) { /* toast 已弹 */ }
  submitting.value = false
}
</script>

<style>
.sec { display: block; font-size: 28rpx; font-weight: 500; color: #1f2328; margin-bottom: 8rpx; }
.no { display: block; margin-top: 8rpx; font-size: 26rpx; color: #8a919c; }
.ta { width: 100%; height: 140rpx; margin: 12rpx 0; font-size: 28rpx; }
.ph { color: #b9c0ca; font-size: 28rpx; }
.chk { display: flex; align-items: flex-start; margin-top: 16rpx; }
.chk-desc { flex: 1; margin-left: 16rpx; font-size: 26rpx; color: #1f2328; }
</style>
