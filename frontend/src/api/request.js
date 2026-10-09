import axios from 'axios'
import { ElMessage } from 'element-plus'
import { mockAdapter } from '@/mock'

export const TOKEN_KEY = 'TE_TOKEN'
export const USER_KEY = 'TE_USER'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 20000
})

// Mock 开关：true 时请求不发出去，直接由本地模拟数据应答（后端没起时用）
if (USE_MOCK) {
  service.defaults.adapter = mockAdapter
}

// 联调时先看一眼控制台：确认走的是真后端还是本地假数据，省得改了 .env 忘了生效
console.info(
  `[api] 数据源=${USE_MOCK ? '本地 Mock（不发请求）' : '真实后端 ' + service.defaults.baseURL}` +
  `｜联调请把 .env 里 VITE_USE_MOCK 设为 false 并重启 vite`
)

// 错误码口径与设计报告 7.5 一致
const ERROR_CODE = {
  1001: '参数校验失败',
  2001: '发票重复',
  2002: '当前状态不允许该操作',
  2003: '超出差标但未填写超标说明',
  2004: '预检未通过，需填写强制说明',
  3001: '无操作权限',
  4001: '登录已失效，请重新登录',
  5000: '系统异常'
}

service.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

service.interceptors.response.use(
  (response) => {
    const body = response.data

    // 静默标记：AI 这类「锦上添花」的能力失败时不弹错误条，只在控制台留痕。
    // AI 不是主流程的一部分，一次调用失败不应该在页面上甩一个红 toast 吓人。
    const silence = response.config?.silence === true

    // 非统一返回体（比如直接返回文件流）原样透出
    if (!body || typeof body !== 'object' || !('code' in body)) {
      return body
    }

    const { code, msg, data } = body
    if (code === 0) {
      return data
    }

    // 业务异常
    let text = msg || ERROR_CODE[code] || `请求失败（${code}）`
    // 5000 是兜底码：接口路径写错、唯一键冲突、后端抛异常都往这里落，
    // 光看「系统繁忙」查不出原因，联调时提示去看后端控制台
    if (code === 5000) {
      console.error('[api] 5000 系统异常，请求：', response.config?.method, response.config?.url, body)
      text += '｜原因看 Spring Boot 控制台日志'
    }
    if (!silence) {
      ElMessage.error({ message: text, customClass: 'code-' + code })
    }

    if (code === 4001 || response.status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
      setTimeout(() => {
        window.location.hash = '#/login'
        window.location.reload()
      }, 600)
    }

    return Promise.reject({ code, msg: text, data })
  },
  (error) => {
    const status = error?.response?.status
    // 同上面的 success 分支：带 silence 的请求（AI 能力）静默失败，不打扰使用者
    if (error?.config?.silence === true) {
      return Promise.reject(error)
    }
    if (status === 401) {
      ElMessage.error('登录已失效，请重新登录')
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
      setTimeout(() => { window.location.hash = '#/login'; window.location.reload() }, 600)
    } else if (status === 403) {
      ElMessage.error('无权限访问该接口')
    } else if (status === 413) {
      ElMessage.error('文件过大，票据影像请压缩到 10MB 以内')
    } else if (status >= 500) {
      ElMessage.error('后端服务异常，确认 Spring Boot 已启动')
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时')
    } else {
      ElMessage.error(error?.message || '网络异常')
    }
    return Promise.reject(error)
  }
)

export default service
