<template>
  <div class="page-wrap">
    <!-- key 换掉就会整块重建：删完草稿用它把旧的输入框实例、日期选择器的残留值全部丢掉 -->
    <el-row :gutter="16" :key="formKey">
      <!-- 左：申请表单 -->
      <el-col :span="16" :xs="24">
        <div class="card">
          <div class="card-hd">
            <h3>
              出差申请填写
              <el-tag v-if="mode === 'draft'" size="small" type="info" effect="plain" class="ml-8">
                续写草稿 · {{ currentApplyNo }}
              </el-tag>
              <el-tag v-else-if="mode === 'resubmit'" size="small" type="warning" effect="plain" class="ml-8">
                驳回修改 · 原单 {{ originApplyNo }}
              </el-tag>
            </h3>
            <div class="hd-tools">
              <!-- 草稿箱：选一张就切过去继续填，清空选择等于回到一张全新的空白申请 -->
              <el-select
                v-model="pickedDraft"
                clearable filterable size="small"
                placeholder="草稿箱 · 切换或继续填写"
                style="width:260px"
                @change="onPickDraft"
              >
                <el-option
                  v-for="d in drafts" :key="d.applyNo"
                  :label="`${d.applyNo} · ${d.reason || '(未填事由)'}`"
                  :value="d.applyNo"
                />
              </el-select>
              <el-button v-if="mode === 'draft'" link type="danger" size="small" :loading="deleting" @click="removeDraft">
                删除草稿
              </el-button>
              <!-- 草稿不校验必填：事由没写、行程只填一半都能存，提交那一步才要求填全 -->
              <el-tooltip content="草稿可以先空着存，提交审批时才要求填全" placement="bottom" :show-after="300">
                <el-button :loading="drafting" @click="saveDraft">
                  {{ mode === 'draft' ? '保存修改' : '存草稿' }}
                </el-button>
              </el-tooltip>
              <el-button type="primary" :loading="submitting" @click="submit">提交审批</el-button>
            </div>
          </div>
          <div class="card-bd">
            <el-form :model="form" :rules="rules" ref="formRef" label-width="96px">
              <el-form-item label="出差事由" prop="reason">
                <el-input
                  v-model="form.reason"
                  type="textarea"
                  :rows="2"
                  maxlength="200"
                  show-word-limit
                  placeholder="写明去做什么、预期产出是什么，写得越具体评价得分越高"
                  @blur="previewEval"
                />
              </el-form-item>

              <el-col :span="24">
                <el-row :gutter="12">
                  <el-col :span="12" :xs="24">
                    <el-form-item label="关联对象" prop="partnerId">
                      <el-select v-model="form.partnerId" placeholder="选择往来对象" style="width:100%" @change="onPartnerChange">
                        <el-option v-for="p in partners" :key="p.id" :label="p.name" :value="p.id" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                  <el-col :span="12" :xs="24">
                    <el-form-item label="出差类型" prop="tripType">
                      <el-select v-model="form.tripType" placeholder="选择出差类型" style="width:100%" @change="onTripTypeChange">
                        <el-option v-for="t in TRIP_TYPES" :key="t" :label="t" :value="t" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                </el-row>
              </el-col>

              <!--
                只有选「其他」才出现。前五个类型审批人一看就知道这趟是干什么的，
                「其他」两个字本身不带信息，所以给个框让人补一句。
                不设必填：真有归不了类的事由（内部借调、临时支援），
                逼着写只会换来「其他事项」这种等于没写的字。
              -->
              <el-form-item v-if="form.tripType === TRIP_TYPE_OTHER" label="类型补充说明" prop="tripTypeNote">
                <el-input
                  v-model="form.tripTypeNote"
                  type="textarea"
                  :rows="2"
                  maxlength="200"
                  show-word-limit
                  placeholder="简要说明这趟属于什么性质的出差，便于审批人判断与后续归类"
                />
              </el-form-item>

              <el-row :gutter="12">
                <el-col :span="12" :xs="24">
                  <el-form-item label="预估交通费" prop="estTraffic">
                    <el-input-number v-model="form.estTraffic" :min="0" :precision="2" :step="100" style="width:100%" @change="recalc" />
                  </el-form-item>
                </el-col>
                <el-col :span="12" :xs="24">
                  <el-form-item label="预估住宿费" prop="estHotel">
                    <el-input-number v-model="form.estHotel" :min="0" :precision="2" :step="100" style="width:100%" @change="recalc" />
                  </el-form-item>
                </el-col>
              </el-row>

              <el-form-item label="预估合计">
                <!-- 区间不再挂在这儿：右栏参考表里已经有同类区间和偏差，同一个数写两遍反而让人对着看 -->
                <div class="total-line">
                  <span class="amount">¥ {{ money(form.estTraffic + form.estHotel) }}</span>
                </div>
              </el-form-item>
            </el-form>

            <!-- 行程段 -->
            <div class="section-title" style="margin-top:20px">行程段</div>
            <el-table :data="form.itineraries" border size="small">
              <el-table-column type="index" label="段" width="50" />
              <el-table-column label="出发日期" width="150">
                <template #default="{ row }">
                  <el-date-picker v-model="row.startDate" type="date" value-format="YYYY-MM-DD" size="small" style="width:100%" />
                </template>
              </el-table-column>
              <el-table-column label="结束日期" width="150">
                <template #default="{ row }">
                  <el-date-picker v-model="row.endDate" type="date" value-format="YYYY-MM-DD" size="small" style="width:100%" />
                </template>
              </el-table-column>
              <el-table-column label="出发地" width="110">
                <template #default="{ row }">
                  <el-input v-model="row.fromCity" size="small" />
                </template>
              </el-table-column>
              <el-table-column label="目的地" width="110">
                <template #default="{ row }">
                  <el-input v-model="row.toCity" size="small" />
                </template>
              </el-table-column>
              <el-table-column label="交通方式" width="120">
                <template #default="{ row }">
                  <el-select v-model="row.trafficType" size="small" style="width:100%">
                    <el-option v-for="t in TRAFFIC_TYPES" :key="t" :label="t" :value="t" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="城市等级" width="110">
                <template #default="{ row }">
                  <el-select v-model="row.cityLevel" size="small" style="width:100%">
                    <el-option v-for="c in CITY_LEVELS" :key="c" :label="c" :value="c" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80" fixed="right">
                <template #default="{ $index }">
                  <el-button link type="danger" size="small" @click="removeRow($index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="row-end" style="margin-top:10px">
              <el-button size="small" @click="addRow">+ 添加行程段</el-button>
            </div>
          </div>
        </div>
      </el-col>

      <!-- 右：参考与提示 -->
      <el-col :span="8" :xs="24">
        <!--
          AI 填写建议的入口。整块内容搬进右侧抽屉了：
          右栏本来就叠着参考卡、评价卡、超标说明，再塞一份带勾选的清单要滚很久才看得到，
          而且清单要边看边挑，占一整列才够宽。这里只留一条入口，点开是抽屉。
        -->
        <div class="ai-entry" v-if="aiEnabled" @click="aiDrawer = true">
          <div class="ai-entry-main">
            <span class="ai-mark">AI</span>
            <span class="ai-entry-title">AI 填写建议</span>
            <el-tag v-if="ai.analyzing" size="small" type="info" effect="plain">分析中</el-tag>
            <el-tag v-else-if="pendingCount" size="small" type="warning" effect="plain">
              {{ pendingCount }} 项待填
            </el-tag>
            <el-tag v-else-if="ai.fields.length" size="small" type="success" effect="plain">
              已全部填充
            </el-tag>
            <el-tag v-else size="small" effect="plain">未分析</el-tag>
          </div>
          <div class="ai-entry-sub">{{ aiEntryHint }}</div>
          <span class="ai-entry-arrow">›</span>
        </div>

        <!--
          右栏两块都做成折叠：这页的主活是左边那张表单，参考和提示属于「要看再点开」，
          常驻摊开会把提交按钮顶出屏幕。默认参考展开（它跟着输入实时刷，收着等于白刷），
          提示收起——它要等事由、对象、类型、行程都填完才有内容，之前摊着就是一块空卡。
          收起时标题右边挂关键数，不点开也知道有没有数、量级多少。
        -->
        <el-collapse v-model="openPanels" class="side-collapse">
          <el-collapse-item name="ref">
            <template #title>
              <div class="side-title">
                <span class="side-title-main">同类花费参考</span>
                <span class="weak side-title-sub">{{ refSummary }}</span>
              </div>
            </template>

            <!-- 同类花费参考：填一项维度就重查一次，不用填齐。 -->
            <div v-if="costRef.amount && costRef.amount.lowSample" class="tip-box tip-warn" style="margin-bottom:8px">
              样本不足 5 单，只够看个大概
            </div>
            <!--
              表格只在「还没条件可查」和「查到了」两种情况下摆出来：
              填了条件却查不到时再摆一张全空的表格，等于让员工自己猜是没数据还是坏了，
              那种情况交给下面那句「近 12 个月无相关单据」说。
            -->
            <table v-if="refTableVisible" class="ref-table">
              <thead>
                <tr>
                  <th>项目</th><th>同类平均</th><th>同类区间</th><th>本次</th><th>与同类</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in refRows" :key="r.key">
                  <td class="ref-col-name">{{ r.label }}</td>
                  <td class="mono">{{ r.avg }}</td>
                  <td class="mono">{{ r.range }}</td>
                  <td class="mono">{{ r.mine }}</td>
                  <td class="mono">{{ r.dev }}</td>
                </tr>
              </tbody>
            </table>
            <div v-if="!costRefQueryable" class="ref-foot weak">
              填了目的地、出差类型、城市等级或交通方式其中任意一项，这里就出同类参考
            </div>
            <div v-else-if="!costRef.amount && !costRefLoading" class="ref-foot weak">
              近 12 个月无相关单据
            </div>
            <template v-else-if="costRef.amount">
              <div class="ref-foot weak">
                {{ refScopeText }}<span v-if="costRef.amount.relaxed"> · 你填的条件凑不出样本，这是放宽后的口径</span>
                <span v-if="costRef.amount.sameDayCnt"> · {{ costRef.amount.sameDayCnt }} 单当天往返</span>
              </div>
              <div class="ref-foot weak">
                区间为 P25 ~ P75 · 合计区间按整单算，不是两项相加
                <template v-if="costRef.amount.sameDayCnt">
                  · 住宿只算住过的单，{{ costRef.amount.sameDayCnt }} 单当天往返没算进去，
                  所以两项的均值加起来与合计不等
                </template>
              </div>
            </template>
          </el-collapse-item>

          <el-collapse-item name="hint">
            <template #title>
              <div class="side-title">
                <span class="side-title-main">提示</span>
                <el-tag
                  v-if="previewReady && preview.level"
                  :type="EVAL_LEVEL[preview.level]?.type"
                  effect="dark" size="small" class="side-title-tag"
                >
                  {{ EVAL_LEVEL[preview.level]?.text }} {{ preview.totalScore }} 分
                </el-tag>
                <span v-else-if="previewReady" class="weak side-title-sub">预判中…</span>
                <span v-else class="weak side-title-sub">事由、对象、类型与行程填完后出结论</span>
              </div>
            </template>

            <template v-if="previewReady">
              <div v-if="preview.level" class="eval-head">
                <el-tag :type="EVAL_LEVEL[preview.level]?.type" effect="dark" size="large">
                  {{ EVAL_LEVEL[preview.level]?.text }}
                </el-tag>
                <div class="eval-score">
                  <span class="amount">{{ preview.totalScore }}</span>
                  <span class="weak">/ 100</span>
                </div>
              </div>
              <ul v-if="preview.hints?.length" class="hint-list">
                <li v-for="(h, i) in preview.hints" :key="i">{{ h }}</li>
              </ul>
              <div v-else class="tip-box tip-info">正在预演本次申请的评价结论…</div>

              <!-- AI 写法解读：只给 Δ 和理由，不接管分数和档位 -->
              <div v-if="ai.sem" class="ai-sem">
                <ul v-if="ai.sem.reasons?.length" class="ai-sem-list">
                  <li v-for="(r, i) in ai.sem.reasons" :key="i" :class="'lv-' + (r.level || 'low')">
                    <div v-if="r.level === 'high' || r.level === 'mid'" class="ai-tag">
                      {{ r.level === 'high' ? '要紧' : '留意' }}
                    </div>
                    <div>{{ r.point }}</div>
                  </li>
                </ul>
                <div v-else class="weak" style="font-size:12px">没有额外提醒。</div>
              </div>
            </template>
            <div v-else class="tip-box tip-info">
              事由、关联对象、出差类型和行程段都填完，这里出合理性提示。
            </div>
          </el-collapse-item>
        </el-collapse>

        <!--
          AI 填写建议：整块搬进右侧抽屉，这里只留入口条（见右栏顶部）。
          分两半走：费用解读和语义判断是实时的，跟着表单走；
          可填充清单和事由改写必须点「AI 分析」——那是要调模型的东西，
          人还在打字就一直生成，既费调用又容易把人刚写的话盖掉。
        -->
        <el-drawer
          v-model="aiDrawer"
          direction="rtl"
          size="440px"
          class="ai-drawer"
        >
          <template #header>
            <div class="ai-drawer-hd">
              <span class="ai-mark">AI</span>
              <span class="ai-drawer-title">AI 填写建议</span>
              <el-tag v-if="aiLocalNow" size="small" type="warning" effect="plain">本地演示</el-tag>
              <el-tag v-else size="small" type="success" effect="plain">模型输出</el-tag>
              <el-tag size="small" effect="plain">依据来自库内数据</el-tag>
            </div>
          </template>

          <AiAdvice
            title="这版还缺什么"
            :points="aiPoints"
            :loading="ai.analyzing"
            :local="aiLocalNow"
            empty-text="点下面的「AI 分析」，AI 会先看这版内容缺什么、能补什么"
          >
            <template #body>
              <div class="ai-drawer-sec">
                <!--
                  库里一条能对标的数据都没有时，先把话说完整。
                  不写这句的话，通用提示和有依据的建议在页面上看不出区别，
                  员工会以为「 AI 说了可以这么填」，实际上系统什么都没查到。
                -->
                <div v-if="ai.advice?.sourceNote" class="ai-note">
                  {{ ai.advice.sourceNote }}
                </div>
                <div class="ai-sec-title">可以一键填充的项</div>
                <AiFieldList
                  v-model="picked"
                  :fields="ai.fields"
                  :stale="analyzeStale"
                  :filled="ai.filled"
                />
              </div>
            </template>
          </AiAdvice>

          <template #footer>
            <div class="ai-drawer-ft">
              <el-button
                size="small" type="primary" plain
                :disabled="!checkedCount"
                @click="applyFields"
              >
                填充选中（{{ checkedCount }}）
              </el-button>
              <el-button
                size="small"
                :type="ai.fields.length ? 'default' : 'primary'"
                :loading="ai.analyzing"
                @click="analyze"
              >
                {{ ai.fields.length ? '重新分析' : 'AI 分析' }}
              </el-button>
              <el-button v-if="canUndo" size="small" @click="undoFill">撤销上次填充</el-button>
            </div>
          </template>
        </el-drawer>

        <!-- 差标超限说明 -->
        <div class="card mt-16" v-if="needExceedReason">
          <div class="card-hd"><h3>超标说明</h3><el-tag type="danger" size="small">必填</el-tag></div>
          <div class="card-bd">
            <DraftTextarea
              v-model="form.exceedReason"
              biz-type="APPLY_SUBMIT"
              :biz-no="exceedBizNo"
              field-name="exceedReason"
              placeholder="差标超限必须说明原因，例如：客户指定酒店，周边无更低档可选"
            />
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  costRef as apiCostRef, submitApply, evalPreview, partnerOptions,
  saveDraft as apiSaveDraft, applyDetail, updateApply, deleteApply, myApplyPage,
  deleteFieldDraft
} from '@/api'
import { CITY_LEVELS, TRAFFIC_TYPES, TRIP_TYPES, TRIP_TYPE_OTHER, EVAL_LEVEL, money } from '@/utils/dict'
import {
  aiEnabled, aiLocal, aiDraftAdvice, aiCostExplain, aiSemanticEval, aiAdviceAdopt
} from '@/api/ai'
import AiAdvice from '@/components/AiAdvice.vue'
import AiFieldList from '@/components/AiFieldList.vue'
import DraftTextarea from '@/components/DraftTextarea.vue'
import { useUserStore } from '@/stores/user'

// keep-alive 是按组件名匹配的，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'EmpApplyCreate' })

const route = useRoute()
const router = useRouter()
const user = useUserStore()

const formRef = ref()
const submitting = ref(false)
const drafting = ref(false)
const deleting = ref(false)
const costRef = reactive({ amount: null })
const costRefLoading = ref(false)
/** 右栏折叠扳手：参考默认展开（趁手地边填边看），提示默认收起（没内容时收着不占位） */
const openPanels = ref(['ref'])
const preview = reactive({ level: '', totalScore: 0, hints: [] })
const standardHints = ref([])
// 只用来给 <el-row> 当 key，++ 一次等于把整个填写区推倒重建
const formKey = ref(0)

/**
 * 页面三种身份：
 * - new       正常新建，save/submit 都不带 applyNo，后端各生成一张
 * - draft     从草稿进来（列表页「继续填写」或存完草稿后停留在本页），申请号锁死不变
 * - resubmit  驳回后修改，走 A-05，后端生成新申请号，老单留档
 */
const mode = ref('new')
const currentApplyNo = ref('')
const originApplyNo = ref('')

/**
 * 超标说明的草稿挂在哪个键上。
 *
 * 续写草稿、驳回重提时都有申请号，直接挂上去。新建时还没有号，
 * 用「NEW + 本人 id」占一个固定槽位——一个人同时只可能有一张新建中的单，
 * 一个槽位就够，也不会像随机键那样在库里越积越多。
 */
const exceedBizNo = computed(() => currentApplyNo.value || ('NEW' + (user.info?.userId || 0)))

// 草稿箱下拉的数据和下划线选中的那张
const drafts = ref([])
const pickedDraft = ref('')

/**
 * 表单快照，用来判「有没有改过」。
 * 每次回填 / 保存之后记一份新的基线，之后拿当前表单跟它比，
 * 不同就说明有内容还没落到库里，离开时要问一句。
 */
const baseline = ref('')
function snapshot () {
  return JSON.stringify(form)
}
const dirty = computed(() => baseline.value !== snapshot())
/** 把当前内容记为「已保存」状态，下次判定从这里开始算 */
function markSaved () {
  baseline.value = snapshot()
}

/*
 * 关联对象下拉：直接读 partner 表，不再写死在前端。
 * 写死那版的问题不是「少几家」，是新增与改名都对不上——
 * 系统管理员在档案里改了个名字，员工这边还挂着旧名，选出来的 id 落到审批页就是另一个对象。
 */
const serverPartners = ref([])
const partners = ref([])

async function loadPartners () {
  try {
    const d = await partnerOptions()
    const list = Array.isArray(d) ? d : (d?.list || [])
    serverPartners.value = list.map((p) => ({
      id: p.id,
      name: p.partnerName,
      partnerType: p.partnerType
    }))
    partners.value = [...serverPartners.value]
  } catch {
    // 拉不到就留空下拉，别回退到一份假名单：宁可让人看到「选不出来」去查接口，
    // 也不能让他选到一个库里不存在的对象，还一路走到提交
    serverPartners.value = []
    partners.value = []
  }
}

/**
 * 造一行空白行程段。不带任何默认值：出发地、目的地、日期、交通方式都要自己选，
 * 右侧的参考和评价是跟着这些字段走的，预填会让它们一开始就算出一个不是用户想要的结果。
 */
function makeRow (seq) {
  return {
    seq,
    startDate: '',
    endDate: '',
    fromCity: '',
    toCity: '',
    trafficType: '',
    cityLevel: ''
  }
}

/** 一整张空表单。写成工厂函数而不是常量对象——常量对象被 reactive 改过就回不到初始值了。 */
function emptyForm () {
  return {
    reason: '',
    tripType: '',
    tripTypeNote: '',
    partnerId: null,
    estTraffic: null,
    estHotel: null,
    exceedReason: '',
    itineraries: [makeRow(1)]
  }
}

const form = reactive(emptyForm())

const rules = {
  reason: [{ required: true, message: '请填写出差事由', trigger: 'blur' }],
  partnerId: [{ required: true, message: '请选择关联对象', trigger: 'change' }],
  tripType: [{ required: true, message: '请选择出差类型', trigger: 'change' }]
}

// 右侧两张卡片的数据来源条件。没填够就不渲染，免得一进页面先摆一堆默认值出来的结果
// 主目的地：取第一条填了目的地的行程段，参考花费就是按它对标的
const primaryDest = computed(() => form.itineraries.find((i) => i.toCity)?.toCity || '')

// 行程每一段都填完整了才算「行程就绪」，评价要用到完整行程。
// 城市等级不算在内——后端 ItineraryItem 里它不是必填，会按目的地查配置自动补，
// 要求它反而让评分一直出不来
const itineraryReady = computed(
  () => form.itineraries.length > 0 && form.itineraries.every(
    (i) => i.fromCity && i.toCity && i.startDate && i.endDate && i.trafficType
  )
)

/**
 * 同类花费参考的可查条件：四个维度（目的地 / 出差类型 / 城市等级 / 交通方式）
 * 只要填了任意一个就发请求，不用等填齐——填一项就重算一次，缺的维度后端自己放宽。
 * 一个都没填时连请求都不发，表格摆四行「待填」把结构先亮出来。
 */
const costRefQueryable = computed(() => {
  const seg = form.itineraries[0]
  return !!primaryDest.value || !!form.tripType
    || !!(seg && (seg.cityLevel || seg.trafficType))
})

/** 整趟天数：跨段按首尾跨度算，与后端 wholeTripDays 同口径，不是各段相加 */
const tripDays = computed(() => {
  const dates = form.itineraries.flatMap((i) => [i.startDate, i.endDate]).filter(Boolean).sort()
  if (dates.length < 2) return null
  const d = (new Date(dates[dates.length - 1]) - new Date(dates[0])) / 86400000 + 1
  return Number.isFinite(d) && d > 0 ? Math.round(d) : null
})
const previewReady = computed(() => !!form.reason && !!form.partnerId && !!form.tripType && itineraryReady.value)

const needExceedReason = computed(() => standardHints.value.some((h) => h.result === 'EXCEED'))

/* ---------- AI 建议 ----------
 * 页面只认 api/ai.js 的返回结构，不关心背后是后端模型还是本地规则。
 * 分两半走：
 * - cost / sem 是实时的，跟着表单走，不改写人填的内容，也不额外调模型；
 * - fields（可填充清单）必须点「AI 分析」才出，理由见 analyze()。
 */
const ai = reactive({
  advice: null,
  cost: null,
  sem: null,
  loading: false,
  analyzing: false,
  /** 可填充项，来自后端 fields */
  fields: [],
  /** 本次已经填进表单的 field 名，清单里锁住并标「已填入」 */
  filled: [],
  /** 分析那一刻的表单摘要，用来判断这批建议是不是已经过期 */
  base: ''
})

/** 抽屉开关：AI 建议整块放在右侧抽屉里 */
const aiDrawer = ref(false)

/** 每一条建议勾不勾，key 是 field。填充动作只认勾上的那些 */
const picked = ref({})

/**
 * 这一批建议挂在哪个号上（后端留痕的 refNo）。
 *
 * <p>提交后要拿它回写「建议有没有被采用」，两边必须是同一个号：
 * 有申请号就用申请号，没有就落到 NEW + 本人 id，跟后端保持一致。
 */
const adviceBizNo = ref('')
function adviceBizNoNow () {
  const no = form.applyNo || originApplyNo.value || ''
  return no || 'NEW' + (user.info?.userId ?? 0)
}

/** 上次填充前的表单快照，只记可能被动到的五个字段，撤销时按它还原 */
const undoSnap = ref(null)
/** 上次填充动了哪几个字段：撤销时只摘掉这几个的「已填入」标记 */
const undoFields = ref([])
const canUndo = computed(() => !!undoSnap.value)

/** 后端说清了这一份到底是不是模型出的，比 .env 里的模式更准 */
const aiLocalNow = computed(() => ai.advice?.local ?? aiLocal)

/** 已经填进去的不再计入「待填」，填过的项在清单里是锁住的 */
const pendingCount = computed(
  () => ai.fields.filter((f) => !ai.filled.includes(f.field)).length
)
const checkedCount = computed(
  () => ai.fields.filter((f) => !ai.filled.includes(f.field) && picked.value[f.field]).length
)

/** 入口条下面那句提示，跟着状态变，别让人点进去才发现什么都没有 */
const aiEntryHint = computed(() => {
  if (ai.analyzing) return '正在结合库内数据分析…'
  if (!ai.fields.length) return '点开看 AI 对这版内容的建议，可勾选填充'
  if (!pendingCount.value) return '建议都填完了，可重新分析或撤销'
  return `${pendingCount.value} 项还没填，点开挑一挑`
})

/** 表单摘要：只取会影响建议的那几项，行程段改了不影响金额口径之外的判断 */
function formDigest () {
  return JSON.stringify({
    reason: String(form.reason || '').trim(),
    tripType: form.tripType || '',
    estTraffic: form.estTraffic ?? null,
    estHotel: form.estHotel ?? null
  })
}

/**
 * 建议过期：填完又改了内容，这批建议就是上一版内容算出来的。
 * 不自动重算——那又会变成「边打字边刷」，只标一句让人自己点重新分析。
 */
const analyzeStale = computed(() => !!ai.fields.length && !!ai.base && ai.base !== formDigest())

/** 事由缺项 + 费用解读合一张卡展示，Δ 单独放在评价卡里（它是分数的一部分） */
const aiPoints = computed(() => {
  const list = [...(ai.advice?.items || [])]
  if (ai.cost?.text) {
    list.push({ level: ai.cost.level === 'high' ? 'mid' : 'low', text: ai.cost.text })
  }
  return list
})

/**
 * 实时那一半：费用解读 + 语义判断。表单一变就重算，不碰人写的事由。
 * 可填充清单不在这里——它是 analyze() 的事，要点按钮才跑。
 */
async function refreshAi () {
  if (!aiEnabled) return
  ai.loading = true
  try {
    const [cost, sem] = await Promise.all([
      aiCostExplain({
        costRef: costRef.amount,
        estTotal: total.value,
        estTraffic: form.estTraffic,
        estHotel: form.estHotel
      }),
      aiSemanticEval({
        reason: form.reason,
        tripType: form.tripType,
        // 传 id 不传名字：remote 模式下对象名称由后端按 id 查，
        // 前端拼出来的名字万一和库里不一致，模型就会拿着错的对象名去判「目的地对不对」
        partnerId: form.partnerId,
        estTraffic: form.estTraffic,
        estHotel: form.estHotel,
        itineraries: form.itineraries
      })
    ])
    ai.cost = cost
    ai.sem = sem
  } finally {
    ai.loading = false
  }
}

/**
 * 点按钮才跑的那一半：拿当前填的内容去问后端，要一份「可以直接填进表单」的清单。
 *
 * <p>为什么不随输入自动跑：一是每敲一次键盘烧一次调用，二是清单里的数字会一直跳，
 * 人还没填完就先看到一堆建议，反而不知道该看哪个。
 * 缺项提示（items）也跟着一起回来，所以「你还缺什么」同样是点了才出现。
 */
async function analyze () {
  if (!aiEnabled) return
  ai.analyzing = true
  try {
    // 这一批建议挂在哪个号上，提交后的采纳回执要用同一个号去回写。
    // 新建单没有申请号，用 NEW + 本人 id 这个槽位，跟后端 refNoOf 的算法一致
    adviceBizNo.value = adviceBizNoNow()
    const advice = await aiDraftAdvice({
      applyNo: adviceBizNo.value,
      reason: form.reason,
      tripType: form.tripType,
      partnerId: form.partnerId,
      estTraffic: form.estTraffic,
      estHotel: form.estHotel,
      exceedReason: form.exceedReason,
      itineraries: form.itineraries,
      // 本地 mock 模式没有库，靠页面已经查回来的这份参考算金额
      costRef: costRef.amount
    })
    if (!advice) {
      ai.fields = []
      ai.filled = []
      return ElMessage.info('AI 分析没拿到结果，后端可能没起来')
    }
    ai.advice = advice
    ai.fields = advice.fields || []
    // 换了一批建议，「已填入」的标记就作废：新清单里的值还没进表单
    ai.filled = []
    ai.base = formDigest()
    // 默认勾选规则：空白项勾上，已填项不勾——要覆盖已填的值，得人自己点上这一条
    const next = {}
    ai.fields.forEach((f) => { next[f.field] = !f.overwrite })
    picked.value = next
    if (!ai.fields.length) {
      ElMessage.info('这版填得挺全，AI 没找出可填充的项')
    }
  } finally {
    ai.analyzing = false
  }
}

/** 把一条建议写进表单。只认这五个字段，其余（对象、日期、城市等级）AI 一概不碰 */
function writeField (f) {
  const v = f.suggestValue
  if (f.field === 'reason') form.reason = v
  else if (f.field === 'tripType') form.tripType = v
  else if (f.field === 'estTraffic') form.estTraffic = Number(v)
  else if (f.field === 'estHotel') form.estHotel = Number(v)
  else if (f.field === 'exceedReason') form.exceedReason = v
}

/** 可能被 AI 改到的字段，填充前记一份、撤销时还原 */
const FILLABLE = ['reason', 'tripType', 'estTraffic', 'estHotel', 'exceedReason']

async function applyFields () {
  // 已经填进去的项锁住了，这里只取还待填且勾上的
  const list = ai.fields.filter((f) => picked.value[f.field] && !ai.filled.includes(f.field))
  if (!list.length) return

  // 覆盖已填的值要人确认一次。这个确认不能省：填完才发现事由被换了，等于白填
  const over = list.filter((f) => f.overwrite)
  if (over.length) {
    try {
      await ElMessageBox.confirm(
        `会覆盖已经填好的：${over.map((f) => f.label).join('、')}。填充后可以用「撤销上次填充」还原。`,
        '确认覆盖',
        { type: 'warning', confirmButtonText: '覆盖', cancelButtonText: '算了' }
      )
    } catch {
      return
    }
  }

  const snap = {}
  FILLABLE.forEach((k) => { snap[k] = form[k] })
  undoSnap.value = snap

  const justFilled = list.map((f) => f.field)
  list.forEach(writeField)
  // 填过的项在清单里锁住：不锁的话再点一次「填充选中」会重复写，撤销却只还原一次
  ai.filled = [...ai.filled, ...justFilled]
  undoFields.value = justFilled
  ElMessage.success(`已填充 ${list.length} 项`)

  // 金额和事由都变了，评价预览与语义判断必须重算，否则右侧还挂着上一次的分数
  await Promise.all([previewEval(), refreshAi()])

  // 出差类型一变，同类花费参考就换了另一组对标，金额建议也跟着不成立。
  // 这里直接重跑一次分析，比留着一屏过期数字让人自己猜要好。
  // 重分析会清掉「已填入」标记（新清单的值还没进表单），所以填过的要补回去
  if (justFilled.includes('tripType')) {
    await loadCostRef()
    await analyze()
    ai.filled = justFilled
    undoFields.value = justFilled
  }
}

async function undoFill () {
  const snap = undoSnap.value
  if (!snap) return
  FILLABLE.forEach((k) => { form[k] = snap[k] })
  undoSnap.value = null
  // 只摘掉上一次填的那几个，之前批次填的仍然算已填入
  ai.filled = ai.filled.filter((f) => !undoFields.value.includes(f))
  undoFields.value = []
  ElMessage.success('已还原到填充前')
  await Promise.all([loadCostRef(), previewEval(), refreshAi()])
}

let aiTimer = null
function refreshAiDebounced () {
  clearTimeout(aiTimer)
  aiTimer = setTimeout(refreshAi, 600)
}

const total = computed(() => Number(form.estTraffic || 0) + Number(form.estHotel || 0))

/**
 * 四行的定义。每行自带 unit 与 kind，格式化只按 kind 分派——
 * 早先三个格式化函数跨四行共用，天数行顺手调了金额的那个，页面上印成「¥1 ~ ¥3」；
 * 补一行就得记住别调错函数，这种约定撑不住，所以把单位焊死在行上。
 */
const REF_ROWS = [
  { key: 'traffic', label: '交通', unit: '元', kind: 'money', avg: 'avgTraffic', lo: 'minTraffic', hi: 'maxTraffic' },
  { key: 'hotel', label: '住宿', unit: '元', kind: 'money', avg: 'avgHotel', lo: 'minHotel', hi: 'maxHotel' },
  { key: 'total', label: '合计', unit: '元', kind: 'money', avg: 'avgAmount', lo: 'minAmount', hi: 'maxAmount' },
  { key: 'days', label: '整趟天数', unit: '天', kind: 'day', avg: 'avgDays', lo: 'minDays', hi: 'maxDays' }
]

/** 表格该不该摆出来：一个条件都没填时摆空表把结构亮出来，查不到时不摆——那句「无相关单据」才是答案 */
const refTableVisible = computed(() => !costRefQueryable.value || costRefLoading.value || !!costRef.amount)

/** 空值只有一种符号。写「待填」会让人分不清是没发请求还是查不到，横杠就是横杠 */
const DASH = '—'

/**
 * 参考表格的四行。「这一格显示什么」全在这里定，模板只负责画。
 * 放宽到最宽仍没单据时整张表是空的，表下那句「近 12 个月无相关单据」负责说明——
 * 它是结论不是空值，不能混成一个横杠。
 */
const refRows = computed(() => {
  const c = costRef.amount
  const num = (v) => (v == null || Number(v) === 0 ? null : Number(v))

  /** 单值：金额带 ¥，天数带「天」。单位取自 row.unit，行数再多也不会串 */
  const cell = (row, v) => {
    const n = num(v)
    if (n == null) return DASH
    return row.kind === 'day' ? `${n.toFixed(1)} 天` : `¥ ${money(n)}`
  }
  /** 区间：两端同用本行单位，任一端缺失就整格空着（给半个区间更误导） */
  const rangeOf = (row, lo, hi) => {
    const a = num(lo); const b = num(hi)
    if (a == null || b == null) return DASH
    return row.kind === 'day' ? `${a} ~ ${b} 天` : `¥ ${money(a)} ~ ¥ ${money(b)}`
  }
  /** 与同类的偏差：金额看倍数用百分比，天数用差值——「多 1 天」比「多 30%」好懂 */
  const dev = (row, mine, avg) => {
    const m = num(mine); const a = num(avg)
    if (m == null || a == null) return DASH
    if (row.kind === 'day') {
      const d = m - a
      return (d > 0 ? '+' : '') + d.toFixed(1) + ' 天'
    }
    const p = Math.round(((m - a) / a) * 100)
    return (p > 0 ? '+' : '') + p + '%'
  }

  const mineOf = { traffic: form.estTraffic, hotel: form.estHotel, total: total.value, days: tripDays.value }

  return REF_ROWS.map((row) => {
    const m = mineOf[row.key]
    return {
      key: row.key,
      // 项目名称上钉着自己的单位，读到「2.0」时先知道它是天还是钱
      label: `${row.label}（${row.unit}）`,
      avg: cell(row, c?.[row.avg]),
      range: rangeOf(row, c?.[row.lo], c?.[row.hi]),
      // 天数是员工自己排的日期算出来的整数，不再带一位小数
      mine: row.kind === 'day' ? (tripDays.value == null ? DASH : `${tripDays.value} 天`) : cell(row, m),
      dev: dev(row, m, c?.[row.avg])
    }
  })
})

/** 折叠时标题右边那句摘要：不点开也要知道有没有数、量级多少 */
const refSummary = computed(() => {
  const c = costRef.amount
  if (!costRefQueryable.value) return '待填比对条件'
  if (!c) return costRefLoading.value ? '查询中…' : '近 12 个月无相关单据'
  return `同类 ¥ ${money(c.avgAmount)} · ${c.sampleCnt} 单`
})

/** 表头那句「实际按什么比对的」：放宽过就必须写出来，否则员工以为看的是自己填的条件 */
const refScopeText = computed(() => {
  const c = costRef.amount
  if (!c) return ''
  const parts = [c.toCity, c.tripType, c.cityLevel, c.trafficType].filter(Boolean)
  const scope = parts.length ? parts.join(' · ') : '全部已通过单据'
  return `按「${scope}」比对（${c.sampleCnt} 单）`
})

function addRow () {
  form.itineraries.push(makeRow(form.itineraries.length + 1))
}

function removeRow (i) {
  form.itineraries.splice(i, 1)
}

function recalc () {
  previewEvalDebounced()
}

/** 目的地是打出来的，漏一个字就发一次请求太浪费，停手 400ms 再拉。 */
let costTimer = null
function loadCostRefDebounced () {
  clearTimeout(costTimer)
  costTimer = setTimeout(loadCostRef, 400)
}

/**
 * 拉同类花费参考。四个维度各带各的，没填的不传——后端会按维度优先级放宽，
 * 放宽了会在结果里标 relaxed，页面必须写明实际按哪组条件比对的。
 *
 * 一个维度都没填时不发请求（后端收到空条件也只会返回 null，白跑一趟）。
 */
async function loadCostRef () {
  if (!costRefQueryable.value) {
    costRef.amount = null
    return
  }
  const seg = form.itineraries[0] || {}
  costRefLoading.value = true
  try {
    costRef.amount = await apiCostRef({
      toCity: primaryDest.value || undefined,
      tripType: form.tripType || undefined,
      cityLevel: seg.cityLevel || undefined,
      trafficType: seg.trafficType || undefined,
      estTraffic: form.estTraffic ?? undefined,
      estHotel: form.estHotel ?? undefined,
      estDays: tripDays.value ?? undefined
    })
    // 费用解读要用刚拿到的参考区间，参考一变解读就得跟着变
    refreshAi()
  } catch {
    costRef.amount = null
  } finally {
    costRefLoading.value = false
  }
}

function onPartnerChange () {
  previewEval()
}

/**
 * 出差类型一变要动两件事：同类花费参考换了另一组对标，补充说明框跟着收起或放出。
 * 收起时顺手把说明清掉——框不见了值还在 form 上，提交就会带一句没人看得见的说明过去。
 */
function onTripTypeChange () {
  if (form.tripType !== TRIP_TYPE_OTHER) {
    form.tripTypeNote = ''
  }
  loadCostRefDebounced()
}

let timer = null
function previewEvalDebounced () {
  clearTimeout(timer)
  timer = setTimeout(previewEval, 400)
}

async function previewEval () {
  // G-02 收的是和提交同一份 ApplySubmitRequest（带 @Valid），少一个必填就 1001，
  // 所以信息没填齐时压根不发请求，顺手把上一次的分数清掉，右侧卡片跟着收回去
  if (!previewReady.value) {
    preview.level = ''
    preview.totalScore = 0
    preview.hints = []
    return
  }
  try {
    const data = await evalPreview(buildPayload())
    preview.level = data.level
    preview.totalScore = data.totalScore
    preview.hints = data.hints
  } catch { /* 预演失败不影响继续填写 */ }
}

/**
 * 落一次草稿。按钮点它、离开页面前的询问框也点它，
 * 拆分出来是为了让「存草稿并离开」这条路径不重复写代码。
 * 抛错由调用方决定拦不拦住导航。
 */
async function doSaveDraft () {
  const res = await apiSaveDraft(buildPayload())
  // 第一次存草稿后端才发号，之后一直复用：拿着号再存就是同一张单
  if (!currentApplyNo.value) {
    currentApplyNo.value = res.applyNo
    mode.value = 'draft'
    pickedDraft.value = res.applyNo
    await router.replace({ path: '/employee/apply', query: { applyNo: res.applyNo } })
  }
  markSaved()
  await loadDrafts()
  return res
}

async function saveDraft () {
  drafting.value = true
  try {
    const res = await doSaveDraft()
    ElMessage.success(`草稿已保存，申请号 ${res.applyNo}`)
  } finally {
    drafting.value = false
  }
}

/** 草稿箱下拉的数据源：只取本人还在 DRAFT 的单。 */
async function loadDrafts () {
  try {
    const data = await myApplyPage({ pageNum: 1, pageSize: 50, status: 'DRAFT' })
    // 状态还是前端再判一遍：列表接口理论上只回草稿，真混进来别的单，下拉里选了也读不回来
    drafts.value = (data.list || []).filter((d) => d.status === 'DRAFT')
  } catch {
    drafts.value = []
  }
}

/**
 * 删完草稿不停留在原页面内容上，直接重置回「刚打开新建页」的样子：
 * 表单、行程段、右侧的评分预演和同类参考一起清掉，
 * 最后把 formKey++ 让整个填写区重新挂载，避免 el-input / 日期选择器留着旧值的显示状态。
 */
async function resetToBlank () {
  Object.assign(form, emptyForm())
  mode.value = 'new'
  currentApplyNo.value = ''
  originApplyNo.value = ''
  standardHints.value = []
  preview.level = ''
  preview.totalScore = 0
  preview.hints = []
  ai.advice = null
  ai.cost = null
  ai.sem = null
  // 换单 / 新建时把上一张单的建议一起清掉，不然会拿着别人的金额往这张填
  ai.fields = []
  ai.filled = []
  ai.base = ''
  picked.value = {}
  undoSnap.value = null
  undoFields.value = []
  aiDrawer.value = false
  // 回到库里那份名单：fillForm 往下拉里补过的草稿对象不该留在新建单上
  partners.value = [...serverPartners.value]
  formKey.value++
  await nextTick()
  // 校验红字是挂在字段上的，表单重建后手动清一遍，不然新页面上还留着「请填写出差事由」
  formRef.value?.clearValidate()
  markSaved()
}

/**
 * 切换草稿 / 切回新建的统一出口。
 * 选中一张：把现有内容推平再读它的详情；清空选择（no 为空）：回到全新空白单。
 */
async function onPickDraft (no) {
  const target = no || ''
  if (target === currentApplyNo.value) return
  // 手上还有没存的内容，先问一次；选取消就把下拉恢复到刚才那张，别让人误会已经切过去了
  const goOn = await confirmLeave()
  if (!goOn) {
    pickedDraft.value = currentApplyNo.value
    return
  }
  await router.replace({ path: '/employee/apply', query: target ? { applyNo: target } : {} })
  // 上面 replace 之后由 watch(route.query.applyNo) 接着把表单换掉。
  // 只有 target 为空时会落到下面这行：replace 没带来新的 applyNo，watch 拿不到变化
  if (!target) await reloadFrom('')
}

/**
 * 离开前拦一道，给三个选择：
 * 「存草稿后离开」先存再走；「不保存，直接离开」丢掉内容走人；右上角 X / Esc = 哪儿也不去。
 * 内容没改过就直接放行，不打扰填表的人。
 *
 * 存草稿失败也不把人锁在页面上：那是后端的问题，不该让人连跳都跳不出去。
 * 所以失败后再问一次，确认要走就放行，取消就留在本页（可以复制走内容或再试一次）。
 *
 * @returns {Promise<boolean>} true = 可以继续走，false = 留在页面上
 */
async function confirmLeave () {
  if (!dirty.value) return true
  let action
  try {
    await ElMessageBox(
      mode.value === 'draft'
        ? `申请 ${currentApplyNo.value} 有改动还没保存，离开之后就找不回来了。要不要先存一次草稿？`
        : '这张申请还没存成草稿，离开之后填的内容会丢。要不要先存一次草稿？',
      '还没保存',
      {
        type: 'warning',
        distinguishCancelAndClose: true,
        confirmButtonText: '存草稿后离开',
        cancelButtonText: '不保存，直接离开'
      }
    )
    action = 'confirm'
  } catch (e) {
    // 'cancel' = 不保存直接离开，'close' = 点了 X / Esc
    action = e
  }
  if (action === 'close') return false
  if (action !== 'confirm') return true

  // 驳回重提这张没有原号可落，doSaveDraft 会新建一张草稿把它接住，
  // 下次从草稿箱里继续填就行
  drafting.value = true
  try {
    await doSaveDraft()
    ElMessage.success('已存为草稿')
    return true
  } catch {
    try {
      await ElMessageBox.confirm(
        '草稿没保存成功，现在离开会丢掉刚才填的内容。确定还要离开吗？',
        '保存失败',
        { type: 'warning', confirmButtonText: '仍然离开', cancelButtonText: '留在页面' }
      )
      return true
    } catch {
      return false
    }
  } finally {
    drafting.value = false
  }
}

onBeforeRouteLeave(async (to, from, next) => {
  // 同路径只是换了个 applyNo——那是切换草稿 / 保存后回填单号，算继续填写，不该弹
  if (to.path === from.path) return next()
  if (!(await confirmLeave())) return next(false)
  next()
})

/** 关标签页 / 刷新也拦一下（浏览器自己的提示框，文案由浏览器给） */
function onBeforeUnload (e) {
  if (!dirty.value) return
  e.preventDefault()
  e.returnValue = ''
}

async function removeDraft () {
  if (!currentApplyNo.value) return
  try {
    await ElMessageBox.confirm(
      `确认删除草稿 ${currentApplyNo.value}？删掉之后就不能再找回，页面会回到空白的新建状态。`,
      '删除草稿',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '留着' }
    )
  } catch {
    return
  }
  deleting.value = true
  try {
    await deleteApply(currentApplyNo.value)
    ElMessage.success('草稿已删除，可以继续填写新的申请')
    // 地址栏的 applyNo 必须一起抹掉：留着的话刷新页面又会把它当成草稿读回来
    await router.replace({ path: '/employee/apply' })
    await resetToBlank()
    await loadDrafts()
  } catch {
    // 后端 2002（不是自己的草稿 / 已进入流程）会在拦截器里统一提示，这里不重复弹
  } finally {
    deleting.value = false
  }
}

async function submit () {
  try {
    await formRef.value.validate()
  } catch {
    return ElMessage.warning('请补全必填项')
  }
  // 草稿可以半填，提交必须齐。行程段那几项后端是 @NotBlank，
  // 与其让它甩回一个不带位置的 1001，不如在这儿直接点出第几段缺什么
  if (form.itineraries.length === 0) {
    return ElMessage.warning('至少要填一段行程')
  }
  const bad = form.itineraries.findIndex(
    (i) => !i.fromCity || !i.toCity || !i.startDate || !i.endDate || !i.trafficType
  )
  if (bad >= 0) {
    return ElMessage.warning(`第 ${bad + 1} 段行程还没填完，出发地 / 目的地 / 日期 / 交通方式都要填`)
  }
  if (needExceedReason.value && !form.exceedReason) {
    return ElMessage.warning('存在超出差标的行程，请填写超标说明')
  }
  submitting.value = true
  try {
    // 采纳回执要在提交前算好：提交成功后表单会被推回空白，
    // 到时候就比不出「建议值 vs 最终提交值」了
    const adoptItems = buildAdoptItems()
    // 驳回重提走 A-05（生成新号），其余两种情况都打 A-02
    const res = mode.value === 'resubmit'
      ? await updateApply(originApplyNo.value, buildPayload())
      : await submitApply(buildPayload())
    reportAdopt(adoptItems)
    standardHints.value = res.standardHints || []
    if (res.evalPreview) {
      preview.level = res.evalPreview.level
      preview.totalScore = res.evalPreview.totalScore
      preview.hints = res.evalPreview.hints
    }
    ElMessage.success(`提交成功，申请号 ${res.applyNo}`)
    // 超标说明已经进了申请单。后端按申请号清的那条覆盖不到「新建时还没号」的情形，
    // 那种草稿挂在 NEW+本人 id 这个槽位上，得自己清一次
    deleteFieldDraft({ bizType: 'APPLY_SUBMIT', bizNo: exceedBizNo.value, fieldName: 'exceedReason' })
      .catch(() => {})
    // 内容已经落到库里了，别让离开守卫再弹一次「要不要存草稿」
    markSaved()
    // 提交完必须把页面推回空白：进了 keep-alive 之后组件不再销毁，
    // 不清的话下次点进「填写申请」看到的还是刚这张单的内容，再点一次提交就是重复下单
    await resetToBlank()
    // 草稿已经变成待初审，留在本页再点一次会报「不能改动已进入流程的单」
    // 路径是 /employee/list（路由里没登记 apply-list，写错会落进 404 兜底被弹回登录页）
    await router.push('/employee/list')
  } finally {
    submitting.value = false
  }
}

/**
 * 采纳回执：把每一条建议的「建议值」与「最终提交值」配成对发回去。
 *
 * <p>只收这一批建议里出现过的字段，没出过建议的字段不掺和——
 * 否则员工自己填的内容和 AI 无关，混进来会把采纳率算歪。
 *
 * <p>值是字符串化之后比的：金额在表单里可能是数字 3000，建议里是 "3000"，
 * 比之前统一转成字符串，别让类型差异被误判成「没采纳」。
 */
function buildAdoptItems () {
  if (!ai.fields.length) return []
  const items = []
  for (const f of ai.fields) {
    if (!f.field || f.suggestValue == null || f.suggestValue === '') continue
    items.push({
      field: f.field,
      suggested: String(f.suggestValue),
      finalValue: String(form[f.field] ?? '')
    })
  }
  return items
}

/**
 * 提交成功后补记采纳结果。
 *
 * <p>不能 await：这是记账，卡在这里会让提交转圈。失败了也不提示——
 * 员工已经看到「提交成功」，再甩一条红框说采纳没记上，是拿内部细节烦他。
 */
function reportAdopt (items) {
  if (!items.length || !adviceBizNo.value) return
  aiAdviceAdopt({ bizNo: adviceBizNo.value, items }).catch(() => {})
}

/**
 * 组装请求体。三种模式唯一的差别就是 applyNo 要不要带、带哪个：
 * 续写草稿带当前号（保持单号不变），驳回重提不带——新号由后端生成，
 * 新建当然也没有。
 */
function buildPayload () {
  const payload = {
    ...form,
    // 后端 ApplySubmitRequest 里 estTraffic / estHotel 是 @NotNull，而输入框清空之后值是 null。
    // 这里按 0 兜底——不然人只是想瞄一眼评价就被拦回来，预演还会顺着拦截器每 400ms 弹一次提示
    estTraffic: form.estTraffic ?? 0,
    estHotel: form.estHotel ?? 0,
    estTotal: total.value,
    // 类型不是「其他」时把说明清掉：框是跟着类型收起来的，值却还挂在 form 上，
    // 不清就会带着一句上上次的说明提交，审批页看到「验货」后面还挂一句别的话
    tripTypeNote: form.tripType === TRIP_TYPE_OTHER ? (form.tripTypeNote || '') : ''
  }
  // applyNo 一定要带进 saveDraft/submit，少了它后端会当成一张新单，等于每次保存都换一个申请号
  if (mode.value === 'draft' && currentApplyNo.value) {
    payload.applyNo = currentApplyNo.value
  }
  return payload
}

/** 把后端详情倒回表单。 */
function fillForm (d) {
  form.reason = d.reason || ''
  form.tripType = d.tripType || ''
  form.tripTypeNote = d.tripTypeNote || ''
  form.partnerId = d.partnerId ?? null
  form.estTraffic = d.estTraffic == null ? null : Number(d.estTraffic)
  form.estHotel = d.estHotel == null ? null : Number(d.estHotel)
  form.exceedReason = d.exceedReason || ''
  form.itineraries = (d.itineraries?.length ? d.itineraries : [makeRow(1)]).map((it, i) => ({
    seq: it.seq ?? i + 1,
    startDate: it.startDate || '',
    endDate: it.endDate || '',
    fromCity: it.fromCity || '',
    toCity: it.toCity || '',
    trafficType: it.trafficType || '',
    cityLevel: it.cityLevel || ''
  }))
  // 下拉只有 4 家常客，草稿里选的是别的对象时补一项，免得回填后显示空白
  if (d.partnerId && !partners.value.some((p) => p.id === d.partnerId)) {
    partners.value.push({ id: d.partnerId, name: d.partnerName || `往来对象 ${d.partnerId}` })
  }
}

/** 把某一张申请的内容读回来。传空则停在新建状态。 */
async function loadExisting (overrideNo) {
  const applyNo = overrideNo ?? route.query.applyNo
  if (!applyNo) return
  const d = await applyDetail(applyNo)
  if (d.status === 'DRAFT') {
    mode.value = 'draft'
    currentApplyNo.value = d.applyNo
    pickedDraft.value = d.applyNo
  } else if (d.status === 'REJECTED') {
    // 驳回单的内容抄过来当底稿，但提交后是新申请号，老单保持在 REJECTED 状态
    mode.value = 'resubmit'
    originApplyNo.value = d.applyNo
  } else {
    ElMessage.warning(`该单当前状态为 ${d.status}，不能继续编辑，已切换为新建`)
    return
  }
  fillForm(d)
}

/** 换到另一张（或什么都不选）：清空 → 读详情 → 重算右侧 → 重新记基线。 */
async function reloadFrom (applyNo) {
  await resetToBlank()
  await loadExisting(applyNo || '')
  pickedDraft.value = currentApplyNo.value
  markSaved()
  await Promise.all([loadCostRef(), previewEval(), refreshAi()])
}

// 评价要跟着输入实时动，所以整个表单都纳入监听：事由多打一个字、行程段换一种交通方式、
// 交通费改一个数，停手 400ms 后都会重算一次（够不够请求的门槛由 previewEval 判断）
watch(
  () => [form.reason, form.partnerId, form.tripType, JSON.stringify(form.itineraries), total.value],
  () => {
    previewEvalDebounced()
    refreshAiDebounced()
    // 行程段里的等级、交通方式、日期也是比对维度，改了就得重查同类参考
    loadCostRefDebounced()
  }
)

// 主目的地变化时同步刷新同类参考：换目的地等于换对标
watch(primaryDest, loadCostRefDebounced)

// 地址栏上的 applyNo 变了就换单。自己 saveDraft 带回来的同号要走开，
// 不然存一次草稿就把表单重启一遍，正在打的字会被回填覆盖
watch(
  () => route.query.applyNo,
  (no) => {
    const next = no || ''
    if (next === currentApplyNo.value) return
    reloadFrom(next)
  }
)

onMounted(async () => {
  // 先拉对象名单再回填：草稿里选的对象如果不在名单里，fillForm 要往下拉补一项，
  // 名单晚一步到就会把刚补的那项冲掉，下拉上又变成空白
  await loadPartners()
  await loadDrafts()
  await loadExisting()
  pickedDraft.value = currentApplyNo.value
  markSaved()
  // 草稿 / 驳回单带回来的内容有可能已经填齐了，这两种情况补算一次；
  // 新建的话两个 ready 都是 false，函数内部会直接返回，不会发出请求
  await Promise.all([loadCostRef(), previewEval(), refreshAi()])
})

/**
 * 「关网页前要不要拦一下」跟着展示状态走，不跟着生命周期走。
 * 组件进 keep-alive 之后切走不再 unmount，挂在 mounted 里的监听器会一直留着——
 * 人明明已经坐在「我的申请」页了，刷新浏览器还弹一句「可能没有保存更改」。
 * 只在展示期间挂着，离开这页就摘掉。（首次进入时 activated 也会触发一次，所以不用额外在 mounted 里加）
 */
onActivated(() => {
  window.addEventListener('beforeunload', onBeforeUnload)
})

onDeactivated(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
})
</script>

<style scoped>
.hd-tools { display: flex; gap: 10px; align-items: center; }
.ml-8 { margin-left: 8px; }
.total-line { display: flex; align-items: baseline; }
.total-line .amount { font-size: 18px; font-weight: 650; color: var(--c-primary); }

.ref-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.ref-table th {
  font-weight: 500;
  text-align: right;
  color: var(--c-text-weak);
  padding: 4px 6px;
  border-bottom: 1px solid var(--c-border);
}
.ref-table th:first-child,
.ref-table td:first-child { text-align: left; }
.ref-table td {
  text-align: right;
  padding: 6px;
  border-bottom: 1px solid var(--c-border-light, #f0f2f5);
}
.ref-table tr:last-child td { border-bottom: none; }
.ref-col-name { color: var(--c-text-sub); white-space: nowrap; }
.ref-table tr:last-child .ref-col-name { font-weight: 600; }
.ref-foot { font-size: 11.5px; line-height: 1.6; margin-top: 6px; }

/* 右栏折叠区：外框跟 .card 保持一致，展开后的内容边距跟 .card-bd 对齐 */
.side-collapse {
  background: var(--c-panel);
  border: 1px solid var(--c-border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}
.side-collapse :deep(.el-collapse-item__header) {
  padding: 0 18px;
  height: 46px;
  line-height: 46px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: .3px;
}
.side-collapse :deep(.el-collapse-item:last-child) { border-bottom: none; }
.side-collapse :deep(.el-collapse-item__wrap) { border-bottom: none; }
.side-collapse :deep(.el-collapse-item__content) { padding: 14px 18px 16px; }
.side-title { display: flex; align-items: center; width: 100%; }
.side-title-main { flex: none; }
/* 摘要靠右，离标题远一点、离展开箭头近一点，扫一眼就看见 */
.side-title-sub, .side-title-tag { margin-left: auto; }
.side-title-sub { font-size: 12px; font-weight: 400; letter-spacing: 0; }

.eval-head { display: flex; align-items: center; justify-content: space-between; }
.eval-score .amount { font-size: 22px; font-weight: 650; }
.hint-list { margin: 10px 0 0; padding-left: 18px; font-size: 12.5px; line-height: 2; color: var(--c-text-sub); }

/* AI 建议入口条：右栏本来就叠了三张卡，清单再塞进去要滚很久才看得到，
   所以这里只留一条入口，内容全在抽屉里 */
.ai-entry {
  position: relative;
  padding: 10px 30px 10px 12px;
  border: 1px solid #d9e3ef;
  border-radius: 8px;
  background: linear-gradient(90deg, var(--c-primary-soft), #fff 70%);
  cursor: pointer;
  transition: border-color .15s, box-shadow .15s;
}
.ai-entry:hover { border-color: var(--c-primary); box-shadow: 0 2px 8px rgba(0, 0, 0, .06); }
.ai-entry-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ai-entry-title { font-size: 13px; font-weight: 600; }
.ai-entry-sub { font-size: 11.5px; color: var(--c-text-weak); margin: 4px 0 0 30px; line-height: 1.5; }
.ai-entry-arrow {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 20px;
  color: var(--c-text-weak);
}

/* 抽屉宽度写在 size 上是固定 440px，窄屏靠这条兜底，别让抽屉比屏幕还宽 */
.ai-drawer { max-width: 94vw; }

.ai-drawer-hd { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ai-drawer-title { font-size: 14px; font-weight: 650; }

/* 抽屉里两个分区：先说缺什么，再列能一键填的项 */
.ai-drawer-sec { margin-top: 14px; }
.ai-sec-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-sub);
  padding-bottom: 6px;
  margin-bottom: 8px;
  border-bottom: 1px solid var(--c-border);
}

/* 库里没有对标数据时的开场说明：琥珀色左边条，与「表单改过了」那一行同一套观感 */
.ai-note {
  padding: 7px 11px;
  margin-bottom: 10px;
  border-radius: 6px;
  border-left: 3px solid var(--c-amber);
  background: var(--c-amber-soft);
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--c-text-sub);
}

.ai-drawer-ft { display: flex; gap: 8px; flex-wrap: wrap; }

/* AI 语义判断：放在评价卡里，跟分数挨着，因为 Δ 就是分数的一部分 */
.ai-sem {
  margin-top: 12px;
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
</style>
