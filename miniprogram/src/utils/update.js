// App 内置热更新（wgt 资源包）通道
// 机制：启动或手动检查时，拉取 <后端基址>/update/update.json，比对 versionCode；
// 若比本机已应用的版本高，就下载 wgt，用 plus.runtime.install 热替换前端资源，
// 重启后即生效 —— 不用重新安装 APK。
// 约束（务必看）：
//  - 仅 App 平台生效。H5 / 微信小程序没有 plus.runtime，直接跳过，调用方无副作用。
//  - 热更新只能换前端资源（页面 / 组件 / js / 样式），不能改原生或打包配置。
//    改了 manifest 的原生项（如图标、权限、usesCleartextTraffic）或用了新的原生插件，
//    必须重新出 APK，wgt 推不上去。
//  - 更新基址从请求封装的 getBase() 推导：去掉末尾 /api，即走同一条隧道 / 局域网 nginx。
//    所以「后端地址」要填到 nginx（8088 或 cpolar 域名），不要直接填 8080 后端，
//    否则 /update/ 拉不到资源包。
//  - 已生效版本记在 storage('app_wgt_version')；首次回落到原生包 versionCode。
//    这样 wgt 覆盖后版本号以 storage 为准，避免和 plus.runtime.versionCode（原生包号）混淆导致死循环。

import { getBase } from './request'

const UPDATE_JSON = '/update/update.json'
const STORAGE_KEY = 'app_wgt_version'

// 本机当前已生效的版本号：优先取历次热更写入的版本，首次回落到原生包 versionCode
function currentVersion () {
  const stored = Number(uni.getStorageSync(STORAGE_KEY) || 0)
  if (stored) return stored
  try { return Number(plus.runtime.versionCode) || 0 } catch (e) { return 0 }
}

// 更新基址 = 后端基址去掉末尾 /api
function updateHost () {
  const base = (getBase() || '').trim()
  if (!base) return ''
  return base.replace(/\/api\/?$/, '')
}

// 下载并安装 wgt；version 用于安装成功后回写 storage
function applyWgt (url, version, { silent }) {
  if (!silent) uni.showLoading({ title: '更新中…', mask: true })
  else console.log('[update] 静默热更新中', url)
  uni.downloadFile({
    url,
    success: (res) => {
      if (res.statusCode !== 200) {
        if (!silent) uni.hideLoading()
        if (!silent) uni.showToast({ title: '更新包下载失败', icon: 'none' })
        return
      }
      plus.runtime.install(
        res.tempFilePath,
        { type: 'wgt' },
        () => {
          if (!silent) uni.hideLoading()
          uni.setStorageSync(STORAGE_KEY, version)
          // 安装成功，重启让新资源生效
          plus.runtime.restart()
        },
        (err) => {
          if (!silent) uni.hideLoading()
          console.error('[update] install failed', err)
          if (!silent) uni.showToast({ title: '更新包安装失败', icon: 'none' })
        }
      )
    },
    fail: (err) => {
      if (!silent) uni.hideLoading()
      console.error('[update] download failed', err)
      if (!silent) uni.showToast({ title: '更新包下载失败', icon: 'none' })
    }
  })
}

// 检查更新
//   opts.silent：true 时静默下载安装（App 启动自动更新用），false 时弹确认框
//   opts.manual：true 时（手动点按钮）若已是最新也给个 toast 提示
export async function checkAppUpdate (opts = {}) {
  const { silent = false, manual = false } = opts
  // 非 App 平台（H5 / 小程序）无热更新能力，直接返回
  if (typeof plus === 'undefined') return false
  const host = updateHost()
  if (!host) {
    if (manual) uni.showToast({ title: '请先在「后端地址」里填 nginx / 隧道地址', icon: 'none' })
    return false
  }
  const url = host + UPDATE_JSON
  try {
    const res = await new Promise((resolve, reject) => {
      uni.request({ url, method: 'GET', sslVerify: false, success: resolve, fail: reject })
    })
    if (!res || res.statusCode !== 200 || !res.data) return false
    const info = res.data
    const latest = Number(info.versionCode) || 0
    const cur = currentVersion()
    if (latest <= cur) {
      if (manual) uni.showToast({ title: '已是最新版本', icon: 'none' })
      return false
    }
    const wgtUrl = host + (info.wgtUrl || '/update/app.wgt')
    if (silent) {
      applyWgt(wgtUrl, latest, { silent: true })
      return true
    }
    uni.showModal({
      title: '发现新版本 v' + (info.version || latest),
      content: info.note || '发现新版本，是否立即更新？',
      confirmText: '立即更新',
      cancelText: '暂不',
      success: (r) => { if (r.confirm) applyWgt(wgtUrl, latest, { silent: false }) }
    })
    return true
  } catch (e) {
    return false
  }
}
