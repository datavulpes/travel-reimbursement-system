<template>
  <div class="page-wrap" v-loading="loading">
    <template v-if="data.apply">
      <!-- 顶部单据头 -->
      <div class="card">
        <div class="card-hd">
          <div>
            <h3>{{ data.apply.reason }}</h3>
            <div class="weak mono" style="margin-top:2px">{{ data.apply.applyNo }}</div>
          </div>
            <div class="hd-right">
              <el-tag :type="APPLY_STATUS[data.apply.status]?.type" effect="dark">
                {{ APPLY_STATUS[data.apply.status]?.text }}
              </el-tag>
              <el-tag v-if="!canApprove" type="info" effect="plain">只读 · 仅供查看</el-tag>
              <el-tag v-if="data.apply.status === 'FINAL_REVIEW'" type="warning" effect="plain">
                当前环节：管理层复审
              </el-tag>
              <el-tag v-else-if="data.apply.status === 'DEPT_REVIEW'" type="info" effect="plain">
                当前环节：部门初审
              </el-tag>
              <el-button @click="goBack">返回列表</el-button>
            </div>
        </div>
        <div class="card-bd">
          <el-descriptions :column="4" border size="small">
            <el-descriptions-item label="申请人">{{ data.apply.applicant }}</el-descriptions-item>
            <el-descriptions-item label="所属部门">{{ data.apply.deptName }}</el-descriptions-item>
            <el-descriptions-item label="关联对象">{{ data.apply.partnerName }}</el-descriptions-item>
            <el-descriptions-item label="提交时间">{{ data.apply.createdAt }}</el-descriptions-item>
            <el-descriptions-item label="预估交通"><span class="amount">¥ {{ money(data.apply.estTraffic) }}</span></el-descriptions-item>
            <el-descriptions-item label="预估住宿"><span class="amount">¥ {{ money(data.apply.estHotel) }}</span></el-descriptions-item>
            <el-descriptions-item label="预估合计"><span class="amount">¥ {{ money(data.apply.estTotal) }}</span></el-descriptions-item>
            <el-descriptions-item label="超标说明">{{ data.apply.exceedReason || '—' }}</el-descriptions-item>
            <!-- 「其他」两个字本身不带信息，只有选了它才有必要把这句说明摊开给审批人看 -->
            <el-descriptions-item v-if="data.apply.tripType === TRIP_TYPE_OTHER" :span="3" label="类型补充说明">
              {{ data.apply.tripTypeNote || '—' }}
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </div>

      <!-- AI 审批摘要：先看一遍风险，再往下翻细节 -->
      <div class="mt-16" v-if="aiEnabled">
        <AiAdvice
          title="审批风险摘要"
          :points="aiRisk?.points || []"
          :summary="aiRisk?.summary || ''"
          :loading="aiLoading"
          :local="aiLocal"
          empty-text="正在汇总这张单的风险点…"
        />
      </div>

      <!-- 三张判断卡 -->
      <div class="grid-3 mt-16">
        <div class="card">
          <div class="card-hd"><h3>差标校验</h3></div>
          <div class="card-bd">
            <el-table :data="data.standardHints || []" size="small" border>
              <el-table-column prop="trafficType" label="类别" width="80" />
              <el-table-column prop="maxAmount" label="上限" width="90" align="right">
                <template #default="{ row }"><span class="amount">{{ money(row.maxAmount) }}</span></template>
              </el-table-column>
              <el-table-column prop="estAmount" label="本次" width="90" align="right">
                <template #default="{ row }"><span class="amount">{{ money(row.estAmount) }}</span></template>
              </el-table-column>
              <el-table-column label="结论" width="90" align="center">
                <template #default="{ row }">
                  <el-tag :type="STANDARD_RESULT[row.result]?.type" size="small">
                    {{ STANDARD_RESULT[row.result]?.text }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <div class="card">
          <div class="card-hd"><h3>同类花费参考</h3></div>
          <div class="card-bd">
            <div class="ref-dest">{{ data.costRef?.toCity }} · {{ data.costRef?.tripType }}</div>
            <div class="ref-main amount">¥ {{ money(data.costRef?.avgAmount) }}</div>
            <div class="weak">近 12 个月均值 · 样本 {{ data.costRef?.sampleCnt }} 单</div>
            <el-divider style="margin:12px 0" />
            <div class="range-line">
              <span>区间下限</span>
              <span class="amount">¥ {{ money(data.costRef?.minAmount) }}</span>
            </div>
            <div class="range-line">
              <span>区间上限</span>
              <span class="amount">¥ {{ money(data.costRef?.maxAmount) }}</span>
            </div>
            <div class="range-line highlight">
              <span>本次预估</span>
              <span class="amount">¥ {{ money(data.apply.estTotal) }}</span>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card-hd">
            <h3>合理性评价</h3>
            <el-tag :type="EVAL_LEVEL[data.eval?.level]?.type" effect="dark">
              {{ EVAL_LEVEL[data.eval?.level]?.text }}
            </el-tag>
          </div>
          <div class="card-bd">
            <div class="eval-score">
              <span class="amount">{{ data.eval?.totalScore }}</span>
              <span class="weak"> / 100 加权总分</span>
            </div>
            <div class="factor-list">
              <div v-for="f in data.eval?.factors || []" :key="f.key" class="factor">
                <div class="factor-top">
                  <span>{{ f.name }}</span>
                  <span class="weak">权重 {{ f.weight }}</span>
                </div>
                <el-progress :percentage="f.score" :stroke-width="7" :show-text="false" />
                <div class="factor-val weak">{{ f.value }}</div>
              </div>
            </div>
            <!-- AI 写法解读：补规则读不懂的那一维，只给 Δ 不给档位 -->
            <div v-if="aiSem" class="ai-sem">
              <ul v-if="aiSem.reasons?.length" class="ai-sem-list">
                <li v-for="(r, i) in aiSem.reasons" :key="i" :class="'lv-' + (r.level || 'low')">
                  <div v-if="r.level === 'high' || r.level === 'mid'" class="ai-tag">
                    {{ r.level === 'high' ? '要紧' : '留意' }}
                  </div>
                  <div>{{ r.point }}</div>
                </li>
              </ul>
              <div v-else class="weak" style="font-size:12px">没有额外提醒。</div>
            </div>
          </div>
        </div>
      </div>

      <el-row :gutter="16" class="mt-16">
        <!-- 行程 -->
        <el-col :span="12" :xs="24">
          <div class="card">
            <div class="card-hd"><h3>行程安排</h3></div>
            <div class="card-bd">
              <el-table :data="data.apply.itineraries || []" border size="small">
                <el-table-column type="index" label="段" width="50" />
                <el-table-column prop="startDate" label="出发" width="110" />
                <el-table-column prop="endDate" label="返回" width="110" />
                <el-table-column label="行程" min-width="130">
                  <template #default="{ row }">{{ row.fromCity }} → {{ row.toCity }}</template>
                </el-table-column>
                <el-table-column prop="trafficType" label="交通" width="90" />
                <el-table-column prop="cityLevel" label="城市等级" width="90" />
              </el-table>
            </div>
          </div>

          <!-- 审批操作：只在轮到自己这一级时才出现。
               从「已办」回看时 canApprove 为 false，换成只读说明，别让人对着空页面猜 -->
          <div v-if="canApprove" class="card mt-16">
            <div class="card-hd">
              <h3>审批意见</h3>
              <span class="weak">{{ isManager ? '复审通过会生成预借款，额度取预估与部门预算的较小值' : '通过后流转到管理层复审，结论与依据一起存档' }}</span>
            </div>
            <div class="card-bd">
              <el-radio-group v-model="form.result">
                <el-radio-button label="APPROVED">通过</el-radio-button>
                <el-radio-button label="REJECTED">驳回</el-radio-button>
              </el-radio-group>
              <DraftTextarea
                v-model="form.opinion" class="mt-12"
                biz-type="APPLY_REVIEW"
                :biz-no="route.query.applyNo || ''"
                field-name="opinion"
                :placeholder="form.result === 'REJECTED' ? '驳回必须写明原因，员工改完好重提' : '写点依据，后面复盘时有用'"
              />
              <div class="row-end mt-12">
                <el-button @click="goBack">稍后处理</el-button>
                <el-button
                  :type="form.result === 'REJECTED' ? 'danger' : 'primary'"
                  :loading="submitting"
                  @click="submit"
                >
                  提交结论
                </el-button>
              </div>
            </div>
          </div>

          <div v-else class="card mt-16">
            <div class="card-hd">
              <h3>审批意见</h3>
              <el-tag size="small" type="info" effect="plain">已归档 · 不可修改</el-tag>
            </div>
            <div class="card-bd">
              <div class="tip-box tip-info">
                {{ data.readonlyReason || '这单你已经处理过，这里只能查看当时的依据，不能再改结论。' }}
              </div>
              <div v-if="myRecord" class="verdict-line">
                <span class="weak">你当时的结论</span>
                <el-tag :type="APPROVAL_RESULT[myRecord.result]?.type" size="small" effect="dark">
                  {{ APPROVAL_RESULT[myRecord.result]?.text }}
                </el-tag>
                <span>{{ myRecord.opinion || '（未填写意见）' }}</span>
                <span class="weak mono">{{ myRecord.approvedAt }}</span>
              </div>
              <div class="row-end mt-12">
                <el-button @click="goBack">返回列表</el-button>
              </div>
            </div>
          </div>
        </el-col>

        <!-- 流转记录：给到半屏宽，快照才有地方并排摆 -->
        <el-col :span="12" :xs="24">
          <div class="card">
            <div class="card-hd"><h3>流转记录</h3></div>
            <div class="card-bd">
              <FlowTimeline
                :records="data.records || []"
                :status="data.apply.status"
                empty-text="这张单还没有流转记录。"
              />
            </div>
          </div>
        </el-col>
      </el-row>

      <!-- 票据影像：只在真的有票时出现，审批阶段通常还没有（票是批完之后才让传的） -->
      <div class="card mt-16" v-if="invoices.length">
        <div class="card-hd"><h3>票据影像</h3></div>
        <div class="card-bd inv-wall">
          <div v-for="item in invoices" :key="item.id" class="inv-wall__item">
            <InvoiceImage
              :id="item.id"
              :img-path="item.imgPath || ''"
              :img-url="item.imgUrl || ''"
              :thumb-url="item.thumbUrl || ''"
              :invoice-no="item.invoiceNo || ''"
              :size="72"
            />
            <div class="inv-wall__no mono">{{ item.invoiceNo || '—' }}</div>
            <div class="inv-wall__amt amount">¥ {{ money(item.amount) }}</div>
          </div>
        </div>
      </div>
    </template>

    <!-- 详情没拉出来时给个空态和出口，别只留一片白让人不知道发生了什么 -->
    <div v-else-if="!loading" class="card">
      <div class="card-bd">
        <div class="tip-box tip-warn">
          {{ loadError || '没有取到这张单据的详情，可能已经没有查看权限。' }}
        </div>
        <div class="row-end mt-12">
          <el-button @click="goBack">返回列表</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onActivated, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { approvalDetail, approvalDecision, invoiceList } from '@/api'
import { useUserStore } from '@/stores/user'
import { APPLY_STATUS, STANDARD_RESULT, EVAL_LEVEL, APPROVAL_RESULT, TRIP_TYPE_OTHER, money } from '@/utils/dict'
import { aiEnabled, aiLocal, aiApprovalRisk, aiSemanticEval } from '@/api/ai'
import FlowTimeline from '@/components/FlowTimeline.vue'
import AiAdvice from '@/components/AiAdvice.vue'
import DraftTextarea from '@/components/DraftTextarea.vue'
import InvoiceImage from '@/components/InvoiceImage.vue'

const route = useRoute()
const router = useRouter()
const user = useUserStore()

// 部门审批人做初审，管理层做复审，同一个页面两种结论。
// 复审是第一级审核之后的终审，通过后钱就出去了（生成预借款），文案要分开说。
const isManager = computed(() => user.role === 'MANAGER')

/**
 * 能不能下结论由后端说了算（ApprovalService.canApprove）：
 * 单据正停在自己这一级审批环节上才是 true。从「已办」回看、或单子已经批完，
 * 后端给 false，页面就退成只读——能看当时的依据，但改不了任何东西。
 * 这里判 === true 而不是 !== false：后端还没返回时先按只读渲染，不会误给出审批按钮。
 */
const canApprove = computed(() => data.canApprove === true)

/** 我在这张单上签过的最后一条结论。回看时直接摆出来，省得去右栏时间线里翻 */
const myRecord = computed(() =>
  (data.records || []).filter((r) => r.approver === user.realName).pop()
)

// 从哪个 tab 进来的，返回就回哪个 tab，别把人丢回待办列表
const tab = computed(() => (route.query.tab === 'done' ? 'done' : 'todo'))
function goBack () {
  router.push({ path: '/approver/todo', query: { tab: tab.value } })
}

const loading = ref(false)
const submitting = ref(false)
const loadError = ref('')
const data = reactive({})
/** 本单票据影像，没有票时整块卡片不渲染 */
const invoices = ref([])
const form = reactive({ result: 'APPROVED', opinion: '' })

/**
 * AI 两件套：风险摘要 + 语义调节 Δ。
 * 走 api/ai.js 的本地实现（后端 AI 未开发），结构与后端契约一致。
 */
const aiRisk = ref(null)
const aiSem = ref(null)
const aiLoading = ref(false)

async function refreshAi () {
  if (!aiEnabled || !data.apply) return
  aiLoading.value = true
  try {
    const [risk, sem] = await Promise.all([
      // risk 只需要单号：这五份数据后端自己就有，
      // 再由前端传一遍等于在页面上另造一个可能与单据不一致的版本
      aiApprovalRisk({ applyNo: route.query.applyNo }),
      aiSemanticEval({
        reason: data.apply.reason,
        tripType: data.apply.tripType || data.costRef?.tripType || '',
        partnerId: data.apply.partnerId,
        itineraries: data.apply.itineraries
      })
    ])
    aiRisk.value = risk
    aiSem.value = sem
  } finally {
    aiLoading.value = false
  }
}

/**
 * 本单已上传的票据影像。
 *
 * 票据是审批通过之后才让传的（后端 upload 卡了单据状态），所以审批阶段这一块通常为空，
 * 空就不显示整张卡片，不占地方。
 *
 * 后端对「我还没签过字的单」也会拒绝（审批人只能看自己审过的），
 * 那种情况静默当成没有票——审批页面弹一个「无权限」出来纯属添乱。
 */
async function loadInvoices (applyNo) {
  invoices.value = []
  try {
    const res = await invoiceList({ applyNo })
    invoices.value = res?.invoices || []
  } catch (e) {
    invoices.value = []
  }
}

async function load () {
  const applyNo = route.query.applyNo
  if (!applyNo) return ElMessage.warning('缺少申请号')
  loading.value = true
  loadError.value = ''
  Object.keys(data).forEach((k) => delete data[k])
  try {
    Object.assign(data, await approvalDetail(applyNo))
    await loadInvoices(applyNo)
    // 详情到位了才算 AI：它要吃单据、参考、差标、评价、流转记录五份数据
    await refreshAi()
  } catch (e) {
    // 拦截层已经弹过一次提示，这里补个页面内的空态，别留一片白
    loadError.value = e?.msg || '这张单据的详情没有加载出来'
  } finally {
    loading.value = false
  }
}

async function submit () {
  // 只读页面上不该出现提交按钮，再加一道保险，防止接口放过了一线都不该改的东西
  if (!canApprove.value) return ElMessage.warning('当前是只读查看，不能提交结论')
  if (form.result === 'REJECTED' && !form.opinion) {
    return ElMessage.warning('驳回必须填写审批意见')
  }
  submitting.value = true
  try {
    await approvalDecision(route.query.applyNo, { ...form })
    ElMessage.success('审批结论已提交')
    router.push({ path: '/approver/todo', query: { tab: tab.value } })
  } finally {
    submitting.value = false
  }
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'ApvDetail' })

/** 已经读进来的是哪一张单。空串 = 还没读过 */
let loadedNo = ''

/**
 * 详情页是照地址栏上的 applyNo 开内容的，缓存住就会停在上一次那张单上，
 * 所以进来先看单号变没变：
 * - 变了：清掉上一张单的审批意见再 load。form 里的 result / opinion 是 load() 不管的，
 *   不清就变成拿 A 单写的意见去批 B 单——这是缓存详情页最危险的一处。
 * - 没变：一个字都不动，正在写的审批意见留着（中途切去看票据影像再回来）
 *
 * 只用 activated：首次进入时它也会触发，不必再留一个 onMounted。
 */
onActivated(() => {
  const no = route.query.applyNo || ''
  if (!no || no === loadedNo) return
  form.result = 'APPROVED'
  form.opinion = ''
  // 上一张单的 AI 结论也不能留，它吃的是上一张单的事由与行程
  aiRisk.value = null
  aiSem.value = null
  loadedNo = no
  load()
})
</script>

<style scoped>
.hd-right { display: flex; align-items: center; gap: 10px; }

.ref-dest { font-size: 13px; color: var(--c-text-sub); }
.ref-main { font-size: 26px; font-weight: 650; margin: 2px 0; }
.range-line { display: flex; justify-content: space-between; font-size: 13px; padding: 3px 0; }
.range-line.highlight { color: var(--c-primary); font-weight: 600; border-top: 1px dashed var(--c-border); margin-top: 4px; padding-top: 7px; }

.eval-score { font-size: 13px; }
.eval-score .amount { font-size: 26px; font-weight: 650; }
.factor-list { margin-top: 10px; }
.factor { margin-bottom: 9px; }
.factor-top { display: flex; justify-content: space-between; font-size: 12.5px; }
.factor-val { font-size: 11.5px; margin-top: 2px; }

/* AI 语义判断：跟评价卡放在一起，Δ 是分数的一部分 */
.ai-sem {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid #d9e3ef;
  border-radius: 8px;
  background: #fbfcfe;
}
.ai-mark {
  display: inline-block;
  background: var(--c-primary);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  border-radius: 4px;
  padding: 0 6px;
  letter-spacing: .5px;
}
/* 理由块与 AiAdvice 卡片同一套观感，两个组件摆在一页上不会像两套设计 */
.ai-sem-list { list-style: none; margin: 8px 0 0; padding: 0; }
.ai-sem-list li {
  font-size: 12.5px;
  line-height: 1.7;
  padding: 7px 10px;
  border-radius: 6px;
  border-left: 3px solid var(--c-border);
  background: #f7f9fc;
}
.ai-sem-list li + li { margin-top: 7px; }
.ai-sem-list li.lv-high { background: var(--c-red-soft); border-left-color: var(--c-red); }
.ai-sem-list li.lv-mid { background: var(--c-amber-soft); border-left-color: var(--c-amber); }

.ai-tag {
  display: inline-block;
  font-size: 11px;
  line-height: 17px;
  padding: 0 6px;
  margin-bottom: 3px;
  border-radius: 3px;
  background: rgba(255, 255, 255, .72);
}
.lv-high .ai-tag { color: var(--c-red); }
.lv-mid .ai-tag { color: var(--c-amber); }

.verdict-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  padding: 8px 10px;
  background: #f5f7fa;
  border: 1px solid var(--c-border);
  border-radius: 6px;
  font-size: 12.5px;
}

/* 票据墙：一行铺开，手机上按屏幕宽度自然折行 */
.inv-wall {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.inv-wall__item {
  width: 108px;
  text-align: center;
}

.inv-wall__no {
  margin-top: 5px;
  font-size: 11px;
  color: var(--c-text-weak);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inv-wall__amt {
  font-size: 12.5px;
}
</style>
