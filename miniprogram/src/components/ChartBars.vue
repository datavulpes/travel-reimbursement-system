<template>
  <!--
    分组柱状图。小程序里没有 echarts，也不值得为两张图引入 canvas 依赖，
    直接用 view 按百分比撑高度——只要数值是相对最大值算的百分比，效果就是柱状图。
    配色跟 Web 端对齐：投入用红、产出用绿（这里不是涨跌，是两笔钱的对照）。
  -->
  <view class="cb">
    <view class="cb-lg">
      <view v-for="s in series" :key="s.name" class="cb-lg-i">
        <view class="cb-dot" :style="{ background: s.color }" />
        <text class="cb-lg-t">{{ s.name }}</text>
      </view>
    </view>

    <view class="cb-plot" :style="{ height: height + 'rpx' }">
      <view v-if="max <= 0" class="cb-none">这一期还没有可比的金额</view>
      <view v-for="(g, i) in items" :key="i" class="cb-col">
        <view class="cb-bars">
          <view v-for="(s, si) in series" :key="si"
            class="cb-bar" :style="{ height: barH(g.values[si]), background: s.color }" />
        </view>
        <text class="cb-lbl">{{ g.label }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  // [{ label, values: [投入, 产出] }]，values 的顺序与 series 一致
  items: { type: Array, default: () => [] },
  series: { type: Array, default: () => ([{ name: '投入', color: '#c0392b' }, { name: '产出', color: '#10956a' }]) },
  height: { type: Number, default: 240 }
})

const max = computed(() => {
  let m = 0
  for (const g of props.items) {
    for (const v of (g.values || [])) m = Math.max(m, Number(v) || 0)
  }
  return m
})

// 留 6% 的顶空，最高的那根不贴边
function barH (v) {
  if (max.value <= 0) return '0%'
  const p = ((Number(v) || 0) / max.value) * 94
  return p.toFixed(1) + '%'
}
</script>

<style>
.cb-lg { display: flex; justify-content: flex-end; gap: 20rpx; margin-bottom: 12rpx; }
.cb-lg-i { display: flex; align-items: center; }
.cb-dot { width: 16rpx; height: 16rpx; border-radius: 4rpx; margin-right: 8rpx; }
.cb-lg-t { font-size: 22rpx; color: #8a919c; }
.cb-plot { display: flex; align-items: flex-end; }
.cb-col { flex: 1; display: flex; flex-direction: column; align-items: center;
  justify-content: flex-end; height: 100%; }
.cb-bars { display: flex; align-items: flex-end; justify-content: center; gap: 6rpx;
  flex: 1; width: 100%; }
.cb-bar { width: 20rpx; border-radius: 4rpx 4rpx 0 0; min-height: 2rpx; }
.cb-lbl { font-size: 21rpx; color: #5f6773; margin-top: 10rpx; text-align: center; }
.cb-none { font-size: 24rpx; color: #9aa2ad; }
</style>
