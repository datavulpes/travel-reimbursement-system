<template>
  <view class="page">
    <!-- 顶部浅蓝渐变 + 半透明方块装饰（对应参考图的 3D 方块质感，纯 CSS 不引图） -->
    <view class="deco">
      <view class="cube c1" />
      <view class="cube c2" />
      <view class="cube c3" />
      <view class="cube c4" />
    </view>

    <!-- 自定义导航栏（pages.json 里该页 navigationStyle: custom），状态栏高度按机型顶出来 -->
    <view class="top" :style="{ paddingTop: (statusBarH + 16) + 'px' }">
      <view class="topline">
        <text v-if="canBack" class="back" @click="goBack">‹</text>
      </view>
      <text class="hi">你好！</text>
      <text class="welcome">欢迎使用企业差旅管理工具</text>
    </view>

    <view class="form">
      <view :class="['field', focusUser ? 'field-on' : '']">
        <input v-model="username" class="field-ipt" placeholder="登录名" placeholder-class="ph"
          @input="onType" @focus="focusUser = true" @blur="focusUser = false" />
      </view>

      <view :class="['field', focusPwd ? 'field-on' : '']">
        <input v-model="password" class="field-ipt" :password="hidePwd" placeholder="密码"
          placeholder-class="ph" @input="onType" @focus="focusPwd = true" @blur="focusPwd = false" @confirm="doLogin" />
        <view class="eye" @click="hidePwd = !hidePwd">
          <view :class="['eye-o', hidePwd ? 'eye-off' : '']">
            <view class="eye-i" />
          </view>
        </view>
      </view>

      <button class="btn" :loading="loading" :disabled="loading" @click="doLogin">登 录</button>

      <!-- ▼▼ [演示] 开始：五个角色快填。上线前删掉本段，以及 script 里的 DEMO_ROLES / fillRole、
           style 里的 .role* —— 三处都标了 [演示]，搜这个词就能删干净 ▼▼ -->
      <view class="roles">
        <view class="roles-head">
          <text class="roles-t">演示角色 · 点一下填入</text>
          <text class="roles-del">上线前删除本块</text>
        </view>
        <view class="roles-grid">
          <view v-for="r in DEMO_ROLES" :key="r.user"
            :class="['role', currentUser === r.user ? 'role-on' : '']" @click="fillRole(r)">
            <text class="role-t">{{ r.label }}</text>
            <text class="role-u">{{ r.user }}</text>
          </view>
        </view>
      </view>
      <!-- ▲▲ [演示] 结束 ▲▲ -->
    </view>

    <view class="spacer" />

    <!-- 地址必须在登录页就能改：APK 装到手机上如果默认地址不通，登录直接失败，
         根本进不去「我的」页，到时候连改的地方都没有 -->
    <view v-if="!editing" class="foot" @click="startEdit">当前后端 {{ baseUrl }} · 点此修改</view>
    <view v-else class="foot-edit">
      <input class="foot-input" v-model="draftBase" placeholder="http://192.168.x.x:8080/api"
        placeholder-style="color:#9aa2ad" confirm-type="done" @confirm="saveBase" />
      <text class="foot-btn" @click="saveBase">保存</text>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { login } from '../../api'
import { setToken, getBase, setBase } from '../../utils/request'
import { saveUser } from '../../utils/session'

const DEMO_USER = 'wengshaogang'
const DEMO_PWD = '123456'

// [演示] 五个角色各一个账号，密码统一 123456（初始化数据.sql 第 41 行写明）。
// 上线前连同模板里的 roles 区、样式里的 .role* 一起删
const DEMO_ROLES = [
  { label: '员工', user: 'wengshaogang', pwd: '123456' },
  { label: '部门主管', user: 'daiqinghao', pwd: '123456' },
  { label: '财务', user: 'wusiting', pwd: '123456' },
  { label: '管理层', user: 'manager', pwd: '123456' },
  { label: '系统管理员', user: 'sysadmin', pwd: '123456' }
]

const username = ref(DEMO_USER)
const password = ref(DEMO_PWD)
const currentUser = ref(DEMO_USER)
const loading = ref(false)
const focusUser = ref(false)
const focusPwd = ref(false)
const hidePwd = ref(true)
const canBack = ref(false)
const baseUrl = ref(getBase())
const draftBase = ref(getBase())
const editing = ref(false)
const statusBarH = ref(20)

onLoad(() => {
  // 从「我的」退出登录进来时栈里还有页面，给个返回；首次启动没有
  canBack.value = getCurrentPages().length > 1
  const info = uni.getSystemInfoSync()
  statusBarH.value = info.statusBarHeight || 20
})

const goBack = () => uni.navigateBack()

function startEdit () {
  draftBase.value = getBase()
  editing.value = true
}

function saveBase () {
  const v = (draftBase.value || '').trim().replace(/\/+$/, '')
  if (!v) {
    uni.showToast({ title: '地址不能为空', icon: 'none' })
    return
  }
  setBase(v)
  baseUrl.value = getBase()
  editing.value = false
  uni.showToast({ title: '已切换', icon: 'none' })
}

// [演示] 点角色卡填入账号密码；手改输入框时清掉高亮（currentUser 只用于显示选中态）
function fillRole (r) {
  username.value = r.user
  password.value = r.pwd
  currentUser.value = r.user
}
function onType () {
  currentUser.value = ''
}

async function doLogin () {
  if (!username.value || !password.value) {
    uni.showToast({ title: '请输入登录名和密码', icon: 'none' })
    return
  }
  loading.value = true
  try {
    const vo = await login(username.value.trim(), password.value)
    setToken(vo.token)
    saveUser(vo)
    // 通知全局 AI 悬浮球按新角色显隐（App.vue 只在启动挂载一次）
    uni.$emit('auth-changed')
    // reLaunch：清空页面栈，进去就是工作台，返回键不会退回登录页
    uni.reLaunch({ url: '/pages/home/home' })
  } catch (e) { /* toast 已在 request 里弹过 */ }
  loading.value = false
}
</script>

<style>
.page { position: relative; min-height: 100vh; display: flex; flex-direction: column;
  background: linear-gradient(180deg, #d9e7fb 0%, #e9f2fd 26%, #f6f9fd 52%, #f5f6f8 100%);
  padding-bottom: 48rpx; box-sizing: border-box; }

/* 装饰方块：只摆在顶部 560rpx 内，超出裁掉 */
.deco { position: absolute; left: 0; right: 0; top: 0; height: 560rpx; overflow: hidden; }
.cube { position: absolute; border-radius: 28rpx; }
.c1 { width: 190rpx; height: 190rpx; right: 30rpx; top: 70rpx;
  background: rgba(255, 255, 255, 0.42); transform: rotate(24deg); }
.c2 { width: 130rpx; height: 130rpx; right: 250rpx; top: 210rpx;
  background: rgba(255, 255, 255, 0.26); transform: rotate(-14deg); }
.c3 { width: 96rpx; height: 96rpx; right: 120rpx; top: 340rpx;
  background: rgba(112, 156, 240, 0.28); transform: rotate(32deg); }
.c4 { width: 150rpx; height: 150rpx; left: -50rpx; top: 300rpx;
  background: rgba(255, 255, 255, 0.20); transform: rotate(18deg); }

.top { position: relative; padding: 0 56rpx; }
.topline { height: 56rpx; display: flex; align-items: center; }
.back { color: #1f2328; font-size: 56rpx; line-height: 1; }
.hi { display: block; margin-top: 32rpx; font-size: 56rpx; font-weight: 800; color: #1f2328; }
.welcome { display: block; margin-top: 14rpx; font-size: 38rpx; font-weight: 600; color: #1f2328; }

.form { position: relative; padding: 72rpx 56rpx 0; }
.field { position: relative; display: flex; align-items: center; height: 104rpx;
  background: #edf3fc; border-radius: 20rpx; padding: 0 96rpx 0 32rpx; margin-bottom: 32rpx;
  border: 2rpx solid #edf3fc; }
.field-on { background: #fff; border-color: #9dc0f7; }
.field-ipt { flex: 1; font-size: 30rpx; color: #1f2328; height: 104rpx; }
.ph { color: #a9b6c9; }

/* 纯 CSS 画的眼睛：外圈椭圆 + 瞳孔，密码隐藏时加一条斜线 */
.eye { position: absolute; right: 28rpx; width: 64rpx; height: 64rpx;
  display: flex; align-items: center; justify-content: center; }
.eye-o { position: relative; width: 40rpx; height: 26rpx; border: 3rpx solid #9aa2ad;
  border-radius: 50%; }
.eye-i { position: absolute; left: 50%; top: 50%; width: 10rpx; height: 10rpx;
  background: #9aa2ad; border-radius: 50%; transform: translate(-50%, -50%); }
.eye-off::after { content: ''; position: absolute; left: -4rpx; top: 50%; width: 48rpx;
  height: 3rpx; background: #9aa2ad; transform: rotate(-32deg); }

.btn { margin-top: 40rpx; height: 104rpx; line-height: 104rpx; font-size: 32rpx; color: #fff;
  border-radius: 22rpx; background: #2f6fed;
  box-shadow: 0 8rpx 24rpx rgba(47, 111, 237, 0.28); }
.btn[disabled] { opacity: 0.6; }

.spacer { flex: 1; min-height: 80rpx; }

/* [演示] 角色快填区样式，上线前随模板一起删 */
.roles { margin-top: 48rpx; padding-top: 32rpx; border-top: 1rpx solid rgba(47, 111, 237, 0.14); }
.roles-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18rpx; }
.roles-t { font-size: 24rpx; color: #5f6773; }
.roles-del { font-size: 20rpx; color: #b0b7c0; }
.roles-grid { display: flex; flex-wrap: wrap; gap: 16rpx; }
.role { padding: 14rpx 24rpx; border-radius: 14rpx; background: #fff;
  border: 2rpx solid #dbe6f7; text-align: center; }
.role-t { display: block; font-size: 26rpx; color: #1f2328; }
.role-u { display: block; margin-top: 4rpx; font-size: 20rpx; color: #8a919c; }
.role-on { background: #eef3fe; border-color: #2f6fed; }
.role-on .role-t { color: #2f6fed; }
.role-on .role-u { color: #5b86e0; }

.foot { display: block; text-align: center; margin-top: 20rpx; font-size: 22rpx; color: #b0b7c0; }
.foot-edit { display: flex; align-items: center; justify-content: center; gap: 12rpx; margin-top: 20rpx; }
.foot-input { width: 60%; height: 64rpx; padding: 0 18rpx; font-size: 24rpx; color: #1f2328;
  background: #fff; border: 2rpx solid #dfe3e8; border-radius: 12rpx; }
.foot-btn { height: 64rpx; line-height: 64rpx; padding: 0 24rpx; font-size: 24rpx; color: #fff;
  background: #2b6cf0; border-radius: 12rpx; }
</style>
