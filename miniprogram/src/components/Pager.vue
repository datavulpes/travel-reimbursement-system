<!-- 列表底部分页条。
     分页是在页面已取回的数据上做的（后端每次最多给 100 条，本地筛完之后再切页），
     所以「共多少条」是筛选后的条数，不是库里总数。
     每页条数用原生 picker 选，不用 v-model——小程序端自定义组件的 v-model 不稳。 -->
<template>
  <view class="pg">
    <picker mode="selector" :range="sizeTexts" :value="sizeIdx" @change="onSize">
      <view class="pg-size">
        <text>{{ pageSize }} 条/页</text>
        <text class="pg-caret">▾</text>
      </view>
    </picker>

    <text class="pg-info">共 {{ total }} 条 · 第 {{ pageNum }}/{{ totalPages }} 页</text>

    <view class="pg-nav">
      <view :class="['pg-btn', pageNum <= 1 ? 'pg-btn-off' : '']" @click="go(pageNum - 1)">
        <text>上一页</text>
      </view>
      <view :class="['pg-btn', pageNum >= totalPages ? 'pg-btn-off' : '']" @click="go(pageNum + 1)">
        <text>下一页</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  total: { type: Number, default: 0 },
  pageNum: { type: Number, default: 1 },
  pageSize: { type: Number, default: 10 },
  sizes: { type: Array, default: () => [10, 20, 50, 100] }
})
const emit = defineEmits(['page', 'size'])

const sizeTexts = computed(() => props.sizes.map((n) => n + ' 条/页'))
const sizeIdx = computed(() => {
  const i = props.sizes.indexOf(props.pageSize)
  return i < 0 ? 0 : i
})
const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))

function go (n) {
  if (n < 1 || n > totalPages.value || n === props.pageNum) return
  emit('page', n)
}
function onSize (e) {
  const n = props.sizes[Number(e.detail.value)]
  if (n && n !== props.pageSize) emit('size', n)
}
</script>

<style>
.pg { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap;
  gap: 16rpx; background: #fff; border-radius: 16rpx; padding: 20rpx 24rpx; margin-top: 8rpx; }
.pg-size { display: flex; align-items: center; background: #e7effb; border-radius: 12rpx;
  padding: 12rpx 20rpx; font-size: 26rpx; color: #1f2328; }
.pg-caret { margin-left: 10rpx; font-size: 22rpx; color: #8a919c; }
.pg-info { font-size: 24rpx; color: #8a919c; }
.pg-nav { display: flex; gap: 12rpx; }
.pg-btn { padding: 12rpx 24rpx; border-radius: 12rpx; background: #eef3fe;
  font-size: 26rpx; color: #2f6fed; }
.pg-btn-off { background: #e7effb; color: #b0b7c0; }
</style>
