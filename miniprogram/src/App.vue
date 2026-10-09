<script>
import AiAssistBall from './components/AiAssistBall.vue'
import { checkAppUpdate } from './utils/update'

export default {
  components: { AiAssistBall },
  onLaunch: function () {
    console.log('App Launch')
    // 启动即检查热更新：有新版 wgt 静默下载安装并重启（仅 App 平台生效）
    checkAppUpdate({ silent: true })
  },
  onShow: function () {
    console.log('App Show')
  },
  onHide: function () {
    console.log('App Hide')
  },
}
</script>

<template>
  <!-- 全局 AI 助手悬浮球：渲染在所有页面之上，登录态且非系统管理员时显示 -->
  <AiAssistBall />
</template>

<style>
/* 全局公共样式：各页面直接用这里的 class，不重复写 */
/* 底色跟登录页主色调（蓝）走：顶部浅蓝，往下过渡到接近白的浅蓝灰 */
page { background: #eef4fc; }

.page { min-height: 100vh; padding: 24rpx 32rpx 60rpx; box-sizing: border-box;
  background: linear-gradient(180deg, #e2ecfb 0%, #eef4fc 40%, #f5f8fd 100%); }
/* 带底部导航的页面：给自研 tabBar 留出位置，不然最后一张卡片被压住 */
.page-tab { padding-bottom: 180rpx; }
.card { background: #fff; border-radius: 16rpx; padding: 28rpx 32rpx; margin-bottom: 24rpx; }

.sec { font-size: 28rpx; font-weight: 500; color: #1f2328; }
.dim { font-size: 24rpx; color: #8a919c; }
.row1 { display: flex; justify-content: space-between; align-items: center; }
.mt { margin-top: 20rpx; }

.grid { display: flex; flex-wrap: wrap; margin-top: 20rpx; }
.g-item { width: 50%; margin-bottom: 18rpx; }
.g-lbl { display: block; font-size: 24rpx; color: #8a919c; }
.g-val { display: block; font-size: 28rpx; color: #1f2328; margin-top: 4rpx; }
.money { color: #d83931; font-weight: 500; }

.tag { font-size: 22rpx; padding: 4rpx 16rpx; border-radius: 8rpx; }
.tag-green { background: #e8f5e9; color: #2e7d32; }
.tag-amber { background: #fff7e6; color: #ad6800; }
.tag-red { background: #fdecec; color: #d83931; }
.tag-gray { background: #f0f1f3; color: #5f6773; }
.tag-blue { background: #e8f1fd; color: #1a56a8; }

.empty { text-align: center; color: #9aa2ad; font-size: 26rpx; padding: 60rpx 0; }

.btn { margin-top: 28rpx; height: 88rpx; line-height: 88rpx; text-align: center;
       border-radius: 12rpx; font-size: 30rpx; background: #2f6fed; color: #fff; }
.btn-ghost { background: #fff; color: #3a4149; border: 1rpx solid #d6dae0; }
.btn-danger { background: #fff; color: #d83931; border: 1rpx solid #f3c3c0; }
.btn-row { display: flex; gap: 20rpx; }
.btn-row .btn { flex: 1; }

.entry { display: flex; align-items: center; background: #fff; border-radius: 16rpx;
         padding: 28rpx 32rpx; margin-bottom: 20rpx; }
.entry-ic { width: 64rpx; height: 64rpx; border-radius: 16rpx; background: #eaf0fd;
            color: #2f6fed; font-size: 30rpx; text-align: center; line-height: 64rpx; margin-right: 24rpx; }
.entry-t { font-size: 30rpx; color: #1f2328; }
.entry-s { font-size: 24rpx; color: #8a919c; margin-top: 4rpx; }

.imgs { display: flex; flex-wrap: wrap; gap: 16rpx; margin-top: 20rpx; }
.img { width: 200rpx; height: 200rpx; border-radius: 12rpx; background: #f0f1f3; }
.img-cap { font-size: 22rpx; color: #8a919c; }
</style>
