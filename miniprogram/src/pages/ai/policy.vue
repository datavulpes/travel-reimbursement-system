<template>
  <view class="page page-tab">
    <view class="card">
      <text class="sec">AI 助手 · 差旅规定</text>
      <text class="note">用大白话问差旅 / 报销的规定，系统照库里现行的规定答，答不上来会直说。</text>

      <view class="ask-row">
        <input class="ask-input" v-model="question" maxlength="100"
          placeholder="比如：去深圳坐飞机能报多少？超标了怎么提交？"
          placeholder-style="color:#b6bcc4" confirm-type="search" @confirm="ask" />
        <text class="ask-btn" :class="{ disabled: !question.trim() || loading }" @click="ask">提问</text>
      </view>

      <view v-if="result.question" class="answer">
        <view class="line">
          <text class="mk">问</text>
          <text class="txt">{{ result.question }}</text>
        </view>
        <view class="line" :class="{ miss: result.answerable === false }">
          <text class="mk" :class="{ miss: result.answerable === false }">答</text>
          <view class="ans">
            <text class="txt">{{ result.answer }}</text>
            <text v-if="result.local" class="local">本地规则</text>
          </view>
        </view>

        <view v-if="(result.facts || []).length" class="facts">
          <text class="facts-t">依据</text>
          <view v-for="(f, i) in result.facts" :key="i" class="fact">{{ f }}</view>
        </view>

        <view v-if="(result.allFacts || []).length" class="facts">
          <text class="facts-t">库里现行的规定（{{ result.allFacts.length }} 条）</text>
          <view v-for="(f, i) in result.allFacts" :key="i" class="fact dim">{{ f }}</view>
        </view>
      </view>
    </view>

    <TabBar current="ai" />
  </view>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { policyQa } from '../../api'
import TabBar from '../../components/TabBar.vue'

const question = ref('')
const loading = ref(false)
const result = reactive({})

async function ask () {
  const q = question.value.trim()
  if (!q || loading.value) return
  loading.value = true
  try {
    // 先清空上一句，避免新答案叠在旧结构上
    Object.keys(result).forEach((k) => delete result[k])
    Object.assign(result, await policyQa(q))
  } catch (e) {
    uni.showToast({ title: '这次没答上来，稍后再试', icon: 'none' })
  } finally {
    loading.value = false
  }
}

// 回到页面不重拉：问答是一次性的，重拉会把上一条答案清空
onShow(() => {})
</script>

<style>
.sec { display: block; font-size: 30rpx; font-weight: 500; color: #1f2328; }
.note { display: block; margin-top: 8rpx; font-size: 24rpx; color: #8a919c; line-height: 1.6; }
.ask-row { display: flex; align-items: center; gap: 16rpx; margin-top: 24rpx; }
.ask-input { flex: 1; height: 76rpx; padding: 0 20rpx; font-size: 26rpx; color: #1f2328;
  background: #f5f6f8; border: 2rpx solid #e6e8eb; border-radius: 12rpx; }
.ask-btn { flex: none; height: 76rpx; line-height: 76rpx; padding: 0 32rpx; font-size: 26rpx;
  color: #fff; background: #2f6fed; border-radius: 12rpx; }
.ask-btn.disabled { opacity: .45; }

.answer { margin-top: 24rpx; padding: 24rpx; border: 2rpx solid #e6e8eb; border-radius: 12rpx; background: #fbfcfe; }
.line { display: flex; align-items: flex-start; gap: 14rpx; }
.line + .line { margin-top: 18rpx; padding-top: 18rpx; border-top: 2rpx dashed #e6e8eb; }
.line.miss .txt { color: #8a919c; }
.mk { flex: none; width: 40rpx; height: 40rpx; border-radius: 8rpx; background: #e8f1fd;
  color: #1a56a8; font-size: 22rpx; line-height: 40rpx; text-align: center; }
.mk.miss { background: #f0f2f5; color: #8a919c; }
.txt { font-size: 28rpx; line-height: 1.7; color: #1f2328; }
.ans { flex: 1; }
.local { display: inline-block; margin-top: 10rpx; font-size: 20rpx; color: #8a919c;
  background: #f0f2f5; padding: 2rpx 12rpx; border-radius: 6rpx; }
.facts { margin-top: 18rpx; }
.facts-t { display: block; font-size: 24rpx; color: #8a919c; margin-bottom: 8rpx; }
.fact { font-size: 25rpx; color: #4a5159; line-height: 1.9; }
.fact.dim { color: #8a919c; }
</style>
