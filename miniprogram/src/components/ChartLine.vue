<template>
  <!--
    折线图。同样是纯 view 画的：点按百分比定位，两点之间用一根旋转的细条连起来。

    为什么 width 要传固定 rpx 而不是 100%：连线的长度要用 sqrt(dx² + dy²) 算出来，
    dx / dy 必须是真实长度，而 rpx 就是真实长度（750rpx = 屏幕宽），
    所以画布宽度写死成 rpx 常量，三角函数才准。宽度写百分比这条线就会歪。
  -->
  <view class="cl">
    <view class="cl-plot" :style="{ width: width + 'rpx', height: height + 'rpx' }">
      <!-- 警戒线 0.40：超过它说明花得比赚的多 -->
      <view v-if="warnY >= 0 && warnY <= 100" class="cl-warn"
        :style="{ top: (height * warnY / 100) + 'rpx', width: width + 'rpx' }">
        <text class="cl-warn-t">警戒线 {{ warn }}</text>
      </view>

      <view v-for="(s, i) in segs" :key="'s' + i" class="cl-seg"
        :style="{ left: s.left + 'rpx', top: s.top + 'rpx', width: s.len + 'rpx',
                  transform: 'rotate(' + s.ang + 'deg)', background: color }" />

      <view v-for="(p, i) in pts" :key="'p' + i" class="cl-pt"
        :style="{ left: (p.x - 6) + 'rpx', top: (p.y - 6) + 'rpx', background: color }" />

      <view v-for="(p, i) in pts" :key="'v' + i" class="cl-val"
        :style="{ left: (p.x - 36) + 'rpx', top: (p.y - 40) + 'rpx', color: valColor(p.v) }">
        <text>{{ fmt(p.v) }}</text>
      </view>
    </view>

    <view class="cl-x" :style="{ width: width + 'rpx' }">
      <text v-for="(p, i) in pts" :key="'x' + i" class="cl-x-t"
        :style="{ width: (width / (pts.length || 1)) + 'rpx' }">{{ p.label }}</text>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  // [{ label: '04月', value: 0.38 }]
  points: { type: Array, default: () => [] },
  warn: { type: Number, default: 0.4 },
  width: { type: Number, default: 470 },
  height: { type: Number, default: 200 },
  color: { type: String, default: '#2f6fed' }
})

// 纵轴上限：至少比警戒线高一截，否则 0.40 那条线贴着顶边看不出是警戒线
const maxY = computed(() => {
  let m = props.warn * 1.35
  for (const p of props.points) m = Math.max(m, Number(p.value) || 0)
  return m > 0 ? m * 1.08 : 1
})

const pts = computed(() => {
  const n = props.points.length
  if (!n) return []
  // 只有一个点时放中间，否则会出现除零
  const step = n === 1 ? 0 : props.width / (n - 1)
  const start = n === 1 ? props.width / 2 : 0
  return props.points.map((p, i) => {
    const v = Number(p.value) || 0
    const y = props.height * (1 - Math.min(v, maxY.value) / maxY.value)
    return { x: start + step * i, y, v, label: p.label }
  })
})

const segs = computed(() => {
  const out = []
  const p = pts.value
  for (let i = 0; i < p.length - 1; i++) {
    const dx = p[i + 1].x - p[i].x
    const dy = p[i + 1].y - p[i].y
    out.push({
      left: p[i].x,
      top: p[i].y,
      len: Math.sqrt(dx * dx + dy * dy),
      // 屏幕 y 轴朝下，顺时针为正，与 CSS rotate 的方向一致
      ang: (Math.atan2(dy, dx) * 180 / Math.PI).toFixed(2)
    })
  }
  return out
})

const warnY = computed(() => (1 - props.warn / maxY.value) * 100)

const fmt = (v) => (v == null || v === '' ? '—' : Number(v).toFixed(2))
const valColor = (v) => {
  const n = Number(v) || 0
  return n <= 0.30 ? '#2e7d32' : (n <= 0.45 ? '#ad6800' : '#d83931')
}
</script>

<style>
.cl-plot { position: relative; }
.cl-warn { position: absolute; left: 0; border-top: 2rpx dashed #d83931; height: 0; }
.cl-warn-t { position: absolute; right: 0; top: -26rpx; font-size: 18rpx; color: #d83931; }
.cl-seg { position: absolute; height: 3rpx; transform-origin: left center; }
.cl-pt { position: absolute; width: 12rpx; height: 12rpx; border-radius: 6rpx;
  border: 2rpx solid #fff; }
.cl-val { position: absolute; width: 72rpx; text-align: center; font-size: 19rpx; }
.cl-x { display: flex; margin-top: 8rpx; }
.cl-x-t { font-size: 19rpx; color: #8a919c; text-align: center; }
</style>
