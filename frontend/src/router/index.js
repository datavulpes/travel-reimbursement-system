import { createRouter, createWebHashHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

// 用 hash 模式：build 出来的 dist 丢到 nginx 任意目录都能跑，不必配 try_files
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/Login.vue'),
      meta: { title: '登录', public: true }
    },

    /* ---------- 员工端 ---------- */
    {
      path: '/employee',
      component: () => import('@/layouts/RoleLayout.vue'),
      redirect: '/employee/apply',
      meta: { roles: ['EMPLOYEE'] },
      children: [
        { path: 'apply', name: 'empApply', component: () => import('@/views/employee/ApplyCreate.vue'), meta: { title: '填写出差申请' } },
        { path: 'list', name: 'empList', component: () => import('@/views/employee/ApplyList.vue'), meta: { title: '我的申请' } },
        { path: 'invoice', name: 'empInvoice', component: () => import('@/views/employee/InvoiceUpload.vue'), meta: { title: '票据上传' } },
        { path: 'expense', name: 'empExpense', component: () => import('@/views/employee/ExpenseSubmit.vue'), meta: { title: '报销提交' } },
        { path: 'policy', name: 'empPolicy', component: () => import('@/views/employee/PolicyQa.vue'), meta: { title: 'AI助手' } }
      ]
    },

    /* ---------- 审批端（部门初审 + 管理层复审共用，后端 ApprovalController 也同时放行这两个角色） ---------- */
    {
      path: '/approver',
      component: () => import('@/layouts/RoleLayout.vue'),
      redirect: '/approver/todo',
      meta: { roles: ['APPROVER', 'MANAGER'] },
      children: [
        { path: 'todo', name: 'apvTodo', component: () => import('@/views/approver/ApprovalTodo.vue'), meta: { title: '待我审批' } },
        { path: 'detail', name: 'apvDetail', component: () => import('@/views/approver/ApprovalDetail.vue'), meta: { title: '审批详情', hidden: true } }
      ]
    },

    /* ---------- 财务端 ---------- */
    {
      path: '/finance',
      component: () => import('@/layouts/RoleLayout.vue'),
      redirect: '/finance/pending',
      meta: { roles: ['FINANCE'] },
      children: [
        { path: 'pending', name: 'finPending', component: () => import('@/views/finance/ReviewList.vue'), meta: { title: '待复核' } },
        { path: 'review', name: 'finReview', component: () => import('@/views/finance/ReviewDetail.vue'), meta: { title: '复核详情', hidden: true } },
        { path: 'invoice', name: 'finInvoice', component: () => import('@/views/finance/InvoiceSearch.vue'), meta: { title: '票据影像' } }
      ]
    },

    /* ---------- 管理层 ---------- */
    {
      path: '/manager',
      component: () => import('@/layouts/RoleLayout.vue'),
      redirect: '/manager/report',
      meta: { roles: ['MANAGER'] },
      children: [
        { path: 'report', name: 'mgtReport', component: () => import('@/views/manager/MonthlyReport.vue'), meta: { title: '费效比月报' } },
        { path: 'drill', name: 'mgtDrill', component: () => import('@/views/manager/Drilldown.vue'), meta: { title: '归因钻取' } },
        { path: 'partner', name: 'mgtPartner', component: () => import('@/views/manager/PartnerIo.vue'), meta: { title: '对象投入产出' } },
        { path: 'data-qa', name: 'mgtDataQa', component: () => import('@/views/manager/DataQa.vue'), meta: { title: 'AI助手' } },
        { path: 'standard', name: 'mgtStandard', component: () => import('@/views/manager/StandardConfig.vue'), meta: { title: '差标与预算' } }
      ]
    },

    /* ---------- 系统管理员：基础档案维护与只读浏览 ---------- */
    {
      path: '/admin',
      component: () => import('@/layouts/RoleLayout.vue'),
      redirect: '/admin/partner',
      meta: { roles: ['SYS_ADMIN'] },
      children: [
        { path: 'partner', name: 'admPartner', component: () => import('@/views/admin/PartnerManage.vue'), meta: { title: '往来对象' } },
        { path: 'user', name: 'admUser', component: () => import('@/views/admin/UserManage.vue'), meta: { title: '用户账号' } },
        { path: 'dept', name: 'admDept', component: () => import('@/views/admin/DeptArchive.vue'), meta: { title: '部门档案' } },
        { path: 'browser', name: 'admBrowse', component: () => import('@/views/admin/DataBrowser.vue'), meta: { title: '全表只读浏览' } },
        { path: 'ai', name: 'admAiStatus', component: () => import('@/views/admin/AiStatus.vue'), meta: { title: 'AI 运行状况' } }
      ]
    },

    { path: '/:pathMatch(.*)*', redirect: '/login' }
  ]
})

router.beforeEach((to, from, next) => {
  const user = useUserStore()
  const title = to.meta?.title
  document.title = title ? `${title} - 企业差旅管理工具` : '企业差旅管理工具'

  if (to.meta?.public) {
    // 已登录再访问登录页，直接送回自己的端口
    if (to.path === '/login' && user.isLogin) return next(user.homePath)
    return next()
  }

  if (!user.isLogin) {
    return next('/login')
  }

  // 越权访问：不是本角色的菜单，踢回自己的落地页
  const roles = to.meta?.roles
  if (roles && roles.length && !roles.includes(user.role)) {
    ElMessage.warning('当前账号无权访问该端口')
    return next(user.homePath)
  }

  next()
})

export default router
