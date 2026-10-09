<template>
  <view class="page page-tab">
    <view class="card">
      <view class="row1">
        <text class="sec">{{ u.realName || '—' }}</text>
        <text class="tag tag-blue">{{ roleText }}</text>
      </view>
      <view class="grid">
        <view class="g-item"><text class="g-lbl">登录名</text><text class="g-val">{{ dash(u.username) }}</text></view>
        <view class="g-item"><text class="g-lbl">部门</text><text class="g-val">{{ dash(u.deptName) }}</text></view>
        <view class="g-item"><text class="g-lbl">银行账号</text><text class="g-val">{{ dash(u.bankAccountMasked) }}</text></view>
        <view class="g-item"><text class="g-lbl">用户号</text><text class="g-val">{{ dash(u.userId) }}</text></view>
      </view>
      <text class="note">账号只显示前 3 后 4，改信息去 Web 端</text>
    </view>

    <view class="card">
      <text class="sec">设置</text>
      <view class="set" @click="editBase">
        <view class="set-m">
          <text class="set-t">后端地址</text>
          <text class="set-s">{{ base }}</text>
        </view>
        <text class="arrow">›</text>
      </view>
      <view class="set" @click="resetBase">
        <view class="set-m">
          <text class="set-t">恢复默认地址</text>
          <text class="set-s">{{ defaultBase }}</text>
        </view>
        <text class="arrow">›</text>
      </view>
      <view class="set" @click="checkUpdate">
        <view class="set-m">
          <text class="set-t">检查更新</text>
          <text class="set-s">{{ curVersionText || '点击检查' }}</text>
        </view>
        <text class="arrow">›</text>
      </view>

      <!-- 用行内输入框而不是 uni.showModal 的 editable：那个参数 App 端不认，
           而 APK 恰恰是最需要现场改地址的场景（隧道域名每天变） -->
      <view class="edit-row">
        <input class="edit-input" v-model="draft" placeholder="http://192.168.x.x:8080/api"
          placeholder-style="color:#b6bcc4" confirm-type="done" @confirm="saveBase" />
        <text class="edit-btn" @click="saveBase">保存</text>
      </view>
      <text class="note">改成电脑的局域网 IP（手机和电脑连同一个网）或 https 隧道域名，改完立刻生效，不用重新编译</text>
    </view>

    <view class="card" v-if="isEmployee() || isManager() || isAdmin()">
      <text class="sec">AI 能力</text>
      <view v-if="isEmployee()" class="set" @click="go('/pages/ai/policy')">
        <view class="set-m"><text class="set-t">AI 助手</text><text class="set-s">问差旅 / 报销规定</text></view>
        <text class="arrow">›</text>
      </view>
      <view v-else-if="isManager()" class="set" @click="go('/pages/ai/data')">
        <view class="set-m"><text class="set-t">AI 数据问答</text><text class="set-s">用大白话问经营数据</text></view>
        <text class="arrow">›</text>
      </view>
      <view v-else-if="isAdmin()" class="set" @click="go('/pages/admin/aistatus')">
        <view class="set-m"><text class="set-t">AI 运行状况</text><text class="set-s">调用量 / 成功率 / 降级</text></view>
        <text class="arrow">›</text>
      </view>
    </view>

    <button class="btn btn-danger" @click="doLogout">退出登录</button>

    <TabBar current="mine" />
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { me, logout } from '../../api'
import { getBase, setBase, DEFAULT_BASE } from '../../utils/request'
import { checkAppUpdate } from '../../utils/update'
import { ROLE, dash } from '../../utils/dict'
import { clearUser, isEmployee, isManager, isAdmin } from '../../utils/session'
import TabBar from '../../components/TabBar.vue'

const u = ref({})
const base = ref(getBase())
const draft = ref(getBase())
const defaultBase = DEFAULT_BASE
const roleText = computed(() => ROLE[u.value.role] || u.value.role || '—')

// 当前生效版本（原生包号 + 已热更的 wgt 版本），仅 App 平台有值
const curVersionText = computed(() => {
  if (typeof plus === 'undefined') return ''
  const wgt = Number(uni.getStorageSync('app_wgt_version') || 0)
  const v = plus.runtime.version || ''
  return wgt ? `${v} · wgt${wgt}` : v
})

function checkUpdate () {
  checkAppUpdate({ manual: true })
}

onShow(async () => {
  base.value = getBase()
  draft.value = getBase()
  try { u.value = (await me()) || {} } catch (e) { /* toast 已弹 */ }
})

function editBase () {
  // 点上面的行只做一件事：把当前地址灌进输入框并聚焦，避免在 App 上弹系统弹窗
  draft.value = getBase()
  uni.showToast({ title: '在下方输入框里改', icon: 'none' })
}

function saveBase () {
  const v = (draft.value || '').trim().replace(/\/+$/, '')
  if (!v) {
    uni.showToast({ title: '地址不能为空', icon: 'none' })
    return
  }
  setBase(v)
  base.value = getBase()
  uni.showToast({ title: '已切换', icon: 'none' })
}

function resetBase () {
  setBase('')
  base.value = getBase()
  uni.showToast({ title: '已恢复', icon: 'none' })
}

function go (url) {
  uni.navigateTo({ url })
}

function doLogout () {
  uni.showModal({
    title: '退出登录',
    content: '确认退出？',
    success: async (r) => {
      if (!r.confirm) return
      try { await logout() } catch (e) { /* 后端没这个会话也照退 */ }
      clearUser()
      uni.removeStorageSync('travel_token')
      // 通知全局 AI 悬浮球收起（已退出登录）
      uni.$emit('auth-changed')
      uni.reLaunch({ url: '/pages/login/login' })
    }
  })
}
</script>

<style>
.sec { font-size: 30rpx; font-weight: 500; color: #1f2328; }
.note { display: block; margin-top: 8rpx; font-size: 24rpx; color: #8a919c; }
.set { display: flex; align-items: center; justify-content: space-between; padding: 24rpx 0;
  border-bottom: 2rpx solid #f0f1f3; }
.set:last-of-type { border-bottom: none; }
.set-t { display: block; font-size: 28rpx; color: #1f2328; }
.set-s { display: block; margin-top: 6rpx; font-size: 24rpx; color: #8a919c; word-break: break-all; }
.arrow { font-size: 36rpx; color: #c4c9d0; margin-left: 16rpx; }
.edit-row { display: flex; align-items: center; gap: 16rpx; margin-top: 20rpx; }
.edit-input { flex: 1; height: 72rpx; padding: 0 20rpx; font-size: 26rpx; color: #1f2328;
  background: #f5f6f8; border: 2rpx solid #e6e8eb; border-radius: 12rpx; }
.edit-btn { padding: 0 28rpx; height: 72rpx; line-height: 72rpx; font-size: 26rpx; color: #fff;
  background: #2b6cf0; border-radius: 12rpx; }
</style>
