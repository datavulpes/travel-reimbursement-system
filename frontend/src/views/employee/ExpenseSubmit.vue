<template>
  <div class="page-wrap">
    <el-row :gutter="16">
      <el-col :span="15" :xs="24">
        <div class="card">
          <div class="card-hd">
            <h3>报销单提交</h3>
            <span class="weak">票据合计减去预借冲抵，剩下的才是付给你的钱</span>
          </div>
          <div class="card-bd">
            <el-form label-width="110px" size="small">
              <el-form-item label="关联申请单">
                <el-select v-model="applyNo" filterable style="width:100%" @change="loadAll">
                  <el-option v-for="a in applyOptions" :key="a.applyNo" :label="`${a.applyNo} · ${a.reason}`" :value="a.applyNo" />
                </el-select>
              </el-form-item>
            </el-form>

            <template v-if="applyNo && bill.totalAmount !== undefined">
              <!-- 冲抵计算 -->
              <div class="calc-panel">
                <div class="calc-row">
                  <span class="calc-label">票据合计</span>
                  <span class="calc-value amount">¥ {{ money(bill.totalAmount) }}</span>
                </div>
                <div class="calc-row">
                  <span class="calc-label">预借冲抵</span>
                  <span class="calc-value amount-out">- ¥ {{ money(bill.offsetAmount) }}</span>
                </div>
                <el-divider style="margin:10px 0" />
                <div class="calc-row net">
                  <span class="calc-label">应付净额</span>
                  <span class="calc-value amount">¥ {{ money(bill.netAmount) }}</span>
                </div>
              </div>

              <div class="grid-3 mt-16">
                <div class="kpi-card">
                  <div class="kpi-label">预借额度</div>
                  <div class="kpi-value amount" style="font-size:20px">¥ {{ money(loan.amount) }}</div>
                  <div class="kpi-foot">可冲抵余额 ¥ {{ money(loan.remainAmount) }}</div>
                </div>
                <div class="kpi-card">
                  <div class="kpi-label">票据张数</div>
                  <div class="kpi-value" style="font-size:20px">{{ bill.invoices?.length || 0 }}</div>
                  <div class="kpi-foot">缺票 {{ bill.missing?.length || 0 }} 项</div>
                </div>
                <div class="kpi-card">
                  <div class="kpi-label">预检结论</div>
                  <div class="kpi-value" style="font-size:20px">
                    <el-tag v-if="bill.precheckResult" :type="PRECHECK_RESULT[bill.precheckResult]?.type" size="small">
                      {{ PRECHECK_RESULT[bill.precheckResult]?.text }}
                    </el-tag>
                    <span v-else class="weak">未预检</span>
                  </div>
                  <div class="kpi-foot">提交时自动执行</div>
                </div>
              </div>

              <!-- 预检明细 -->
              <div v-if="bill.checks?.length" class="mt-16">
                <div class="section-title">预检明细</div>
                <div v-for="(c, i) in bill.checks" :key="i" class="check-row">
                  <el-tag :type="LEVEL_TYPE[c.level] || 'info'" size="small" class="check-tag">{{ c.level }}</el-tag>
                  <span class="check-desc">{{ c.desc }}</span>
                </div>
              </div>

              <!-- 票据清单 -->
              <div class="section-title mt-16">票据清单</div>
              <el-table :data="bill.invoices || []" border size="small">
                <!-- 报销时核对的是票面。少了这一列，报销人只能凭票号回想是哪一张 -->
                <el-table-column label="影像" width="84" align="center" class-name="img-cell">
                  <template #default="{ row }">
                    <InvoiceImage
                      :img-path="row.imgPath" :img-url="row.imgUrl" :thumb-url="row.thumbUrl"
                      :invoice-no="row.invoiceNo" :id="row.id" :size="60"
                    />
                  </template>
                </el-table-column>
                <el-table-column prop="invoiceNo" label="发票号" min-width="170" class-name="mono" show-overflow-tooltip />
                <el-table-column prop="seller" label="销售方" min-width="170" show-overflow-tooltip />
                <el-table-column prop="invoiceDate" label="开票日期" width="110" />
                <el-table-column prop="amount" label="金额" width="110" align="right">
                  <template #default="{ row }"><span class="amount">¥ {{ money(row.amount) }}</span></template>
                </el-table-column>
              </el-table>

              <!-- 缺票 -->
              <div v-if="bill.missing?.length" class="mt-16">
                <div class="section-title">缺票提醒（{{ bill.missing.length }}）</div>
                <div v-for="m in bill.missing" :key="m.itineraryId + m.ticketType" class="tip-box tip-warn" style="margin-bottom:6px">
                  第 {{ m.seq }} 段 {{ m.route }}：{{ m.hint }}
                </div>
              </div>

              <!-- 强制说明 -->
              <div v-if="needForce" class="mt-16">
                <div class="section-title">强制提交说明</div>
                <DraftTextarea
                  v-model="forceReason"
                  biz-type="EXPENSE_SUBMIT"
                  :biz-no="applyNo"
                  field-name="forceReason"
                  placeholder="例如：纸质票据已交财务归档，系统影像后补"
                />
              </div>

              <div class="row-end mt-16">
                <el-button :loading="previewing" @click="doPreview">预检一次</el-button>
                <el-button type="primary" :loading="submitting" @click="submit">提交报销</el-button>
              </div>
            </template>

            <el-empty v-else description="先选择一个出差申请单" />
          </div>
        </div>
      </el-col>

      <!-- 右：规则说明 -->
      <el-col :span="9" :xs="24">
        <div class="card">
          <div class="card-hd"><h3>预检会查什么</h3></div>
          <div class="card-bd">
            <div class="rule-item">
              <div class="rule-title"><el-icon><Coin /></el-icon>差标 STANDARD</div>
              <div class="rule-desc">逐段比对本次预估与差标上限，超限需要说明</div>
            </div>
            <div class="rule-item">
              <div class="rule-title"><el-icon><Calendar /></el-icon>行程 TRIP</div>
              <div class="rule-desc">行程段时间是否重叠、票据日期是否落在行程区间内</div>
            </div>
            <div class="rule-item">
              <div class="rule-title"><el-icon><Tickets /></el-icon>票据 INVOICE</div>
              <div class="rule-desc">每段有无对应票种、发票号是否重复、影像是否缺失</div>
            </div>
          </div>
        </div>

        <div class="card mt-16">
          <div class="card-hd"><h3>我的报销记录</h3></div>
          <div class="card-bd">
            <el-table :data="myExpenses" border size="small">
              <el-table-column prop="reportNo" label="报销单号" min-width="140" class-name="mono" show-overflow-tooltip />
              <el-table-column prop="netAmount" label="净额" width="100" align="right">
                <template #default="{ row }"><span class="amount">¥ {{ money(row.netAmount) }}</span></template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="EXPENSE_STATUS[row.status]?.type" size="small">
                    {{ EXPENSE_STATUS[row.status]?.text }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onActivated, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  myApplyPage, applyDetail, invoiceList, loanInfo, myExpensePage, submitExpense, expensePreview
} from '@/api'
import { money, PRECHECK_RESULT, EXPENSE_STATUS } from '@/utils/dict'
import DraftTextarea from '@/components/DraftTextarea.vue'
import InvoiceImage from '@/components/InvoiceImage.vue'

/** 预检明细的严重度 → 标签颜色 */
const LEVEL_TYPE = { PASS: 'info', WARN: 'warning', FORCE: 'danger', BLOCK: 'danger' }

const applyNo = ref('')
const applyOptions = ref([])
const myExpenses = ref([])
const submitting = ref(false)
const previewing = ref(false)
const forceReason = ref('')

const loan = reactive({})
const bill = reactive({ invoices: [], missing: [], totalAmount: 0 })

const needForce = computed(
  () => bill.missing?.length > 0 || ['WARN', 'FORCE'].includes(bill.precheckResult)
)

async function loadOptions () {
  const data = await myApplyPage({ pageNum: 1, pageSize: 50 })
  applyOptions.value = data.list.filter((a) => ['APPROVED', 'CLOSED'].includes(a.status))
  if (!applyNo.value && applyOptions.value.length) {
    applyNo.value = applyOptions.value[0].applyNo
  }
}

/**
 * D-01 预借额度。没有预借款的申请，后端返回 1001「该申请还没有预借款」，
 * 这里必须自己吞掉——直接 Promise.all 会让整页数据全空，员工还以为接口坏了。
 */
async function loadLoan (no) {
  Object.keys(loan).forEach((k) => delete loan[k])
  try {
    Object.assign(loan, await loanInfo(no))
  } catch (e) {
    if (e?.code === 1001) {
      Object.assign(loan, { amount: 0, offsetAmount: 0, remainAmount: 0, hasLoan: false })
    } else {
      throw e
    }
  }
}

async function loadAll () {
  if (!applyNo.value) return
  const [inv] = await Promise.all([
    invoiceList({ applyNo: applyNo.value }),
    loadLoan(applyNo.value)
  ])
  Object.assign(bill, inv)
  // 没预借或字段缺失时按 0 处理，别算出 NaN 显示在页面上
  const loanAmount = Number(loan.amount || 0)
  const loanUsed = Number(loan.offsetAmount || 0)
  bill.offsetAmount = Math.min(Number(bill.totalAmount || 0), Math.max(loanAmount - loanUsed, 0))
  bill.netAmount = Math.max(Number(bill.totalAmount || 0) - bill.offsetAmount, 0)
}

async function loadMine () {
  const data = await myExpensePage({ pageNum: 1, pageSize: 5 })
  myExpenses.value = data.list
}

async function doPreview () {
  if (!applyNo.value) return ElMessage.warning('先选择一个出差申请单')
  previewing.value = true
  try {
    // 之前这里是本地装样子（只看缺票），差标超标这种 FORCE 项根本看不见，
    // 提交必然被 2004 拦下却没有地方写说明。现在走真预检接口，口径与提交一致。
    const res = await expensePreview({ applyNo: applyNo.value })
    bill.precheckResult = res.precheckResult
    bill.checks = res.checks || []
    // 冲抵/净额以后端算的为准，别用前端本地记账的数
    if (res.offsetAmount !== undefined) {
      bill.offsetAmount = res.offsetAmount
      bill.netAmount = res.netAmount
    }
    if (res.precheckResult === 'FORCE') {
      ElMessage.warning('预检为 FORCE，请填写强制提交说明后再提交')
    }
  } finally {
    previewing.value = false
  }
}

async function submit () {
  if (needForce.value && !forceReason.value) {
    return ElMessage.warning('存在缺票或提醒项，请填写强制提交说明')
  }
  submitting.value = true
  try {
    const res = await submitExpense({ applyNo: applyNo.value, forceReason: forceReason.value })
    bill.precheckResult = res.precheckResult
    forceReason.value = ''
    ElMessage.success(`提交成功，报销单号 ${res.reportNo}`)
    await Promise.all([loadMine(), loadAll()])
  } catch (e) {
    // 2004 = 后端预检 FORCE 且没带说明。此时把真预检结果拉回来，
    // 明细和强制说明输入框就都出来了，用户不用对着红字干瞪眼
    if (e?.code === 2004) await doPreview()
    throw e
  } finally {
    submitting.value = false
  }
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'EmpExpenseSubmit' })

/**
 * 每次回到这页把下拉和「我的报销单」拉一遍：刚提交过的申请、在别处新传的票据都要看得到。
 * 已经选中的那张单和用户填了一半的强制说明留在原处不动。
 * 用 activated 而不是 mounted：组件进了 keep-alive 之后切走不再 mounted，
 * 只靠 mounted 的话回到这页全是上一次进来时的数据。（首次进入 activated 也会触发）
 */
onActivated(async () => {
  await Promise.all([loadOptions(), loadMine()])
  if (applyNo.value) await loadAll()
})
</script>

<style scoped>
/* 影像列：缩略图是定宽块级元素，靠 flex 居中，别让它贴在单元格左边 */
.img-cell :deep(.cell) {
  display: flex;
  align-items: center;
  justify-content: center;
  padding-top: 4px;
  padding-bottom: 4px;
}

.calc-panel {
  border: 1px solid var(--c-border);
  border-radius: 10px;
  padding: 14px 16px;
  background: #fafbfd;
}
.calc-row { display: flex; justify-content: space-between; align-items: baseline; padding: 3px 0; }
.calc-label { font-size: 13px; color: var(--c-text-sub); }
.calc-value { font-size: 16px; font-weight: 600; }
.calc-row.net .calc-value { font-size: 22px; color: var(--c-primary); }

.rule-item { padding: 9px 0; border-bottom: 1px dashed var(--c-border); }

.check-row { display: flex; align-items: flex-start; gap: 8px; padding: 5px 0; }
.check-tag { flex: none; width: 58px; justify-content: center; }
.check-desc { font-size: 12.5px; color: var(--c-text-sub); line-height: 1.5; }
.rule-item:last-of-type { border-bottom: none; }
.rule-title { font-size: 13px; font-weight: 600; display: flex; align-items: center; gap: 6px; }
.rule-desc { font-size: 12.5px; color: var(--c-text-sub); margin-top: 2px; padding-left: 20px; }
</style>
