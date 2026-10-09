<template>
  <div class="page-wrap">
    <div class="card">
      <div class="card-hd">
        <h3>我的出差申请</h3>
        <div class="hd-tools">
          <el-select v-model="statusFilter" placeholder="状态筛选" clearable size="small" style="width:150px" @change="load">
            <el-option v-for="(v, k) in APPLY_STATUS" :key="k" :label="v.text" :value="k" />
          </el-select>
          <el-button type="primary" size="small" @click="$router.push('/employee/apply')">新建申请</el-button>
        </div>
      </div>
      <div class="card-bd">
        <el-table :data="list" v-loading="loading" border stripe size="small">
          <el-table-column prop="applyNo" label="申请号" width="160" class-name="mono" />
          <el-table-column prop="reason" label="事由" min-width="200" show-overflow-tooltip />
          <el-table-column prop="partnerName" label="关联对象" min-width="180" show-overflow-tooltip />
          <el-table-column prop="estTotal" label="预估合计" width="120" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.estTotal) }}</span></template>
          </el-table-column>
          <el-table-column prop="standardResult" label="差标结论" width="120" align="center">
            <template #default="{ row }">
              <el-tag :type="STANDARD_RESULT[row.standardResult]?.type" size="small" effect="plain">
                {{ STANDARD_RESULT[row.standardResult]?.text }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="130" align="center">
            <template #default="{ row }">
              <el-tag :type="APPLY_STATUS[row.status]?.type" size="small">
                {{ APPLY_STATUS[row.status]?.text }}
              </el-tag>
              <div v-if="row.overdue" class="overdue">已逾期 {{ overdueText(row) }}</div>
              <div v-else-if="row.waitText" class="weak" style="font-size:11.5px">
                {{ row.waitText }}
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="提交时间" width="160" />
          <el-table-column label="操作" width="290" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDetail(row.applyNo)">详情</el-button>
              <el-button
                v-if="row.status === 'DRAFT'"
                link type="warning" size="small"
                @click="goCreate(row.applyNo)"
              >
                继续填写
              </el-button>
              <el-button
                v-else
                link type="warning" size="small"
                :disabled="row.status !== 'REJECTED'"
                @click="goCreate(row.applyNo)"
              >
                修改重提
              </el-button>
              <!--
                后端 InvoiceService 对 APPROVED / CLOSED 都放行：付完款的单子要是发现漏了一张票，
                照样得让人补得进来。这里别只放 APPROVED，否则关了单就贴不了票。
              -->
              <el-button
                link type="success" size="small"
                :disabled="row.status !== 'APPROVED' && row.status !== 'CLOSED'"
                @click="goInvoice(row.applyNo)"
              >
                传票据
              </el-button>
              <!--
                催办只在「球在审批人手上」时出现。草稿、已通过、已驳回都不摆：
                那几类不是审批人在等，摆出来点了只会吃一个「不用催」的拒答。
              -->
              <el-button
                v-if="row.status === 'DEPT_REVIEW' || row.status === 'FINAL_REVIEW'"
                link size="small" type="warning"
                :disabled="reminding === row.applyNo"
                @click="remindOne(row)"
              >
                催办
              </el-button>
              <!-- 结果回填只在出差已成行之后才有意义：审批没过根本没出去，
                   草稿和审批中摆一个点不动的按钮只会让人以为是权限问题 -->
              <el-button
                v-if="canBackfill(row.status)"
                link type="success" size="small"
                @click="openBackfill(row)"
              >
                回填结果
              </el-button>
              <!-- 只有草稿能删。用 v-if 而不是 disabled：非草稿行摆一个永远点不动的灰按钮，
                   反而让人以为是权限问题，操作列也会挤到放不下 -->
              <el-button
                v-if="row.status === 'DRAFT'"
                link type="danger" size="small"
                @click="removeDraft(row)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="row-end" style="margin-top:14px">
          <el-pagination
            background layout="total, prev, pager, next"
            :total="total" :page-size="pageSize" v-model:current-page="pageNum"
            @current-change="load"
          />
        </div>
      </div>
    </div>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawer" :title="`申请详情 ${detail.applyNo || ''}`" size="56%">
      <div v-if="detail.applyNo" v-loading="detailLoading">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="申请人">{{ detail.applicant }}</el-descriptions-item>
          <el-descriptions-item label="所属部门">{{ detail.deptName }}</el-descriptions-item>
          <el-descriptions-item label="关联对象">{{ detail.partnerName }}</el-descriptions-item>
          <el-descriptions-item label="出差事由" :span="2">{{ detail.reason }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.tripType === TRIP_TYPE_OTHER" label="类型补充说明" :span="2">
            {{ detail.tripTypeNote || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="预估交通"><span class="amount">¥ {{ money(detail.estTraffic) }}</span></el-descriptions-item>
          <el-descriptions-item label="预估住宿"><span class="amount">¥ {{ money(detail.estHotel) }}</span></el-descriptions-item>
          <el-descriptions-item label="预估合计"><span class="amount">¥ {{ money(detail.estTotal) }}</span></el-descriptions-item>
          <el-descriptions-item label="差标结论">
            <el-tag :type="STANDARD_RESULT[detail.standardResult]?.type" size="small">
              {{ STANDARD_RESULT[detail.standardResult]?.text }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="当前状态">
            <el-tag :type="APPLY_STATUS[detail.status]?.type" size="small">
              {{ APPLY_STATUS[detail.status]?.text }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div class="section-title mt-16">行程段</div>
        <el-table :data="detail.itineraries" border size="small">
          <el-table-column type="index" label="段" width="50" />
          <el-table-column prop="startDate" label="出发" width="110" />
          <el-table-column prop="endDate" label="返回" width="110" />
          <el-table-column label="行程" min-width="140">
            <template #default="{ row }">{{ row.fromCity }} → {{ row.toCity }}</template>
          </el-table-column>
          <el-table-column prop="trafficType" label="交通方式" width="100" />
          <el-table-column prop="cityLevel" label="城市等级" width="90" />
          <el-table-column prop="invoiceCnt" label="已挂票据" width="90" align="center" />
        </el-table>

        <!--
          票据按缩略图铺墙，不摆成一张表：报销时人对的是票面，不是票号。
          三四个字段挤在一行里，眼睛会先落在字上，图反而成了装饰。
        -->
        <div class="section-title mt-16">已挂接票据（{{ detail.invoices?.length || 0 }}）</div>
        <div v-if="detail.invoices?.length" class="inv-grid">
          <div v-for="row in detail.invoices" :key="row.id" class="inv-card">
            <InvoiceImage
              :img-path="row.imgPath" :img-url="row.imgUrl" :thumb-url="row.thumbUrl"
              :invoice-no="row.invoiceNo" :id="row.id" :size="84"
            />
            <div class="inv-card__no mono">{{ row.invoiceNo || '—' }}</div>
            <div class="inv-card__line">{{ row.seller || '—' }}</div>
            <div class="inv-card__line weak">{{ row.invoiceDate || '—' }}</div>
            <div class="inv-card__line amount">¥ {{ money(row.amount) }}</div>
          </div>
        </div>
        <!--
          空态要说清楚「为什么没有」，不是干摆一张空表。
          审批还没过的单本来就传不了票，说一句比让人猜是不是系统没查到强。
        -->
        <el-empty
          v-else
          :description="invoiceEmptyText"
          :image-size="60"
        />

        <div class="section-title mt-16">审批流转</div>
        <FlowTimeline
          :records="detail.records || []"
          :status="detail.status"
          :empty-text="detail.status === 'DRAFT' ? '这张还是草稿，提交之后才会进审批流转。' : '暂无流转记录。'"
        />

        <!-- 关了不是没了：只读归档，但补票、回填照样能进，免得以为点不动是权限问题 -->
        <div v-if="detail.status === 'CLOSED'" class="drawer-actions">
          <span class="muted" style="font-size:12.5px">
            这趟差已结案（报销已付款），单据归档只读。漏贴的票和业务结果仍然可以补，补进来照常计入统计。
          </span>
        </div>

        <!--
          手工关闭的入口。它补的是这样一个场景：申请批了，但因为取消行程、对方改期等原因
          没成行，也就没有任何费用要报销——这张单永远走不到「报销付完款」那条自动结案路径，
          只能一直停在 APPROVED。让人手动给它一个终点。
        -->
        <template v-if="detail.status === 'APPROVED'">
          <div class="section-title mt-16">结束这趟差</div>
          <div class="close-box">
            <div class="close-text">
              出差没成行、也没有费用要报销时，可以把这单关掉归档。
              关了不会删除数据，历史和统计都还在，只是不再占着待办。
            </div>
            <div class="drawer-actions">
              <el-button size="small" plain :loading="closing" @click="closeApplyOne">关闭此单</el-button>
            </div>
          </div>
        </template>

        <template v-if="canBackfill(detail.status)">
          <div class="section-title mt-16">业务结果（{{ detail.results?.length || 0 }}）</div>
          <el-table v-if="detail.results?.length" :data="detail.results" border size="small">
            <el-table-column prop="resultType" label="结果类型" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="RESULT_TYPE[row.resultType]?.type" size="small" effect="plain">
                  {{ RESULT_TYPE[row.resultType]?.text || row.resultType }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="resultDesc" label="说明" min-width="200" show-overflow-tooltip />
            <el-table-column prop="outputAmount" label="折算产出" width="120" align="right">
              <template #default="{ row }"><span class="amount">¥ {{ money(row.outputAmount) }}</span></template>
            </el-table-column>
            <el-table-column prop="filledBy" label="回填人" width="100" />
            <el-table-column prop="filledAt" label="回填时间" width="160" />
          </el-table>
          <el-empty
            v-else
            description="还没回填这趟差旅的结果。回来之后把「办成了什么」填上，它是费效比月报里产出那一半的数据来源。"
            :image-size="60"
          />
          <div class="drawer-actions">
            <el-button type="primary" @click="openBackfill(detail)">回填结果</el-button>
            <span class="muted" style="font-size:12.5px">
              一趟差可以回填多条结果，比如先交货、后验收，分开记。
            </span>
          </div>
        </template>

        <!-- 草稿在抽屉里就能接着处理，不用关掉抽屉再回列表找操作列 -->
        <div v-if="detail.status === 'DRAFT'" class="drawer-actions">
          <el-button type="warning" @click="goCreate(detail.applyNo)">继续填写</el-button>
          <el-button type="danger" plain @click="removeDraft(detail)">删除草稿</el-button>
          <span class="muted" style="font-size:12.5px">
            草稿没进审批，改完要回填写页点「提交审批」才送得出去。
          </span>
        </div>
      </div>
    </el-drawer>

    <!-- 结果回填弹窗 -->
    <el-dialog v-model="bfVisible" title="回填业务结果" width="520px" :close-on-click-modal="false">
      <el-alert
        type="info" :closable="false" show-icon
        :title="`申请单 ${bfForm.applyNo || ''}：这趟差带回了什么结果？`"
        description="填了之后会进费效比月报的产出口径，也会作为下次出差事前评价的「历史产出」依据。"
        style="margin-bottom:14px"
      />
      <el-form ref="bfRef" :model="bfForm" :rules="bfRules" label-width="100px">
        <el-form-item label="结果类型" prop="resultType">
          <el-select v-model="bfForm.resultType" placeholder="这趟的结果算哪一类" style="width:100%">
            <el-option v-for="(v, k) in RESULT_TYPE" :key="k" :label="v.text" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果说明" prop="resultDesc">
          <el-input
            v-model="bfForm.resultDesc" type="textarea" :rows="3" maxlength="200" show-word-limit
            placeholder="比如：签下年度框架协议，首批订单 300 台"
          />
        </el-form-item>
        <el-form-item label="折算产出" prop="outputAmount">
          <el-input-number
            v-model="bfForm.outputAmount" :min="0" :precision="2" :step="1000"
            :disabled="bfForm.resultType === '无进展'" style="width:100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bfVisible = false">取消</el-button>
        <el-button type="primary" :loading="bfSaving" @click="submitBackfill">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onActivated, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { myApplyPage, applyDetail, deleteApply, backfillResult, closeApply, remindApply } from '@/api'
import { APPLY_STATUS, STANDARD_RESULT, RESULT_TYPE, TRIP_TYPE_OTHER, money } from '@/utils/dict'
import FlowTimeline from '@/components/FlowTimeline.vue'
import InvoiceImage from '@/components/InvoiceImage.vue'

const router = useRouter()
const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const statusFilter = ref('')

const drawer = ref(false)
const detailLoading = ref(false)
const closing = ref(false)
const detail = reactive({})

async function load () {
  loading.value = true
  try {
    const data = await myApplyPage({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      status: statusFilter.value
    })
    list.value = data.list
    total.value = data.total
    fillWait(list.value)
  } finally {
    loading.value = false
  }
}

function overdueText (row) {
  const h = row.overdueHours || 0
  return h < 24 ? `${h} 小时` : `${Math.floor(h / 24)} 天`
}

/**
 * 补「在这一步待了多久」。
 *
 * 只在两个在审状态上算：草稿是自己的事、驳回之后球在自己手上，
 * 这两种摆一个「已等待 3 天」会让人以为审批人在拖。
 */
function fillWait (rows) {
  const now = Date.now()
  for (const r of rows) {
    r.waitText = ''
    if (!r.statusAt || r.overdue) {
      continue
    }
    if (r.status !== 'DEPT_REVIEW' && r.status !== 'FINAL_REVIEW') {
      continue
    }
    const t = new Date(String(r.statusAt).replace(/-/g, '/')).getTime()
    if (!t) {
      continue
    }
    const h = Math.floor((now - t) / 3600000)
    r.waitText = h < 1 ? '刚刚提交' : h < 24 ? `已等待 ${h} 小时` : `已等待 ${Math.floor(h / 24)} 天`
  }
}

/**
 * 催一次。
 *
 * <p>能不能催由后端说了算（本人、在审、24 小时内没催过），
 * 前端不抄这套规则——抄了就要跟着后端一起改，迟早对不上。
 * 被拒时后端 msg 自带原因，照着弹出来就行。
 */
const reminding = ref('')
async function remindOne (row) {
  reminding.value = row.applyNo
  try {
    await remindApply(row.applyNo)
    ElMessage.success('已提醒审批人，24 小时内不必重复催')
    await load()
  } finally {
    reminding.value = ''
  }
}

async function openDetail (applyNo) {
  drawer.value = true
  detailLoading.value = true
  Object.keys(detail).forEach((k) => delete detail[k])
  try {
    Object.assign(detail, await applyDetail(applyNo))
  } finally {
    detailLoading.value = false
  }
}

function goInvoice (applyNo) {
  router.push({ path: '/employee/invoice', query: { applyNo } })
}

/**
 * 结果回填只对「已经出去过」的单开放：审批通过或已关闭。
 * 后端 ApplyService.backfill 也是这个口径，两边保持一致，省得前端放行了后端还是报 2004。
 */
function canBackfill (status) {
  return status === 'APPROVED' || status === 'CLOSED'
}

/**
 * 票据空态的说明。
 *
 * 「一张票都没有」有两种完全不同的含义，摆同一句空表会让人自己猜：
 * 审批还没走完时是**本来就传不了**，这时候得告诉他什么时候能传；
 * 已经批了却没票，才是真的该去传了。
 */
const invoiceEmptyText = computed(() => {
  switch (detail.status) {
    case 'DRAFT':
      return '这张还是草稿，提交并通过审批之后才能传票据。'
    case 'DEPT_REVIEW':
    case 'FINAL_REVIEW':
      return '审批通过之后才能传票据。到时候在这里能直接看到票面影像。'
    case 'REJECTED':
      return '这张被驳回了。改完重提、通过审批之后才能传票据。'
    case 'APPROVED':
    case 'CLOSED':
      return '还没传票据。点列表里的「传票据」上传，传完这里就能看到影像。'
    default:
      return '暂无票据。'
  }
})

const bfVisible = ref(false)
const bfSaving = ref(false)
const bfRef = ref()
const bfForm = reactive({
  applyNo: '',
  resultType: '',
  resultDesc: '',
  outputAmount: 0
})

// 无进展必须归零：后端 outputAmount 是 @NotNull，空着传 null 会被拦在 1001
const bfRules = {
  resultType: [{ required: true, message: '请选择结果类型', trigger: 'change' }],
  outputAmount: [{ required: true, message: '请填写折算产出金额', trigger: 'blur' }]
}

function openBackfill (row) {
  bfForm.applyNo = row.applyNo
  bfForm.resultType = ''
  bfForm.resultDesc = ''
  bfForm.outputAmount = 0
  bfVisible.value = true
}

async function submitBackfill () {
  try {
    await bfRef.value.validate()
  } catch {
    return
  }
  bfSaving.value = true
  try {
    await backfillResult(bfForm.applyNo, {
      resultType: bfForm.resultType,
      resultDesc: bfForm.resultDesc,
      outputAmount: bfForm.resultType === '无进展' ? 0 : bfForm.outputAmount
    })
    ElMessage.success('业务结果已回填')
    bfVisible.value = false
    // 抽屉开着就把详情重新拉一次，让新填的那条立刻出现在结果表里
    if (drawer.value && detail.applyNo === bfForm.applyNo) {
      Object.assign(detail, await applyDetail(bfForm.applyNo))
    }
    await load()
  } finally {
    bfSaving.value = false
  }
}

/**
 * 手工关闭这张单。
 *
 * <p>能不能关由后端说了算：只有本人、只有 APPROVED 状态、名下不能有在途报销单，
 * 前端不把这套判断抄一遍——这里查不到报销单，重复实现早晚会跟后端对不上。
 * 后端拒的时候 msg 里直接带了原因（比如「还有 1 张报销单没走完」），照着弹就行。
 */
async function closeApplyOne () {
  const no = detail.applyNo
  if (!no) return
  try {
    await ElMessageBox.confirm(
      '关掉之后这单归档，不再占着待办。票据和业务结果照样能补，历史统计不受影响。',
      '关掉这趟差？',
      { type: 'warning', confirmButtonText: '关闭此单', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  closing.value = true
  try {
    await closeApply(no)
    ElMessage.success('已关闭归档')
    await load()
  } finally {
    closing.value = false
  }
}

/**
 * 带申请号进填写页。草稿续写会沿用同一个号，驳回单则只借用内容、提交后换新号，
 * 具体由填写页按状态判断。
 */
function goCreate (applyNo) {
  router.push({ path: '/employee/apply', query: { applyNo } })
}

/**
 * 删除草稿。列表操作列和详情抽屉共用这一个入口。
 * 从抽屉里发起时删完顺手关掉抽屉——留着的话抽屉里摆的是一张已经不存在的单。
 */
async function removeDraft (row) {
  const fromDrawer = drawer.value && detail.applyNo === row.applyNo
  try {
    await ElMessageBox.confirm(
      `确认删除草稿 ${row.applyNo}？删掉之后就不能再找回。`,
      '删除草稿',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '留着' }
    )
  } catch {
    return
  }
  await deleteApply(row.applyNo)
  ElMessage.success('草稿已删除')
  if (fromDrawer) drawer.value = false
  if (list.value.length === 1 && pageNum.value > 1) pageNum.value--
  await load()
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'EmpApplyList' })

/**
 * 每次回到这页都重新拉数据——递交成功的申请、别处的审批结果都要看得见。
 * 用 onActivated 而不是 onMounted：组件被 keep-alive 缓存住，切走不再 mounted，
 * 光靠 mounted 的话回到列表看到的是上一次进来的旧数据。
 * 筛选条件和页码是组件自己的状态，跟着一起留着，不会被这次刷新带跑。
 * （首次进入时 activated 也会触发，所以不必再留一个 onMounted）
 */
onActivated(load)
</script>

<style scoped>
.close-box {
  padding: 10px 12px;
  border: 1px dashed var(--c-border);
  border-radius: 6px;
  background: #fafbfc;
}
.close-text { font-size: 12.5px; line-height: 1.7; color: var(--c-text-sub); }
.close-box .drawer-actions { margin-top: 10px; padding-top: 0; border-top: none; }

.hd-tools { display: flex; gap: 10px; align-items: center; }

/* 票据墙：抽屉宽度有限，卡片按 124px 自适应排，窄屏自动少排几列 */
.inv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(124px, 1fr));
  gap: 12px;
}

.inv-card {
  padding: 9px 8px;
  text-align: center;
  background: #fbfcfe;
  border: 1px solid #e8edf4;
  border-radius: 8px;
}

.inv-card :deep(.inv-img) {
  margin: 0 auto;
}

.inv-card__no {
  margin-top: 7px;
  font-size: 11.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inv-card__line {
  margin-top: 3px;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.drawer-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid #ebeef5;
}
.overdue {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--c-red);
}
</style>
