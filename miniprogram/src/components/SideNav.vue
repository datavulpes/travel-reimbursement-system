<template>
  <!--
    左侧分区导航。概览页原来是一整页往下堆卡片，八个区块一路滑到底，
    找「趋势」要滑四次屏；改成左边一列索引、右边只渲染选中的那一块。
    父页面用 v-if 切区块，不是切 tab 组件——一次只挂一块，切走就卸载，
    各块自己的筛选状态由父页面持有，切回来还在。
  -->
  <scroll-view class="sn" scroll-y>
    <view v-for="s in items" :key="s.key"
      :class="['sn-i', s.key === current ? 'on' : '']"
      @click="pick(s)">
      <text class="sn-t">{{ s.text }}</text>
    </view>
  </scroll-view>
</template>

<script setup>
const props = defineProps({
  items: { type: Array, default: () => [] },
  current: { type: String, default: '' }
})
const emit = defineEmits(['switch'])

function pick (s) {
  if (s.key === props.current) return
  emit('switch', s.key)
}
</script>

<style>
.sn { width: 168rpx; flex: none; height: 100%; background: #fff;
  border-radius: 16rpx; padding: 12rpx 0; box-sizing: border-box; }
.sn-i { display: flex; align-items: center; height: 96rpx; padding: 0 20rpx;
  border-left: 6rpx solid transparent; box-sizing: border-box; }
.sn-i.on { background: #eef3fe; border-left-color: #2f6fed; }
.sn-t { font-size: 24rpx; color: #5f6773; line-height: 1.25; }
.sn-i.on .sn-t { color: #2f6fed; font-weight: 500; }
</style>
