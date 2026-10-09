import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { TOKEN_KEY, USER_KEY } from '@/api/request'
import { login as apiLogin, logout as apiLogout, getMe } from '@/api'
import { ROLE_META } from '@/config/menus'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const info = ref(JSON.parse(localStorage.getItem(USER_KEY) || 'null'))

  const isLogin = computed(() => !!token.value)
  const role = computed(() => info.value?.role || '')
  const realName = computed(() => info.value?.realName || '')
  const deptName = computed(() => info.value?.deptName || '')

  /** 当前角色对应的落地页，登录成功后按它跳转 */
  const homePath = computed(() => ROLE_META[role.value]?.home || '/login')

  async function doLogin ({ username, password }) {
    const data = await apiLogin({ username, password })
    token.value = data.token
    localStorage.setItem(TOKEN_KEY, data.token)
    info.value = {
      userId: data.userId,
      username,
      realName: data.realName,
      role: data.role,
      deptId: data.deptId,
      deptName: data.deptName
    }
    localStorage.setItem(USER_KEY, JSON.stringify(info.value))
    return data.role
  }

  /** 刷新后拿本地缓存恢复不了详情时，向后端补一次当前用户 */
  async function refreshMe () {
    if (!token.value) return
    try {
      const data = await getMe()
      info.value = { ...info.value, ...data }
      localStorage.setItem(USER_KEY, JSON.stringify(info.value))
    } catch (e) {
      // token 失效时 request.js 已统一处理跳转
    }
  }

  async function doLogout () {
    try {
      await apiLogout()
    } catch (e) {
      // 退出失败也要清本地
    }
    token.value = ''
    info.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return {
    token, info, isLogin, role, realName, deptName, homePath,
    doLogin, doLogout, refreshMe
  }
})
