<template>
  <div class="page-wrap" v-loading="loading">
    <template v-if="bill.reportNo">
      <!-- 单据头 -->
      <div class="card">
        <div class="card-hd">
          <div>
            <h3>报销复核 · {{ bill.reportNo }}</h3>
            <div class="weak mono" style="margin-top:2px">关联申请 {{ bill.applyNo }} · 报销人 {{ bill.applicant }}</div>
          </div>
          <div class="hd-right">
            <el-tag :type="PRECHECK_RESULT[bill.precheckResult]?.type" effect="plain">预检 {{ PRECHECK_RESULT[bill.precheckResult]?.text }}</el-tag>
            <el-button @click="$router.push('/finance/pending')">返回</el-button>
          </div>
        </div>
        <div class="card-bd">
          <div class="grid-4">
            <div class="kpi-card">
              <div class="kpi-label">票据合计</div>
              <div class="kpi-value amount">¥ {{ money(bill.totalAmount) }}</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-label">预借冲抵</div>
              <div class="kpi-value amount-out">¥ {{ money(bill.offsetAmount) }}</div>
              <div class="kpi-foot">预借号 {{ bill.loan?.id }} · {{ LOAN_STATUS[bill.loan?.status]?.text }}</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-label">应付净额</div>
              <div class="kpi-value amount" style="color: var(--c-primary)">¥ {{ money(bill.netAmount) }}</div>
            </div>
            <div class="kpi-card">
              <div class="kpi-label">异常总数</div>
              <div class="kpi-value" :style="{ color: check.issueCount ? 'var(--c-red)' : 'var(--c-green)' }">
                {{ check.issueCount }}
              </div>
              <div class="kpi-foot">{{ check.canForcePass ? '允许带异常通过' : '不允许带异常通过' }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 四维比对 -->
      <div class="mt-16">
        <div class="section-title">四维比对（E-02）</div>
        <div class="grid-4">
          <div
            v-for="dim in dims" :key="dim.key"
            class="dim-card" :class="{ 'dim-clean': !dim.items.length }"
          >
            <div class="dim-hd">
              <el-icon><component :is="dim.icon" /></el-icon>
              <span>{{ dim.title }}</span>
              <el-badge :value="dim.items.length" :hidden="!dim.items.length" type="danger" />
            </div>
            <div class="dim-desc">{{ dim.desc }}</div>
            <div v-if="dim.items.length" class="dim-body">
              <div v-for="(it, i) in dim.items" :key="i" class="issue">
                <div class="issue-code mono">{{ it.issueCode }}</div>
                <div class="issue-desc">{{ it.desc }}</div>
                <div class="issue-src weak mono">
                  定位 {{ it.refType }} · {{ it.refId }}｜原值 {{ it.srcValue }}
                </div>
              </div>
            </div>
            <div v-else class="dim-clean-tip">未发现异常</div>
          </div>
        </div>
      </div>

      <!-- AI 可疑提示：四维比对抓不一致，这里抓「太巧了」 -->
      <div class="mt-16" v-if="aiEnabled && (aiLoading || aiSus?.points?.length)">
        <AiAdvice
          title="可疑模式提示"
          :points="aiSus?.points || []"
          :summary="aiSus?.summary || ''"
          :loading="aiLoading"
          :local="aiLocal"
          empty-text="这批票据看不出可疑模式"
        />
      </div>

      <el-row :gutter="16" class="mt-16">
        <!-- 票据清单 -->
        <el-col :span="14" :xs="24">
          <div class="card">
            <div class="card-hd"><h3>票据清单</h3></div>
            <div class="card-bd">
              <el-table :data="bill.invoices || []" border size="small">
                <el-table-column prop="invoiceNo" label="发票号" min-width="160" class-name="mono" show-overflow-tooltip />
                <el-table-column prop="seller" label="销售方" min-width="160" show-overflow-tooltip />
                <el-table-column prop="invoiceDate" label="开票日期" width="105" />
                <el-table-column prop="amount" label="金额" width="100" align="right">
                  <template #default="{ row }"><span class="amount">¥ {{ money(row.amount) }}</span></template>
                </el-table-column>
                <!-- 复核就是对照票面看的，只写「已上传」等于让人凭印象判 -->
                <el-table-column label="影像" width="96" align="center">
                  <template #default="{ row }">
                    <div class="img-cell">
                      <InvoiceImage
                        :id="row.id"
                        :img-path="row.imgPath || ''"
                        :img-url="row.imgUrl || ''"
                        :thumb-url="row.thumbUrl || ''"
                        :invoice-no="row.invoiceNo || ''"
                        :size="72"
                      />
                    </div>
                  </template>
                </el-table-column>
              </el-table>
              <div class="row-end amount" style="margin-top:10px; font-size:15px">
                合计 ¥ {{ money(bill.totalAmount) }}
              </div>
            </div>
          </div>
        </el-col>

        <!-- 复核结论 + 付款 -->
        <el-col :span="10" :xs="24">
          <div class="card">
            <div class="card-hd"><h3>复核结论</h3></div>
            <div class="card-bd">
              <el-radio-group v-model="reviewForm.result" class="review-radio">
                <el-radio label="APPROVED">通过</el-radio>
                <el-radio label="PASS_WITH_ISSUE">带异常通过</el-radio>
                <el-radio label="RETURNED">退回</el-radio>
              </el-radio-group>
              <!-- 已提交过的说明摆出来：这单复核完了，理由落了库，回头要能看见当时怎么判的 -->
              <div v-if="bill.reviewReason && bill.status !== 'PENDING'" class="submitted-reason">
                <span class="weak">已提交的说明</span>{{ bill.reviewReason }}
              </div>
              <DraftTextarea
                v-model="reviewForm.reason" class="mt-12"
                biz-type="EXPENSE_REVIEW"
                :biz-no="route.query.reportNo || ''"
                field-name="reviewReason"
                :placeholder="reviewForm.result === 'APPROVED' ? '选填，可留痕备注' : '带异常通过或退回时必须写清原因'"
              />
              <div class="row-end mt-12">
                <el-button type="primary" :loading="reviewing" @click="submitReview">提交复核</el-button>
              </div>
            </div>
          </div>

          <div class="card mt-16">
            <div class="card-hd"><h3>付款与冲抵</h3></div>
            <div class="card-bd">
              <el-descriptions :column="1" size="small" border>
                <el-descriptions-item label="报销单号">{{ bill.reportNo }}</el-descriptions-item>
                <el-descriptions-item label="应收票据合计">¥ {{ money(bill.totalAmount) }}</el-descriptions-item>
                <el-descriptions-item label="冲抵预借款">¥ {{ money(bill.offsetAmount) }}</el-descriptions-item>
                <el-descriptions-item label="实付净额">
                  <span class="amount" style="font-weight:650">¥ {{ money(bill.netAmount) }}</span>
                </el-descriptions-item>
              </el-descriptions>

              <el-form :model="payForm" label-width="88px" size="small" class="mt-12">
                <el-form-item label="实付金额">
                  <el-input-number v-model="payForm.payAmount" :min="0" :precision="2" style="width:100%" />
                </el-form-item>
                <el-form-item label="对冲预借">
                  <el-input
                    :model-value="payForm.loanId ? `预借 #${payForm.loanId}` : '无预借，按实付金额全额支付'"
                    disabled
                  />
                </el-form-item>
                <el-form-item v-if="!canPay" label="为什么不能付">
                  <span class="weak">
                    {{ bill.status === 'PENDING' ? '这张单还没复核，复核通过后才能付款' : '当前状态不可付款（已付款 / 已退回）' }}
                  </span>
                </el-form-item>
                <el-form-item label="付款备注">
                  <DraftTextarea
                    v-model="payForm.payRemark"
                    biz-type="EXPENSE_PAY"
                    :biz-no="route.query.reportNo || ''"
                    field-name="payRemark"
                    :multiline="false"
                    placeholder="如：差旅报销尾款"
                  />
                </el-form-item>
              </el-form>

              <div class="row-end">
                <el-button type="success" :loading="paying" :disabled="!canPay" @click="doPay">确认付款</el-button>
              </div>
            </div>
          </div>
        </el-col>
      </el-row>
    </template>
  </div>
</template>

<script setup>
import { computed, onActivated, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { expenseDetail, fourDimCheck, financeReview, financePay } from '@/api'
import { aiEnabled, aiLocal, aiExpenseSuspicion } from '@/api/ai'
import AiAdvice from '@/components/AiAdvice.vue'
import DraftTextarea from '@/components/DraftTextarea.vue'
import InvoiceImage from '@/components/InvoiceImage.vue'
import { PRECHECK_RESULT, LOAN_STATUS, money } from '@/utils/dict'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const reviewing = ref(false)
const paying = ref(false)

const bill = reactive({})
const check = reactive({ issueCount: 0, canForcePass: true })
const reviewForm = reactive({ result: 'APPROVED', reason: '' })
const payForm = reactive({ payAmount: 0, loanId: null, payRemark: '' })

/** AI 可疑模式提示（后端未接入，走 api/ai.js 本地实现） */
const aiSus = ref(null)
const aiLoading = ref(false)

async function refreshAi () {
  if (!aiEnabled || !bill.invoices?.length) return
  aiLoading.value = true
  try {
    // remote 模式按报销单号查：票据明细后端本身就有，不必再从页面上搬一次
    aiSus.value = await aiExpenseSuspicion({ reportNo: bill.reportNo, invoices: bill.invoices })
  } finally {
    aiLoading.value = false
  }
}

/** 只有复核通过（含带异常通过）的单可以付款，后端 E-04 也是这么判的 */
const canPay = computed(() => ['APPROVED', 'PASS_WITH_ISSUE'].includes(bill.status))

const dims = computed(() => [
  { key: 'person', icon: 'User', title: '人', desc: '申请人、出行人与票据署名是否一致', items: check.personIssues || [] },
  { key: 'matter', icon: 'Calendar', title: '事', desc: '票据时间地点与行程安排是否对得上', items: check.matterIssues || [] },
  { key: 'invoice', icon: 'Ticket', title: '票', desc: '查重、影像缺失、金额是否异常', items: check.invoiceIssues || [] },
  { key: 'account', icon: 'Coin', title: '账', desc: '票据合计与报销单金额是否勾稽一致', items: check.accountIssues || [] }
])

async function load () {
  const reportNo = route.query.reportNo
  if (!reportNo) return ElMessage.warning('缺少报销单号')
  loading.value = true
  try {
    const [detail, ck] = await Promise.all([expenseDetail(reportNo), fourDimCheck(reportNo)])
    Object.assign(bill, detail)
    Object.assign(check, ck)
    payForm.payAmount = detail.netAmount
    payForm.loanId = detail.loan?.id
    reviewForm.result = ck.issueCount ? 'PASS_WITH_ISSUE' : 'APPROVED'
    await refreshAi()
  } finally {
    loading.value = false
  }
}

async function submitReview () {
  if (reviewForm.result !== 'APPROVED' && !reviewForm.reason) {
    return ElMessage.warning('带异常通过或退回时必须填写说明')
  }
  reviewing.value = true
  try {
    await financeReview(route.query.reportNo, { ...reviewForm })
    ElMessage.success('复核结论已提交')
    // 说明已经进了报销单，框清掉（草稿也会被后端一并清掉）
    reviewForm.reason = ''
    // 以前这里无条件跳回 /finance/pending，而这张单已经不是 PENDING 了，
    // 列表里没有它，付款卡片又被留在再也回不去的详情页——付款这一步直接断掉。
    // 现在停在本页刷新：状态标签变「复核通过」，付款卡片立即可用。
    await load()
  } finally {
    reviewing.value = false
  }
}

async function doPay () {
  if (!canPay.value) return ElMessage.warning('这张单还没复核通过，不能付款')
  paying.value = true
  try {
    const res = await financePay(route.query.reportNo, { ...payForm })
    ElMessage.success(`付款完成，流水号 ${res.id}，对应的出差申请单已关闭`)
    // 付完这张单是 PAID 了，别再丢回空空如也的待复核列表
    router.push({ path: '/finance/pending', query: { tab: 'paid' } })
  } finally {
    paying.value = false
  }
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'FinDetail' })

/** 已经读进来的是哪一张单。空串 = 还没读过 */
let loadedNo = ''

/**
 * 同样是照地址栏的 reportNo 开内容，缓存住要先看单号变没变：
 * - 变了：清掉说明类字段再 load。load() 只覆盖 result / payAmount / loanId，
 *   reviewForm.reason 与 payForm.payRemark 是它碰不到的——不清的话，
 *   上一张单写的退回理由会跟着下一张单一起提交。
 * - 没变：不动，正在填的复核结论与付款金额留着（本页复核完就是停在这儿刷新的）
 *
 * 只用 activated：首次进入时它也会触发，不必再留一个 onMounted。
 */
onActivated(() => {
  const no = route.query.reportNo || ''
  if (!no || no === loadedNo) return
  reviewForm.reason = ''
  payForm.payRemark = ''
  loadedNo = no
  load()
})
</script>

<style scoped>
.hd-right { display: flex; align-items: center; gap: 10px; }

/* 复核过的单把当初那句说明摆出来：结论在状态标签上，理由在这行 */
.submitted-reason {
  display: flex;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 6px;
  background: var(--c-green-soft);
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--c-text-sub);
}
.submitted-reason .weak { flex: none; }

.dim-card {
  background: #fff;
  border: 1px solid var(--c-border);
  border-radius: 10px;
  padding: 12px 14px;
  box-shadow: var(--shadow-sm);
  border-left: 3px solid var(--c-red);
}
.dim-card.dim-clean { border-left-color: var(--c-green); }

.dim-hd {
  display: flex; align-items: center; gap: 6px;
  font-size: 13.5px; font-weight: 650;
}
.dim-desc { font-size: 11.5px; color: var(--c-text-weak); margin: 3px 0 8px; }

.dim-body { max-height: 200px; overflow: auto; }
.issue {
  background: #fbf7f7;
  border: 1px solid #f0e0e0;
  border-radius: 7px;
  padding: 8px 10px;
  margin-bottom: 7px;
}
.issue-code { font-size: 11px; color: var(--c-red); font-weight: 600; }
.issue-desc { font-size: 12.5px; margin: 2px 0; }
.issue-src { font-size: 11px; word-break: break-all; }

.dim-clean-tip {
  font-size: 12.5px; color: var(--c-green);
  background: var(--c-green-soft); border-radius: 6px; padding: 6px 10px;
}

.review-radio { display: flex; flex-direction: column; gap: 6px; }
.review-radio :deep(.el-radio) { margin-right: 0; }

.img-cell { display: flex; justify-content: center; }
</style>
