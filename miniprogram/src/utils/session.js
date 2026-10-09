// 当前登录用户：登录时写进 storage，页面按角色决定入口与可见按钮
const USER_KEY = 'travel_user'

export const saveUser = (vo) => uni.setStorageSync(USER_KEY, JSON.stringify(vo || {}))
export const readUser = () => {
  try { return JSON.parse(uni.getStorageSync(USER_KEY) || '{}') } catch (e) { return {} }
}
export const clearUser = () => uni.removeStorageSync(USER_KEY)

export const role = () => readUser().role || ''
export const isEmployee = () => role() === 'EMPLOYEE'
export const isApprover = () => role() === 'APPROVER'
export const isManager = () => role() === 'MANAGER'
export const isFinance = () => role() === 'FINANCE'
export const isAdmin = () => role() === 'SYS_ADMIN'

// 能审批的人：部门主管走部门审批，管理层走复审
export const canApprove = () => isApprover() || isManager()
// 「谁能看哪个功能区」一律以 utils/tabs.js 的 hasTab(key) 为准——
// 底部导航与页面守卫共用同一份角色配置，别在这里再写一套口径
