<template>
  <view class="page page-tab">
    <view class="card">
      <view class="hd">
        <text class="sec">AI 运行状况</text>
        <text class="weak">模型有没有在正常工作，看这一页，不用猜</text>
      </view>
      <view class="hd-r">
        <picker :range="daysOpts" range-key="label" :value="daysIdx" @change="onDays">
          <view class="picker">{{ daysOpts[daysIdx].label }}</view>
        </picker>
        <text class="refresh" @click="load">刷新</text>
      </view>

      <view v-if="loading" class="ph">正在统计…</view>
      <template v-else>
        <view class="stats">
          <view class="stat">
            <text class="s-l">调用次数</text>
            <text class="s-v">{{ d.totalCnt || 0 }}</text>
          </view>
          <view class="stat">
            <text class="s-l">成功</text>
            <text class="s-v ok">{{ d.successCnt || 0 }}</text>
            <text class="s-s">占 {{ pct(d.successCnt, d.totalCnt) }}</text>
          </view>
          <view class="stat">
            <text class="s-l">降级</text>
            <text class="s-v" :class="{ warn: (d.fallbackCnt || 0) > 0 }">{{ d.fallbackCnt || 0 }}</text>
            <text class="s-s">走了本地规则</text>
          </view>
          <view class="stat">
            <text class="s-l">平均耗时</text>
            <text class="s-v">{{ d.avgCostMs || 0 }} ms</text>
          </view>
          <view class="stat">
            <text class="s-l">建议采纳率</text>
            <text class="s-v">{{ d.adoptRate == null ? '—' : pct(d.adoptRate, 1, 0) }}</text>
            <text class="s-s">已决定的里被采用的</text>
          </view>
        </view>

        <view class="muted">
          模型 {{ d.model || '未启用' }} ·
          {{ d.enabled ? (d.usable ? '配置完整' : '配置不完整，会一直降级') : 'AI 总开关已关闭' }}
          <text v-if="!d.logEnabled"> · 留痕已关闭，不会有新数据</text>
        </view>
      </template>
    </view>

    <view class="card" v-if="!loading">
      <text class="sec">分场景</text>
      <view v-if="(d.scenes || []).length" class="rows">
        <view v-for="(s, i) in d.scenes" :key="i" class="srow">
          <text class="sname">{{ s.sceneText }}</text>
          <view class="snums">
            <text>调用 {{ s.cnt }}</text>
            <text class="ok">成功 {{ s.successCnt }}</text>
            <text :class="{ warn: s.fallbackCnt > 0 }">降级 {{ s.fallbackCnt }}</text>
            <text>{{ s.avgCostMs }} ms</text>
            <text>采纳 {{ s.adoptRate == null ? '—' : pct(s.adoptRate, 1, 0) }}</text>
          </view>
        </view>
      </view>
      <view v-else class="empty">这段时间没有分场景数据</view>
    </view>

    <view class="card" v-if="!loading">
      <text class="sec">最近留痕</text>
      <view v-if="(d.recent || []).length" class="rows">
        <view v-for="(r, i) in d.recent" :key="i" class="rrow">
          <view class="r-top">
            <text class="r-time">{{ r.createdAt }}</text>
            <text :class="['r-tag', r.errorMessage ? 'err' : (r.fallback === 1 ? 'fb' : 'ok')]">
              {{ r.errorMessage ? '失败' : (r.fallback === 1 ? '降级' : '成功') }}
            </text>
          </view>
          <view class="r-mid">
            <text>{{ r.sceneText }}</text>
            <text class="mono">{{ r.refNo || '—' }}</text>
            <text>{{ r.model || '—' }}</text>
            <text>{{ r.costMs == null ? '—' : r.costMs + ' ms' }}</text>
            <text>采纳 {{ r.accepted === 1 ? '是' : r.accepted === 0 ? '否' : '—' }}</text>
          </view>
          <text v-if="r.errorMessage" class="r-err">{{ r.errorMessage }}</text>
        </view>
      </view>
      <view v-else class="empty">{{ d.logEnabled ? '这段时间没有 AI 调用记录' : '留痕开关关着，不会有任何记录' }}</view>
    </view>

    <view class="card" v-if="!loading">
      <text class="sec">最常被改的字段</text>
      <text class="weak">系统给的值被人改掉几次，改得越多越该回去改那句 prompt</text>
      <view v-if="(d.topCorrected || []).length" class="rows">
        <view v-for="(c, i) in d.topCorrected" :key="i" class="crow">
          <view class="c-top">
            <text class="c-name">{{ c.fieldText }}</text>
            <text class="c-cnt">{{ c.cnt }} 次</text>
          </view>
          <view class="c-bar">
            <view class="c-fill" :style="{ width: barWidth(c.cnt) }" />
            <text class="c-pct">{{ pct(c.cnt, correctedSum, 0) }}</text>
          </view>
        </view>
      </view>
      <view v-else class="empty">这段时间没有人工修正记录</view>
    </view>

    <TabBar current="ai" />
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { aiStatus } from '../../api'
import TabBar from '../../components/TabBar.vue'

const loading = ref(false)
const daysOpts = [
  { label: '最近 1 天', value: 1 },
  { label: '最近 7 天', value: 7 },
  { label: '最近 30 天', value: 30 },
  { label: '全部', value: 365 }
]
const daysIdx = ref(1)
const d = ref({})

function pct (num, den, digits = 0) {
  if (num === null || num === undefined || !den) return '—'
  return (Number(num) / Number(den) * 100).toFixed(digits) + '%'
}

const correctedSum = computed(() =>
  (d.value.topCorrected || []).reduce((s, r) => s + (r.cnt || 0), 0))
function barWidth (cnt) {
  const max = Math.max(1, ...(d.value.topCorrected || []).map((r) => r.cnt || 0))
  return Math.round(((cnt || 0) / max) * 100) + '%'
}

function onDays (e) {
  daysIdx.value = Number(e.detail.value)
  load()
}

async function load () {
  loading.value = true
  try {
    d.value = await aiStatus(daysOpts[daysIdx.value].value) || {}
  } catch (e) {
    uni.showToast({ title: '没能取到 AI 运行状况', icon: 'none' })
  } finally {
    loading.value = false
  }
}

// 数据实时聚合，每次进页面重拉一次
onShow(load)
</script>

<style>
.sec { display: block; font-size: 30rpx; font-weight: 500; color: #1f2328; }
.weak { display: block; font-size: 24rpx; color: #8a919c; margin-top: 6rpx; }
.hd { display: flex; align-items: baseline; gap: 12rpx; flex-wrap: wrap; }
.hd-r { display: flex; align-items: center; gap: 16rpx; margin-top: 16rpx; }
.picker { font-size: 24rpx; color: #1f2328; background: #f5f6f8; border: 2rpx solid #e6e8eb;
  padding: 8rpx 20rpx; border-radius: 10rpx; }
.refresh { font-size: 24rpx; color: #1a56a8; }
.ph { text-align: center; color: #9aa2ad; font-size: 26rpx; padding: 40rpx 0; }

.stats { display: flex; flex-wrap: wrap; gap: 14rpx; margin-top: 16rpx; }
.stat { width: calc(33.33% - 10rpx); box-sizing: border-box; border: 2rpx solid #e6e8eb;
  border-radius: 10rpx; padding: 14rpx 16rpx; }
.s-l { display: block; font-size: 22rpx; color: #8a919c; }
.s-v { display: block; font-size: 36rpx; font-weight: 600; margin-top: 4rpx; }
.s-v.ok { color: #2e7d32; }
.s-v.warn { color: #ad6800; }
.s-s { display: block; font-size: 20rpx; color: #8a919c; margin-top: 2rpx; }
.muted { display: block; margin-top: 14rpx; font-size: 22rpx; color: #8a919c; line-height: 1.6; }

.rows { margin-top: 14rpx; }
.srow { padding: 14rpx 0; border-bottom: 2rpx solid #f0f1f3; }
.srow:last-child { border-bottom: none; }
.sname { font-size: 26rpx; color: #1f2328; font-weight: 500; }
.snums { display: flex; flex-wrap: wrap; gap: 18rpx; margin-top: 8rpx; font-size: 22rpx; color: #5f6773; }
.snums .ok { color: #2e7d32; }
.snums .warn { color: #ad6800; }

.rrow { padding: 14rpx 0; border-bottom: 2rpx solid #f0f1f3; }
.rrow:last-child { border-bottom: none; }
.r-top { display: flex; justify-content: space-between; align-items: center; }
.r-time { font-size: 22rpx; color: #8a919c; }
.r-tag { font-size: 20rpx; padding: 2rpx 12rpx; border-radius: 6rpx; }
.r-tag.ok { background: #e8f5e9; color: #2e7d32; }
.r-tag.fb { background: #fff7e6; color: #ad6800; }
.r-tag.err { background: #fdecec; color: #d83931; }
.r-mid { display: flex; flex-wrap: wrap; gap: 16rpx; margin-top: 8rpx; font-size: 22rpx; color: #5f6773; }
.r-mid .mono { color: #1f2328; }
.r-err { display: block; margin-top: 6rpx; font-size: 22rpx; color: #d83931; }

.crow { padding: 14rpx 0; border-bottom: 2rpx solid #f0f1f3; }
.crow:last-child { border-bottom: none; }
.c-top { display: flex; justify-content: space-between; align-items: center; }
.c-name { font-size: 26rpx; color: #1f2328; }
.c-cnt { font-size: 22rpx; color: #8a919c; }
.c-bar { display: flex; align-items: center; gap: 12rpx; margin-top: 8rpx; }
.c-fill { height: 14rpx; border-radius: 7rpx; background: #ad6800; max-width: 260rpx; }
.c-pct { font-size: 22rpx; color: #8a919c; }
.empty { text-align: center; color: #9aa2ad; font-size: 26rpx; padding: 40rpx 0; }
</style>
