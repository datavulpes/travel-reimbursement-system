// 统一请求封装：后端 R 契约（code=0 成功），token 走 Authorization 头
// BASE 说明（三端口径不同，别再写死一个 IP）：
// ① H5：由 nginx 托管，页面和 /api 同源，直接写相对路径。
//    好处是穿透域名怎么变都不用重新打包（PC 前端就是这个思路）。
// ② App（Android APK / iOS）：不是浏览器，没有同源概念，必须写绝对地址。
//    默认给 192.168.x.x —— 这是 Windows「移动热点」分配给本机的固定 IP，
//    正好命中「手机开热点给笔记本」这种没外网的现场。走公网隧道时不用重新打包，
//    在「我的」页把 BASE 改成 https://xxx.cpolar.cn/api 即可（setBase 已支持）。
// ③ 微信小程序：真机预览走「不校验合法域名」，用局域网 IP 最省事；
//    体验版/正式版必须 https + 已备案域名，免费穿透域名过不了，别指望。
// 想让 App 包默认就带隧道域名：建 .env 写 VITE_APP_BASE=https://xxx.cpolar.cn/api
// 再打包，不用改这个文件。
// #ifdef H5
export const DEFAULT_BASE = '/api'
// #endif
// #ifndef H5
export const DEFAULT_BASE = import.meta.env.VITE_APP_BASE || 'http://192.168.x.x:8080/api'
// #endif

// 地址可运行时改（「我的」页里改），省得换网络就去翻源码
const BASE_KEY = 'travel_base'
export const getBase = () => uni.getStorageSync(BASE_KEY) || DEFAULT_BASE
export const setBase = (v) => {
  const s = (v || '').trim().replace(/\/+$/, '')
  if (s) uni.setStorageSync(BASE_KEY, s)
  else uni.removeStorageSync(BASE_KEY)
}

const TOKEN_KEY = 'travel_token'

export const getToken = () => uni.getStorageSync(TOKEN_KEY) || ''
export const setToken = (t) => uni.setStorageSync(TOKEN_KEY, t)
export const clearToken = () => uni.removeStorageSync(TOKEN_KEY)

function toLogin () {
  clearToken()
  uni.reLaunch({ url: '/pages/login/login' })
}

// silence=true 时把业务错误与网络异常都吞掉（不打 toast、不 reject 之外的副作用），
// 用于 AI 面板暂存这类「存不上也不该打扰人」的请求
export function request ({ url, method = 'GET', data = null, header = {}, silence = false }) {
  return new Promise((resolve, reject) => {
    const h = { ...header }
    const token = getToken()
    if (token) h.Authorization = token
    uni.request({
      url: getBase() + url,
      method,
      data,
      header: h,
      timeout: 15000,
      success (res) {
        const body = res.data
        if (res.statusCode === 401 || body?.code === 4001) {
          if (!silence) uni.showToast({ title: '登录已过期', icon: 'none' })
          toLogin()
          return reject(new Error('not login'))
        }
        if (body && body.code === 0) return resolve(body.data)
        // 业务错误：后端 msg 自带人话，直接展示
        const msg = body?.msg || ('请求失败 ' + res.statusCode)
        if (!silence) uni.showToast({ title: msg, icon: 'none' })
        return reject(new Error(msg))
      },
      fail (err) {
        if (!silence) uni.showToast({ title: '网络异常，请确认后端已启动', icon: 'none' })
        reject(err)
      }
    })
  })
}

// GET 与 DELETE 的参数都要拼在 URL 上：
// 小程序只有 GET 会把 data 转成 query，DELETE 走 body，而 Spring 那边的
function buildQs (params) {
  if (!params) return ''
  const pairs = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
  return pairs.length ? '?' + pairs.join('&') : ''
}

export const get = (url, params, opts = {}) => request({ url: url + buildQs(params), method: 'GET', ...opts })

export const post = (url, data, opts = {}) => request({ url, method: 'POST', data, ...opts })
export const put = (url, data, opts = {}) => request({ url, method: 'PUT', data, ...opts })
export const del = (url, params, opts = {}) => request({ url: url + buildQs(params), method: 'DELETE', ...opts })

// 票据上传走 uni.uploadFile（multipart），成功后同样解 R 契约
export function uploadInvoice (filePath, applyNo) {
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: `${getBase()}/invoice/upload?applyNo=${encodeURIComponent(applyNo)}`,
      filePath,
      name: 'file',
      header: { Authorization: getToken() },
      success (res) {
        try {
          const body = JSON.parse(res.data)
          if (body.code === 0) return resolve(body.data)
          uni.showToast({ title: body.msg || '上传失败', icon: 'none' })
          reject(new Error(body.msg))
        } catch (e) {
          reject(e)
        }
      },
      fail: reject
    })
  })
}
