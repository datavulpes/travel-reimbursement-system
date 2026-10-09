/**
 * 演示数据 —— 字段与 openapi3.json 的数据模型完全同名同类型
 * 后端接口就绪后这份文件可以整个删掉
 */

const NOW_TAG = '2026-09-18 10:22:31'

/**
 * 演示模式下的票据影像占位图。
 *
 * mock 里没有真的图片文件，以前写的是 /api/invoice/file?... 和一个假的 OSS 域名，
 * 两种都取不到东西，页面上一片裂图——起 mock 演示时必现，看着像功能坏了。
 * 这里内联一张 SVG 画成票据的样子，src 是 data: 地址，不需要网络。
 *
 * 注意别改成 http 开头的假地址：组件判断 "^https?:" 就当它是一张真图直接挂上去，
 * 既不报错也不显示，排查方向会被带偏。
 */
function mockImg (label) {
  const svg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="220">' +
    '<rect width="320" height="220" fill="#eef2f7"/>' +
    '<rect x="14" y="14" width="292" height="192" rx="6" fill="#fff" stroke="#cbd5e1" stroke-width="2"/>' +
    '<rect x="34" y="38" width="120" height="9" rx="4" fill="#94a3b8"/>' +
    '<rect x="34" y="58" width="180" height="7" rx="3" fill="#cbd5e1"/>' +
    '<rect x="34" y="98" width="252" height="1" fill="#e2e8f0"/>' +
    '<rect x="34" y="118" width="150" height="7" rx="3" fill="#cbd5e1"/>' +
    '<rect x="34" y="138" width="200" height="7" rx="3" fill="#cbd5e1"/>' +
    '<rect x="34" y="158" width="120" height="7" rx="3" fill="#cbd5e1"/>' +
    '<circle cx="252" cy="156" r="30" fill="none" stroke="#cbd5e1" stroke-width="2"/>' +
    '<text x="252" y="160" font-family="sans-serif" font-size="11" fill="#94a3b8" text-anchor="middle">' +
    label +
    '</text>' +
    '</svg>'
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
}

/* ---------------- 出差申请 ---------------- */

export const applyDetailData = {
  applyNo: 'TA202609180001',
  applicant: '翁绍刚',
  deptName: '销售部',
  partnerName: '深圳市某某科技有限公司',
  reason: '赴深圳客户现场验货并确认交付节点',
  estTraffic: 1200,
  estHotel: 1600,
  estTotal: 2800,
  standardResult: 'NORMAL',
  exceedReason: '',
  status: 'DEPT_REVIEW',
  createdAt: NOW_TAG,
  itineraries: [
    {
      id: 101, seq: 1,
      startDate: '2026-09-20', endDate: '2026-09-22',
      fromCity: '惠州', toCity: '深圳',
      trafficType: '高铁', cityLevel: '二级', invoiceCnt: 2
    },
    {
      id: 102, seq: 2,
      startDate: '2026-09-22', endDate: '2026-09-23',
      fromCity: '深圳', toCity: '东莞',
      trafficType: '自驾', cityLevel: '二级', invoiceCnt: 0
    }
  ],
  invoices: [
    {
      id: 901, invoiceNo: '24412000000012345678',
      applyNo: 'TA202609180001', itineraryId: 101,
      amount: 1180, invoiceDate: '2026-09-21',
      seller: '深圳北站餐饮服务有限公司',
      imgPath: '2026/09/invoice_12345678.jpg',
      imgUrl: mockImg('演示'),
      thumbUrl: mockImg('演示'),
      ocrFlag: 1, dupOf: null
    },
    {
      id: 902, invoiceNo: '24412000000012345679',
      applyNo: 'TA202609180001', itineraryId: 101,
      amount: 680, invoiceDate: '2026-09-22',
      seller: '深圳市南山区如家酒店',
      imgPath: '2026/09/invoice_12345679.jpg',
      imgUrl: mockImg('演示'),
      thumbUrl: mockImg('演示'),
      ocrFlag: 1, dupOf: null
    }
  ],
  records: [
    {
      id: 11, applyNo: 'TA202609180001',
      approver: '翁绍刚', step: 'SUBMIT',
      result: 'APPROVED', opinion: '提交申请',
      snapshot: null, approvedAt: NOW_TAG
    }
  ]
}

export const otherApplyDetail = {
  ...applyDetailData,
  applyNo: 'TA202609120003',
  reason: '赴广州参加行业展会并拜访两家意向客户',
  partnerName: '广州某某贸易有限公司',
  estTraffic: 860, estHotel: 1100, estTotal: 1960,
  standardResult: 'HIGH',
  status: 'APPROVED',
  createdAt: '2026-09-12 09:03:44'
}

export const applyListData = [
  {
    applyNo: 'TA202609180001', reason: '赴深圳客户现场验货并确认交付节点',
    applicant: '翁绍刚', deptName: '销售部', partnerName: '深圳市某某科技有限公司',
    estTotal: 2800, standardResult: 'NORMAL', status: 'DEPT_REVIEW', createdAt: NOW_TAG
  },
  {
    applyNo: 'TA202609120003', reason: '赴广州参加行业展会并拜访两家意向客户',
    applicant: '翁绍刚', deptName: '销售部', partnerName: '广州某某贸易有限公司',
    estTotal: 1960, standardResult: 'HIGH', status: 'APPROVED', createdAt: '2026-09-12 09:03:44'
  },
  {
    applyNo: 'TA202609050002', reason: '赴长沙供应商工厂完成年度审核',
    applicant: '翁绍刚', deptName: '销售部', partnerName: '长沙某某制造有限公司',
    estTotal: 3240, standardResult: 'EXCEED', status: 'REJECTED', createdAt: '2026-09-05 14:18:02'
  },
  {
    applyNo: 'TA202608280007', reason: '赴厦门交付现场支持系统上线',
    applicant: '翁绍刚', deptName: '销售部', partnerName: '厦门某某信息有限公司',
    estTotal: 4120, standardResult: 'NORMAL', status: 'CLOSED', createdAt: '2026-08-28 16:40:11'
  }
]

/* ---------------- 参考值 / 差标 / 评价 ---------------- */

/**
 * 同类花费参考。与后端 queryLive 同一套字段：交通、住宿、合计各自独立统计，
 * 住宿只算住过的单（sameDayCnt 是当天往返单数），天数取整趟。
 * 合计区间不是两项相加——分位数不可加，mock 里也刻意让它们不相等，
 * 免得有人照着「应该相等」去改后端。
 */
export const costRefData = {
  toCity: '深圳', tripType: '验货', cityLevel: '一级', trafficType: '高铁',
  avgTraffic: 720, minTraffic: 560, maxTraffic: 880,
  avgHotel: 2280, minHotel: 1800, maxHotel: 2600,
  avgAmount: 3000, minAmount: 2200, maxAmount: 3600,
  avgDays: 2.0, minDays: 1, maxDays: 3,
  sampleCnt: 27, sameDayCnt: 3,
  lowSample: false, relaxed: false
}

export const standardHintsData = [
  { cityLevel: '二级', trafficType: '高铁', maxAmount: 1500, estAmount: 1200, result: 'NORMAL' },
  { cityLevel: '二级', trafficType: '住宿', maxAmount: 1800, estAmount: 1600, result: 'NORMAL' },
  { cityLevel: '二级', trafficType: '自驾', maxAmount: 600, estAmount: 620, result: 'HIGH' }
]

export const evalData = {
  applyNo: 'TA202609180001',
  fHistory: 88, fCost: 76.5, fFreq: 90, fComplete: 70,
  totalScore: 82.5,
  level: 'SUGGEST',
  evalAt: NOW_TAG,
  factors: [
    { key: 'F_HISTORY', name: '历史产出率', score: 88, weight: 0.35, value: '该对象近 12 个月产出率 5.2，高于均值' },
    { key: 'F_COST', name: '成本占对象产出比', score: 76.5, weight: 0.25, value: '本次预估占该对象近 12 个月产出 0.9%' },
    { key: 'F_FREQ', name: '频次异常度', score: 90, weight: 0.2, value: '近 3 个月同类出差 2 次，未见异常集中' },
    { key: 'F_COMPLETE', name: '事由完整度', score: 70, weight: 0.2, value: '事由 18 字，未写明预期产出' }
  ]
}

/* ---------------- 审批 ---------------- */

export const todoListData = [
  {
    applyNo: 'TA202609180001', reason: '赴深圳客户现场验货并确认交付节点',
    applicant: '翁绍刚', deptName: '销售部', partnerName: '深圳市某某科技有限公司',
    estTotal: 2800, standardResult: 'NORMAL',
    avgAmount: 2600, minAmount: 1800, maxAmount: 3400,
    evalLevel: 'SUGGEST', totalScore: 82.5, status: 'DEPT_REVIEW'
  },
  {
    applyNo: 'TA202609170004', reason: '赴武汉客户现场完成二期需求确认',
    applicant: '陈子豪', deptName: '销售部', partnerName: '武汉某某装备有限公司',
    estTotal: 4380, standardResult: 'EXCEED',
    avgAmount: 2900, minAmount: 2100, maxAmount: 3800,
    evalLevel: 'ATTENTION', totalScore: 58.4, status: 'FINAL_REVIEW'
  },
  {
    applyNo: 'TA202609160002', reason: '赴上海参加产品发布会',
    applicant: '林一鸣', deptName: '市场部', partnerName: '上海某某会展服务有限公司',
    estTotal: 5200, standardResult: 'HIGH',
    avgAmount: 4600, minAmount: 3200, maxAmount: 6000,
    evalLevel: 'NOTICE', totalScore: 71.2, status: 'FINAL_REVIEW'
  }
]

export const historyListData = [
  {
    applyNo: 'TA202609120003', reason: '赴广州参加行业展会并拜访两家意向客户',
    applicant: '翁绍刚', deptName: '销售部', partnerName: '广州某某贸易有限公司',
    estTotal: 1960, standardResult: 'HIGH',
    avgAmount: 2200, minAmount: 1600, maxAmount: 2900,
    evalLevel: 'SUGGEST', totalScore: 79.1, status: 'APPROVED'
  },
  {
    applyNo: 'TA202609050002', reason: '赴长沙供应商工厂完成年度审核',
    applicant: '翁绍刚', deptName: '销售部', partnerName: '长沙某某制造有限公司',
    estTotal: 3240, standardResult: 'EXCEED',
    avgAmount: 2600, minAmount: 1900, maxAmount: 3300,
    evalLevel: 'NOTICE', totalScore: 66.8, status: 'REJECTED'
  }
]

/**
 * 只读提示文案，与后端 ApprovalService.readonlyReason 保持同一套说法，
 * 免得 mock 模式下点「已办」看到的话和真后端不一样。
 */
const READONLY_TEXT = {
  DRAFT: '这单还是草稿，员工提交后才会进审批',
  REJECTED: '这单已被驳回，等员工修改重提',
  APPROVED: '这单已审批通过，不能再改结论',
  CLOSED: '这单已关闭',
  FINAL_REVIEW: '这单已经走到管理层复审，你这一级处理过了，现在只能查看'
}

/**
 * 审批页数据。canApprove 按后端 ApprovalService.canApprove 的口径算：
 * 单据还停在某个审批环节上才 true，已通过 / 已驳回 / 已流转到下一级都是只读。
 *
 * mock 的 decision 会就地改 todoListData 那行的 status，所以同一张单
 * 批完再回来看，这里自动变成只读——和真链路的表现一致。
 */
export const approvalDetailData = (applyNo) => {
  const row = todoListData.find((item) => item.applyNo === applyNo)
    || historyListData.find((item) => item.applyNo === applyNo)
  const status = row ? row.status : (applyNo === 'TA202609170004' ? 'FINAL_REVIEW' : 'DEPT_REVIEW')
  const pending = status === 'DEPT_REVIEW' || status === 'FINAL_REVIEW'
  const base = applyNo === 'TA202609170004' ? otherApplyDetail : applyDetailData
  return {
    // 状态跟着列表走，否则刚审批完回头一看，详情页顶上还挂着「部门审批中」
    apply: { ...base, applyNo, status },
    canApprove: pending,
    readonlyReason: pending ? null : (READONLY_TEXT[status] || '当前不能在这个环节修改'),
    costRef: costRefData,
    standardHints: standardHintsData,
    eval: evalData,
    records: [
      ...(applyDetailData.records || []),
      {
        id: 12, applyNo: applyNo, approver: '戴庆豪', step: 'DEPT_REVIEW',
        result: 'APPROVED', opinion: '同意，客户节点确认属实',
        // 结构与后端 ApprovalService.snapshot() 一致，前端才能拆成几行展示
        snapshot: '{"costRef":{"toCity":"东莞","tripType":"商务拜访","avgAmount":2600,"minAmount":1800,"maxAmount":3600,"sampleCnt":12},"estTotal":2360,"standardResult":"NORMAL","eval":{"level":"SUGGEST","totalScore":82.5}}',
        approvedAt: '2026-09-18 15:02:11'
      }
    ]
  }
}

/* ---------------- 票据 ---------------- */

export const invoiceListData = {
  applyNo: 'TA202609180001',
  totalAmount: 2360,
  invoices: applyDetailData.invoices,
  missing: [
    {
      itineraryId: 102, seq: 2, route: '惠州 → 深圳',
      ticketType: '住宿发票',
      hint: '该段行程 2 天，尚未上传住宿类票据'
    },
    {
      itineraryId: 102, seq: 2, route: '深圳 → 东莞',
      ticketType: '交通票据',
      hint: '自驾出行未上传过路费或油费票据'
    }
  ]
}

export const ocrResultData = {
  invoiceNo: '24412000000012345680',
  amount: 460,
  invoiceDate: '2026-09-22',
  seller: '东莞市常平镇商务酒店有限公司'
}

// I-05 票据影像查询：分页结构 PageResult{list,total,pageNum,pageSize}
// 最后一条刻意不给 imgUrl —— 演示「有票无影像」的空态（财务看得到缺口，才知道要催谁补）
export const invoiceSearchData = {
  total: 5,
  pageNum: 1,
  pageSize: 12,
  list: [
    {
      id: 901, invoiceNo: '24412000000012345678',
      applyNo: 'TA202609180001', applicantName: '翁绍刚',
      amount: 1180, invoiceDate: '2026-09-21',
      seller: '深圳北站餐饮服务有限公司',
      imgPath: '2026/09/invoice_12345678.jpg',
      imgUrl: mockImg('901'),
      thumbUrl: mockImg('901'),
      ocrFlag: 1, dupOf: null
    },
    {
      id: 902, invoiceNo: '24412000000012345679',
      applyNo: 'TA202609180001', applicantName: '翁绍刚',
      amount: 680, invoiceDate: '2026-09-22',
      seller: '深圳市南山区如家酒店',
      imgPath: '2026/09/invoice_12345679.jpg',
      imgUrl: mockImg('902'),
      thumbUrl: mockImg('902'),
      ocrFlag: 1, dupOf: null
    },
    {
      id: 903, invoiceNo: '24412000000012345680',
      applyNo: 'TA202608120003', applicantName: '李文博',
      amount: 460, invoiceDate: '2026-08-14',
      seller: '东莞市常平镇商务酒店有限公司',
      imgPath: '2026/08/invoice_12345680.jpg',
      imgUrl: mockImg('903'),
      thumbUrl: mockImg('903'),
      ocrFlag: 1, dupOf: null
    },
    {
      id: 904, invoiceNo: '24412000000012345681',
      applyNo: 'TA202608120003', applicantName: '李文博',
      amount: 2150, invoiceDate: '2026-08-15',
      seller: '中国铁路广州局集团有限公司',
      imgPath: '2026/08/invoice_12345681.jpg',
      imgUrl: mockImg('904'),
      thumbUrl: mockImg('904'),
      ocrFlag: 0, dupOf: null
    },
    {
      id: 905, invoiceNo: '24412000000012345682',
      applyNo: 'TA202607050002', applicantName: '陈静',
      amount: 320, invoiceDate: '2026-07-07',
      seller: '广州白云机场出租车队',
      imgPath: '',
      imgUrl: '',
      thumbUrl: '',
      ocrFlag: 0, dupOf: null
    }
  ]
}

/* ---------------- 报销与预借 ---------------- */

export const loanData = {
  id: 55, applyNo: 'TA202609180001',
  amount: 2000, offsetAmount: 0, remainAmount: 2000,
  status: 'ACTIVE', createdAt: '2026-09-18 15:02:11'
}

export const expenseDetailData = {
  reportNo: 'ER202609250001',
  applyNo: 'TA202609180001',
  applicant: '翁绍刚',
  totalAmount: 2360,
  offsetAmount: 2000,
  netAmount: 360,
  precheckResult: 'WARN',
  status: 'PENDING',
  createdAt: '2026-09-25 09:11:02',
  invoices: invoiceListData.invoices,
  loan: loanData
}

export const expenseListData = [
  {
    reportNo: 'ER202609250001', applyNo: 'TA202609180001', reason: '赴深圳客户现场验货',
    totalAmount: 2360, offsetAmount: 2000, netAmount: 360,
    precheckResult: 'WARN', status: 'PENDING', createdAt: '2026-09-25 09:11:02'
  },
  {
    reportNo: 'ER202609120006', applyNo: 'TA202609120003', reason: '赴广州参加行业展会',
    totalAmount: 1840, offsetAmount: 1500, netAmount: 340,
    precheckResult: 'PASS', status: 'PAID', createdAt: '2026-09-12 17:22:40'
  }
]

export const precheckIssuesData = [
  { type: 'INVOICE', desc: '第 2 段行程缺少住宿票据', level: 'WARN' },
  { type: 'INVOICE', desc: '第 2 段行程缺少交通票据', level: 'WARN' },
  { type: 'STANDARD', desc: '第 2 段自驾预估 620 元，超出该类差标上限 600 元', level: 'WARN' }
]

/* ---------------- 财务复核 ---------------- */

export const pendingListData = [
  {
    reportNo: 'ER202609250001', applyNo: 'TA202609180001', applicant: '翁绍刚',
    totalAmount: 2360, offsetAmount: 2000, netAmount: 360,
    precheckResult: 'WARN', invoiceCnt: 4, resultCnt: 0, createdAt: '2026-09-25 09:11:02'
  },
  {
    reportNo: 'ER202609240003', applyNo: 'TA202609170004', applicant: '陈子豪',
    totalAmount: 4380, offsetAmount: 3000, netAmount: 1380,
    precheckResult: 'PASS', invoiceCnt: 6, resultCnt: 1, createdAt: '2026-09-24 15:36:18'
  },
  {
    reportNo: 'ER202609230002', applyNo: 'TA202609160002', applicant: '林一鸣',
    totalAmount: 5200, offsetAmount: 4000, netAmount: 1200,
    precheckResult: 'FORCE', invoiceCnt: 8, resultCnt: 0, createdAt: '2026-09-23 11:05:57'
  }
]

export const fourDimData = (reportNo) => ({
  reportNo: reportNo || 'ER202609250001',
  personIssues: [],
  matterIssues: [
    {
      issueCode: 'DATE_MISMATCH',
      desc: '票号 24412000000012345679 开票日期 09-22，落在第 1 段行程区间内但晚于返程填报日',
      refType: 'INVOICE', refId: '24412000000012345679',
      srcValue: '2026-09-22'
    }
  ],
  invoiceIssues: [
    {
      issueCode: 'INV_DUP',
      desc: '发票号 24412000000012345678 与申请 TA202608120003 重复使用',
      refType: 'INVOICE', refId: '24412000000012345678',
      srcValue: 'TA202608120003'
    }
  ],
  accountIssues: [],
  issueCount: 2,
  canForcePass: true
})

/* ---------------- 分析与配置 ---------------- */

export const summaryData = {
  month: '2026-09',
  totalPay: 132000,
  totalOutput: 402000,
  ratio: 0.33,
  attentionCnt: 4
}

export const monthlyData = {
  month: '2026-09',
  rows: [
    { deptId: 2, deptName: '销售部', totalPay: 48000, totalOutput: 156000, ratio: 0.31, applyCnt: 18 },
    { deptId: 3, deptName: '市场部', totalPay: 41000, totalOutput: 88000, ratio: 0.47, applyCnt: 11 },
    { deptId: 4, deptName: '技术部', totalPay: 26000, totalOutput: 121000, ratio: 0.21, applyCnt: 9 },
    { deptId: 5, deptName: '采购部', totalPay: 17000, totalOutput: 37000, ratio: 0.46, applyCnt: 7 }
  ],
  totalPay: 132000,
  totalOutput: 402000,
  ratio: 0.33
}

export const ratioTrend = {
  months: ['2026-04', '2026-05', '2026-06', '2026-07', '2026-08', '2026-09'],
  ratios: [0.41, 0.38, 0.36, 0.39, 0.35, 0.33]
}

export const drillData = {
  deptId: 2,
  month: '2026-09',
  rows: [
    { level: 'REPORT', bizNo: 'ER202609250001', name: '深圳客户验货（翁绍刚）', payAmount: 2360, outputAmount: 12000, ratio: 0.20 },
    { level: 'APPLY', bizNo: 'TA202609180001', name: '赴深圳客户现场验货', payAmount: 1480, outputAmount: 12000, ratio: 0.12 },
    { level: 'APPLY', bizNo: 'TA202609120004', name: '赴广州客户季度复盘', payAmount: 880, outputAmount: 0, ratio: 0 },
    { level: 'REPORT', bizNo: 'ER202609120006', name: '武汉二期需求确认（陈子豪）', payAmount: 4380, outputAmount: 42000, ratio: 0.10 },
    { level: 'REPORT', bizNo: 'ER202609180009', name: '长沙供应商年度审核（林一鸣）', payAmount: 3240, outputAmount: 0, ratio: 0 }
  ]
}

export const partnerIoData = {
  month: '2026-09',
  rows: [
    { partnerId: 3, partnerName: '深圳市某某科技有限公司', partnerType: 'CUSTOMER', totalInvest: 18600, totalOutput: 96000, ratio: 0.19, tripCnt: 7 },
    { partnerId: 4, partnerName: '广州某某贸易有限公司', partnerType: 'CUSTOMER', totalInvest: 12400, totalOutput: 21000, ratio: 0.59, tripCnt: 5 },
    { partnerId: 5, partnerName: '长沙某某制造有限公司', partnerType: 'SUPPLIER', totalInvest: 15200, totalOutput: 0, ratio: 0, tripCnt: 4 },
    { partnerId: 6, partnerName: '武汉某某装备有限公司', partnerType: 'CUSTOMER', totalInvest: 21800, totalOutput: 128000, ratio: 0.17, tripCnt: 8 }
  ]
}

/**
 * 部门清单：值与 travel_server.dept 的初始化数据对齐（budget / standard_level / leader 都照抄库），
 * 这样 mock 模式下看到的数字跟真后端一致，不会一会儿 85000 一会儿 60000。
 * memberCount / applyCount 是后端 DeptVO 给删除按钮做的体检指标，不为 0 的部门删不掉。
 */
export const deptListData = [
  { deptId: 1, deptName: '销售部', monthlyBudget: 80000, standardLevel: '二级', leaderId: 2, approverName: '戴庆豪', memberCount: 1, applyCount: 4 },
  { deptId: 2, deptName: '采购部', monthlyBudget: 50000, standardLevel: '三级', leaderId: 4, approverName: '管理层', memberCount: 0, applyCount: 0 },
  { deptId: 3, deptName: '技术部', monthlyBudget: 40000, standardLevel: '二级', leaderId: 4, approverName: '管理层', memberCount: 1, applyCount: 2 },
  { deptId: 4, deptName: '财务部', monthlyBudget: 10000, standardLevel: '二级', leaderId: 4, approverName: '管理层', memberCount: 1, applyCount: 0 }
]

// 城市等级与trip_standard 取同一套中文口径（一级 ~ 五级），别写成「一线」
export const standardListData = [
  { id: 1, cityLevel: '一级', trafficType: '高铁', maxAmount: 1200, effectiveAt: '2026-01-01', updatedBy: '管理层' },
  { id: 2, cityLevel: '一级', trafficType: '飞机', maxAmount: 2500, effectiveAt: '2026-01-01', updatedBy: '管理层' },
  { id: 4, cityLevel: '二级', trafficType: '高铁', maxAmount: 900, effectiveAt: '2026-01-01', updatedBy: '吴思挺' },
  { id: 5, cityLevel: '二级', trafficType: '飞机', maxAmount: 1800, effectiveAt: '2026-01-01', updatedBy: '管理层' },
  { id: 6, cityLevel: '二级', trafficType: '住宿', maxAmount: 700, effectiveAt: '2026-03-15', updatedBy: '李管理层' },
  { id: 7, cityLevel: '三级', trafficType: '高铁', maxAmount: 700, effectiveAt: '2026-01-01', updatedBy: '吴思挺' }
]

// 留痕：字段与后端 ConfigLogVO / config_log 表一致，targetId 就是 item_key（差标「等级-方式」、部门「dept:id」）
export const configLogData = [
  { id: 31, type: 'STANDARD', action: 'UPDATE', targetId: '二级-高铁', targetName: '二级-高铁', before: '900.00', after: '1000.00', reason: '二线城市高铁票价上调', operator: '吴思挺', operateAt: '2026-09-18 14:05:02' },
  { id: 30, type: 'BUDGET', action: 'UPDATE', targetId: 'dept:1', targetName: '销售部', before: '75000.00', after: '80000.00', reason: '三季度销售部出差计划增加', operator: '李管理层', operateAt: '2026-09-15 10:31:47' },
  { id: 29, type: 'STANDARD', action: 'UPDATE', targetId: '二级-住宿', targetName: '二级-住宿', before: '600.00', after: '700.00', reason: '油价上涨，住宿标准同步调整', operator: '李管理层', operateAt: '2026-09-02 16:20:33' }
]
