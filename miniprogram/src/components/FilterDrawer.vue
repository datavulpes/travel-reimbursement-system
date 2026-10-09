<!-- 左侧筛选抽屉，申请单列表与报销列表共用。
     父页面用 v-if 控制挂载（不在组件里做显隐），mount 时就把当前条件读进来。
     控件三种：select 胶囊单选 / text 关键字 / date 起止日期（两个原生 picker）。
     后端只认 status 与 keyword，其余条件在已加载的列表里筛，口径由父页面的 localNote 写明。 -->
<template>
  <view class="fd" @touchmove.stop.prevent="noop">
    <view class="fd-mask" @click="emit('close')" />
    <view class="fd-panel">
      <view class="fd-head">
        <text class="fd-title">筛选</text>
        <text class="fd-x" @click="emit('close')">×</text>
      </view>

      <scroll-view scroll-y class="fd-body">
        <view v-for="g in groups" :key="g.key" class="fd-sec">
          <text class="fd-lbl">{{ g.label }}</text>

          <!-- 关键字 -->
          <input v-if="g.type === 'text'" class="fd-input" :value="draft[g.key]"
            :placeholder="g.placeholder || '输入关键字'" placeholder-class="fd-ph"
            @input="draft[g.key] = $event.detail.value" />

          <!-- 起止日期 -->
          <view v-else-if="g.type === 'date'" class="fd-dates">
            <picker mode="date" :value="draft[g.key].from" @change="onDate(g.key, 'from', $event)">
              <view :class="['fd-date', draft[g.key].from ? 'fd-date-on' : '']">
                <text>{{ draft[g.key].from || '开始日期' }}</text>
              </view>
            </picker>
            <text class="fd-sep">至</text>
            <picker mode="date" :value="draft[g.key].to" @change="onDate(g.key, 'to', $event)">
              <view :class="['fd-date', draft[g.key].to ? 'fd-date-on' : '']">
                <text>{{ draft[g.key].to || '结束日期' }}</text>
              </view>
            </picker>
            <text v-if="draft[g.key].from || draft[g.key].to" class="fd-clear" @click="clearDate(g.key)">清除</text>
          </view>

          <!-- 胶囊单选 -->
          <view v-else class="fd-opts">
            <view v-for="o in g.options" :key="o.value"
              :class="['fd-opt', draft[g.key] === o.value ? 'fd-opt-on' : '']"
              @click="draft[g.key] = o.value">
              <text>{{ o.label }}</text>
            </view>
          </view>
        </view>

        <text v-if="localNote" class="fd-note">{{ localNote }}</text>
      </scroll-view>

      <view class="fd-foot">
        <view class="fd-btn fd-reset" @click="doReset"><text>重置</text></view>
        <view class="fd-btn fd-ok" @click="doApply"><text>看结果</text></view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { reactive } from 'vue'

const props = defineProps({
  // [{ key, label, type: 'select' | 'text' | 'date', options, placeholder }]
  // select 组第一个选项约定为「不限」（value 为空串）
  groups: { type: Array, default: () => [] },
  values: { type: Object, default: () => ({}) },
  localNote: { type: String, default: '' }
})
const emit = defineEmits(['close', 'apply'])

const draft = reactive({})

for (const g of props.groups) {
  const v = props.values[g.key]
  if (g.type === 'date') draft[g.key] = { from: (v && v.from) || '', to: (v && v.to) || '' }
  else draft[g.key] = (v === null || v === undefined) ? '' : v
}

const noop = () => {}
const onDate = (k, which, e) => { draft[k][which] = e.detail.value }
const clearDate = (k) => { draft[k].from = ''; draft[k].to = '' }

function snapshot () {
  return JSON.parse(JSON.stringify(draft))
}

function doApply () {
  emit('apply', snapshot())
  emit('close')
}

function doReset () {
  for (const g of props.groups) {
    if (g.type === 'date') draft[g.key] = { from: '', to: '' }
    else draft[g.key] = (g.options && g.options[0]) ? g.options[0].value : ''
  }
  emit('apply', snapshot())
  emit('close')
}
</script>

<style>
.fd { position: fixed; left: 0; top: 0; right: 0; bottom: 0; z-index: 9999; }
.fd-mask { position: absolute; left: 0; top: 0; right: 0; bottom: 0; background: rgba(0, 0, 0, 0.45); }
.fd-panel { position: absolute; left: 0; top: 0; bottom: 0; width: 640rpx; background: #fff;
  border-radius: 0 24rpx 24rpx 0; display: flex; flex-direction: column; }

.fd-head { display: flex; justify-content: space-between; align-items: center;
  padding: 36rpx 36rpx 24rpx; }
.fd-title { font-size: 34rpx; font-weight: 600; color: #1f2328; }
.fd-x { font-size: 44rpx; color: #8a919c; line-height: 1; padding: 0 8rpx; }

.fd-body { flex: 1; height: 0; padding: 0 36rpx; box-sizing: border-box; }
.fd-sec { margin-bottom: 36rpx; }
.fd-lbl { display: block; font-size: 26rpx; color: #8a919c; margin-bottom: 18rpx; }
.fd-input { background: #e7effb; border-radius: 12rpx; padding: 18rpx 24rpx; font-size: 28rpx;
  color: #1f2328; }
.fd-ph { color: #b0b7c0; }
.fd-opts { display: flex; flex-wrap: wrap; gap: 16rpx; }
.fd-opt { padding: 12rpx 30rpx; border-radius: 999rpx; background: #e7effb;
  font-size: 26rpx; color: #5f6773; border: 2rpx solid #e7effb; }
.fd-opt-on { background: #eef3fe; color: #2f6fed; border-color: #2f6fed; font-weight: 500; }

.fd-dates { display: flex; align-items: center; flex-wrap: wrap; gap: 12rpx; }
.fd-date { padding: 14rpx 26rpx; border-radius: 12rpx; background: #e7effb;
  font-size: 26rpx; color: #8a919c; }
.fd-date-on { background: #eef3fe; color: #2f6fed; }
.fd-sep { font-size: 24rpx; color: #b0b7c0; }
.fd-clear { font-size: 24rpx; color: #d83931; margin-left: 8rpx; }

.fd-note { display: block; font-size: 22rpx; color: #b0b7c0; line-height: 1.6; }

.fd-foot { display: flex; gap: 20rpx; padding: 24rpx 36rpx calc(24rpx + env(safe-area-inset-bottom));
  border-top: 2rpx solid #f0f1f3; }
.fd-btn { flex: 1; text-align: center; padding: 22rpx 0; border-radius: 14rpx; font-size: 30rpx; }
.fd-reset { background: #e7effb; color: #5f6773; }
.fd-ok { background: #2f6fed; color: #fff; }
</style>
