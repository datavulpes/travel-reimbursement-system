<template>
  <!-- 自研底部导航：原生 tabBar 写死在 pages.json 里，没法按角色换项，所以自己画。
       切页用 redirectTo——tab 之间不该层层压栈，物理返回键退小程序才符合预期 -->
  <view class="tabb">
    <view v-for="t in tabs" :key="t.key" class="tabb-i" @click="go(t)">
      <image class="tabb-ic" :src="t.key === current ? t.on : t.icon" mode="aspectFit" />
      <text :class="['tabb-t', t.key === current ? 'on' : '']">{{ t.text }}</text>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import { tabsOf } from '../utils/tabs'
import { role } from '../utils/session'

const props = defineProps({
  // 当前页的 key，与 utils/tabs.js 里的 key 对齐
  current: { type: String, default: '' }
})

const tabs = computed(() => tabsOf(role()))

function go (t) {
  if (t.key === props.current) return
  uni.redirectTo({ url: t.url })
}
</script>

<style>
.tabb { position: fixed; left: 0; right: 0; bottom: 0; z-index: 99;
  display: flex; background: #fff; border-top: 2rpx solid #eceff3;
  padding-bottom: env(safe-area-inset-bottom); }
.tabb-i { flex: 1; height: 108rpx; display: flex; flex-direction: column;
  align-items: center; justify-content: center; }
.tabb-ic { width: 44rpx; height: 44rpx; }
.tabb-t { margin-top: 4rpx; font-size: 22rpx; color: #8a919c; }
.tabb-t.on { color: #2f6fed; }
</style>
