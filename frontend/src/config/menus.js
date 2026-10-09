// 五个角色的菜单配置：角色登录后只能看到自己这一份菜单
// icon 用 Element Plus 图标名，已在 main.js 全局注册

export const ROLE_META = {
  EMPLOYEE: { label: '出差员工', color: '#2563a8', soft: '#e8f1fa', home: '/employee/apply' },
  APPROVER: { label: '部门审批人', color: '#10956a', soft: '#e4f5ee', home: '/approver/todo' },
  FINANCE: { label: '财务复核', color: '#b7791f', soft: '#fdf3e2', home: '/finance/pending' },
  MANAGER: { label: '企业管理层', color: '#6b4fa0', soft: '#efeaf8', home: '/manager/report' },
  SYS_ADMIN: { label: '系统管理员', color: '#0f6f80', soft: '#e0f0f3', home: '/admin/partner' }
}

export const MENUS = {
  EMPLOYEE: [
    {
      index: 'apply',
      title: '出差申请',
      icon: 'EditPen',
      children: [
        { index: '/employee/apply', title: '填写申请' },
        { index: '/employee/list', title: '我的申请' }
      ]
    },
    {
      index: 'invoice',
      title: '票据与报销',
      icon: 'Ticket',
      children: [
        { index: '/employee/invoice', title: '票据上传' },
        { index: '/employee/expense', title: '报销提交' }
      ]
    },
    // 顶级菜单项（不给 children）：点一下直接进页面。
    // 以前它是「分组 + 一个子项」，点父级只是展开下拉，得多点一次才看得到内容。
    {
      index: '/employee/policy',
      title: 'AI助手',
      icon: 'ChatDotRound'
    }
  ],
  APPROVER: [
    {
      index: 'approval',
      title: '审批中心',
      icon: 'Stamp',
      children: [
        { index: '/approver/todo', title: '待我审批' },
        { index: '/approver/detail', title: '审批详情', hidden: true }
      ]
    }
  ],
  FINANCE: [
    {
      index: 'finance',
      title: '财务复核',
      icon: 'Coin',
      children: [
        { index: '/finance/pending', title: '待复核' },
        { index: '/finance/review', title: '复核详情', hidden: true }
      ]
    },
    {
      index: 'invoiceQuery',
      title: '票据影像',
      icon: 'Picture',
      children: [
        { index: '/finance/invoice', title: '影像查询' }
      ]
    }
  ],
  /**
   * 管理层是两级审批的最后一环，复审入口必须给他，不然初审通过的单就卡死在 FINAL_REVIEW。
   * 这里与 APPROVER 复用同一组路由（/approver/todo、/approver/detail），
   * 页面内部按角色切文案。两套菜单各登记一份自己的路径，指向同一个组件。
   */
  MANAGER: [
    {
      index: 'approval',
      title: '出差复审',
      icon: 'Stamp',
      children: [
        { index: '/approver/todo', title: '待我复审' },
        { index: '/approver/detail', title: '复审详情', hidden: true }
      ]
    },
    {
      index: 'analysis',
      title: '费效分析',
      icon: 'DataLine',
      children: [
        { index: '/manager/report', title: '费效比月报' },
        { index: '/manager/drill', title: '归因钻取' },
        { index: '/manager/partner', title: '对象投入产出' },
        { index: '/manager/data-qa', title: 'AI助手' }
      ]
    },
    {
      index: 'config',
      title: '标准配置',
      icon: 'Setting',
      children: [
        { index: '/manager/standard', title: '差标与预算' }
      ]
    }
  ],
  /**
   * 系统管理员管的是「只被引用、不参与计算」的那部分档案。
   * 差标金额与部门预算不在这里：dept.monthly_budget 直接决定 BR-04 的预借额度上限，
   * 差标决定是否判超标、要不要强制写超标说明——那属于财权，归管理层，
   * 费效分析三页看完能接着调，这个闭环才不会断。
   */
  SYS_ADMIN: [
    {
      index: 'base',
      title: '基础数据',
      icon: 'Files',
      children: [
        { index: '/admin/partner', title: '往来对象' },
        { index: '/admin/user', title: '用户账号' },
        { index: '/admin/dept', title: '部门档案' }
      ]
    },
    {
      index: 'browse',
      title: '数据浏览',
      icon: 'Grid',
      children: [
        { index: '/admin/browser', title: '全表只读' },
        { index: '/admin/ai', title: 'AI 运行状况' }
      ]
    }
  ]
}

/**
 * 登录页的一键填充账号。
 *
 * 这些账号不是编的，就是 travel_server.sys_user 里的真实数据，
 * 密码统一 123456（BCrypt 存库），后端起来就能直接登录进对应角色端口。
 * 名单与库不一致时以库为准（SELECT id, username, real_name, role FROM sys_user）。
 */
export const MOCK_ACCOUNTS = [
  { username: 'wengshaogang', password: '123456', realName: '翁绍刚', role: 'EMPLOYEE', deptName: '销售部' },
  { username: 'daiqinghao', password: '123456', realName: '戴庆豪', role: 'APPROVER', deptName: '销售部' },
  { username: 'wusiting', password: '123456', realName: '吴思挺', role: 'FINANCE', deptName: '财务部' },
  { username: 'manager', password: '123456', realName: '管理层', role: 'MANAGER', deptName: '销售部' },
  { username: 'sysadmin', password: '123456', realName: '系统管理员', role: 'SYS_ADMIN', deptName: '销售部' }
]
