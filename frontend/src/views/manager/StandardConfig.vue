<template>
  <div class="page-wrap">
    <el-tabs v-model="tab">
      <el-tab-pane label="差标维护" name="standard" />
      <el-tab-pane label="部门预算" name="budget" />
      <el-tab-pane label="调整留痕" name="logs" />
    </el-tabs>

    <!-- 差标 -->
    <div class="card" v-if="tab === 'standard'">
      <div class="card-hd">
        <div style="display:flex;align-items:baseline;gap:10px">
          <h3>差旅标准</h3>
          <span class="weak">每次调整都要求写原因</span>
        </div>
        <el-button type="primary" size="small" @click="openCreate">新增差标</el-button>
      </div>
      <div class="card-bd">
        <el-table :data="standards" v-loading="loading.std" border size="small">
          <el-table-column prop="cityLevel" label="城市等级" width="110" />
          <el-table-column prop="trafficType" label="类别" width="120" />
          <el-table-column label="上限金额" width="140" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.maxAmount) }}</span></template>
          </el-table-column>
          <el-table-column prop="effectiveAt" label="生效日期" width="130" />
          <el-table-column prop="updatedBy" label="最近调整人" width="120" />
          <el-table-column label="操作" width="140" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openStd(row)">调整</el-button>
              <el-button link type="danger" size="small" @click="removeStd(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="weak" style="margin-top:10px">
          删除只能删该组最新的一档，历史版本留着给旧单据追溯；已经提交过的申请不受影响，
          差标结论在提交那一刻就写进申请单了。
        </div>
      </div>
    </div>

    <!-- 预算 -->
    <div class="card" v-if="tab === 'budget'">
      <div class="card-hd">
        <div style="display:flex;align-items:baseline;gap:10px">
          <h3>部门月度预算</h3>
        </div>
        <el-button type="primary" size="small" @click="openDeptCreate">新增部门</el-button>
      </div>
      <div class="card-bd">
        <el-table :data="depts" v-loading="loading.dept" border size="small">
          <el-table-column prop="deptName" label="部门" width="120" />
          <el-table-column label="月度预算" width="140" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.monthlyBudget) }}</span></template>
          </el-table-column>
          <el-table-column label="已使用" width="140" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.usedBudget) }}</span></template>
          </el-table-column>
          <el-table-column label="使用率" min-width="180">
            <template #default="{ row }">
              <el-progress
                v-if="row.monthlyBudget > 0"
                :percentage="Math.min(Math.round((row.usedBudget / row.monthlyBudget) * 100), 100)"
                :stroke-width="12"
                :status="row.usedBudget / row.monthlyBudget > 0.9 ? 'exception' : undefined"
              />
              <span v-else class="muted">未设预算</span>
            </template>
          </el-table-column>
          <el-table-column prop="standardLevel" label="差标等级" width="100" align="center" />
          <el-table-column prop="approverName" label="部门审批人" width="110" />
          <el-table-column label="操作" width="140" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openBudget(row)">调整</el-button>
              <el-tooltip
                :disabled="deletable(row)"
                :content="deleteTip(row)"
                placement="top"
              >
                <span>
                  <el-button link type="danger" size="small" :disabled="!deletable(row)" @click="removeDept(row)">
                    删除
                  </el-button>
                </span>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 留痕 -->
    <div class="card" v-if="tab === 'logs'">
      <div class="card-hd">
        <div style="display:flex;align-items:baseline;gap:10px">
          <h3>调整留痕</h3>
          <span class="weak">谁改的、什么时候改的、前后值是多少，只读</span>
        </div>
        <div style="display:flex;gap:8px">
          <el-select v-model="logType" clearable placeholder="全部类型" size="small" style="width:130px" @change="searchLogs">
            <el-option v-for="(m, key) in CONFIG_TYPE" :key="key" :label="m.text" :value="key" />
          </el-select>
          <el-input
            v-model="logKeyword" clearable size="small" placeholder="搜变更项或原因"
            style="width:180px" @keyup.enter="searchLogs" @clear="searchLogs"
          />
          <el-button size="small" @click="searchLogs">查询</el-button>
        </div>
      </div>
      <div class="card-bd">
        <el-table :data="logs" v-loading="loading.log" border size="small">
          <el-table-column prop="operateAt" label="调整时间" width="160" />
          <el-table-column label="类型" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="dictType(CONFIG_TYPE, row.type)" size="small" effect="plain">
                {{ dictText(CONFIG_TYPE, row.type) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="动作" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="dictType(CONFIG_ACTION, row.action)" size="small" effect="plain">
                {{ dictText(CONFIG_ACTION, row.action) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="变更项" width="140">
            <template #default="{ row }">
              <span>{{ row.targetName || row.targetId }}</span>
              <span class="muted" style="margin-left:6px" v-if="row.targetName && row.targetName !== row.targetId">
                {{ row.targetId }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="调整前" width="120" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.before) }}</span></template>
          </el-table-column>
          <el-table-column label="调整后" width="120" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.after) }}</span></template>
          </el-table-column>
          <el-table-column prop="reason" label="调整原因" min-width="200" show-overflow-tooltip />
          <el-table-column prop="operator" label="操作人" width="100" />
        </el-table>
        <div class="row-end" style="margin-top:14px">
          <el-pagination
            background layout="total, prev, pager, next"
            :total="logTotal" :page-size="10" v-model:current-page="logPage"
            @current-change="loadLogs"
          />
        </div>
      </div>
    </div>

    <!-- 差标新增 / 调整弹窗 -->
    <el-dialog v-model="stdDlg" :title="stdMode === 'create' ? '新增差标' : '调整差标'" width="440px">
      <el-form :model="stdForm" label-width="90px" size="small">
        <template v-if="stdMode === 'create'">
          <el-form-item label="城市等级">
            <el-select v-model="stdForm.cityLevel" placeholder="请选择" style="width:100%">
              <el-option v-for="c in CITY_LEVELS" :key="c" :label="c" :value="c" />
            </el-select>
          </el-form-item>
          <el-form-item label="类别">
            <el-select v-model="stdForm.trafficType" placeholder="请选择" style="width:100%">
              <el-option v-for="t in TRAFFIC_TYPES" :key="t" :label="t" :value="t" />
            </el-select>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="适用范围">
            <span class="muted">{{ stdForm.cityLevel }} · {{ stdForm.trafficType }}</span>
          </el-form-item>
          <el-form-item label="当前上限">
            <span class="amount">¥ {{ money(stdForm.originAmount) }}</span>
          </el-form-item>
        </template>
        <el-form-item label="上限金额">
          <el-input-number v-model="stdForm.maxAmount" :min="0" :precision="2" :step="100" style="width:100%" />
        </el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker v-model="stdForm.effectiveAt" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input v-model="stdForm.reason" type="textarea" :rows="3" placeholder="必填，例如：二线城市住宿价格上涨" />
        </el-form-item>
      </el-form>
      <div class="weak" v-if="stdMode === 'update'" style="margin-top:-6px">
        同城市等级 + 同类别 + 同一天只能有一条，改的是新的一版，旧版本保留；换个生效日期就行。
      </div>
      <template #footer>
        <el-button @click="stdDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="saveStd">保存</el-button>
      </template>
    </el-dialog>

    <!-- 预算调整弹窗 -->
    <el-dialog v-model="budgetDlg" title="调整部门预算" width="440px">
      <el-form :model="budgetForm" label-width="90px" size="small">
        <el-form-item label="部门">
          <span class="muted">{{ budgetForm.deptName }}</span>
        </el-form-item>
        <el-form-item label="当前预算">
          <span class="amount">¥ {{ money(budgetForm.originBudget) }}</span>
        </el-form-item>
        <el-form-item label="月度预算">
          <el-input-number v-model="budgetForm.monthlyBudget" :min="0" :precision="2" :step="1000" style="width:100%" />
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input v-model="budgetForm.reason" type="textarea" :rows="3" placeholder="必填，例如：三季度销售部出差计划增加" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="budgetDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="saveBudget">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新增部门弹窗 -->
    <el-dialog v-model="deptDlg" title="新增部门" width="440px">
      <el-form :model="deptForm" label-width="100px" size="small">
        <el-form-item label="部门名称">
          <el-input v-model="deptForm.deptName" placeholder="与组织架构保持一致，库里不能重名" />
        </el-form-item>
        <el-form-item label="月度预算">
          <el-input-number v-model="deptForm.monthlyBudget" :min="0" :precision="2" :step="1000" style="width:100%" />
        </el-form-item>
        <el-form-item label="差标等级">
          <el-select v-model="deptForm.standardLevel" placeholder="请选择" style="width:100%">
            <el-option v-for="c in CITY_LEVELS" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="新增原因">
          <el-input v-model="deptForm.reason" type="textarea" :rows="3" placeholder="必填，例如：新设大客户部，单独核算差旅" />
        </el-form-item>
      </el-form>
      <div class="weak" style="margin-top:-6px">
        部门审批人先留空，系统会指派一名现有审批人，建完可以在员工管理里再调。
      </div>
      <template #footer>
        <el-button @click="deptDlg = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="saveDept">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onActivated, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  standardList, createStandard, updateStandard, deleteStandard,
  deptList, createDept, updateBudget, deleteDept,
  configLogs, monthlyReport
} from '@/api'
import { CITY_LEVELS, TRAFFIC_TYPES, CONFIG_ACTION, CONFIG_TYPE, money, dictType, dictText } from '@/utils/dict'
import dayjs from 'dayjs'

const tab = ref('standard')
const submitting = ref(false)
const loading = reactive({ std: false, dept: false, log: false })

const standards = ref([])
const depts = ref([])
const logs = ref([])
const logTotal = ref(0)
const logPage = ref(1)
const logType = ref('')
const logKeyword = ref('')

// 差标弹窗：新增与调整共用一套表单，用 mode 区分能不能改城市等级与类别
const stdDlg = ref(false)
const stdMode = ref('create')
const stdForm = reactive({
  id: null, cityLevel: '', trafficType: '', originAmount: 0, maxAmount: 0, effectiveAt: '', reason: ''
})

const budgetDlg = ref(false)
const budgetForm = reactive({ deptId: null, deptName: '', originBudget: 0, monthlyBudget: 0, reason: '' })

const deptDlg = ref(false)
const deptForm = reactive({ deptName: '', monthlyBudget: 0, standardLevel: '二级', reason: '' })

async function loadStd () {
  loading.std = true
  try {
    standards.value = await standardList()
  } finally {
    loading.std = false
  }
}

/** 部门清单走后端 /config/depts，库里加了部门这里立刻能看到 */
async function loadDepts () {
  loading.dept = true
  try {
    depts.value = await deptList()
  } finally {
    loading.dept = false
  }
}

/**
 * 已使用额度取 F-01 月报里各部门本月实际付款额：dept 表没有 used_budget 字段，
 * 后端也不返回，用真实报销数据顶上，改完预算能看到使用率跟着动。
 */
async function loadBudgetUsage () {
  try {
    const rep = await monthlyReport({ month: dayjs().format('YYYY-MM') })
    const paid = Object.fromEntries((rep.rows || []).map((r) => [r.deptId, r.totalPay]))
    depts.value = (depts.value || []).map((d) => ({ ...d, usedBudget: paid[d.deptId] || 0 }))
  } catch (e) {
    // 月报挂了不影响预算列表，usedBudget 保持 0
  }
}

function searchLogs () {
  logPage.value = 1
  loadLogs()
}

async function loadLogs () {
  loading.log = true
  try {
    const params = { pageNum: logPage.value, pageSize: 10 }
    if (logType.value) params.type = logType.value
    if (logKeyword.value) params.keyword = logKeyword.value
    const data = await configLogs(params)
    logs.value = data.list
    logTotal.value = data.total
  } finally {
    loading.log = false
  }
}

/** 任何一处改完，差标/部门列表和留痕都要一起刷新，否则页面上的留痕比数据慢一拍 */
function refreshAfterChange () {
  return Promise.all([loadStd(), loadDepts(), loadBudgetUsage(), loadLogs()])
}

function openCreate () {
  stdMode.value = 'create'
  Object.assign(stdForm, {
    id: null,
    cityLevel: CITY_LEVELS[0],
    trafficType: TRAFFIC_TYPES[0],
    originAmount: 0,
    maxAmount: 0,
    effectiveAt: dayjs().format('YYYY-MM-DD'),
    reason: ''
  })
  stdDlg.value = true
}

function openStd (row) {
  stdMode.value = 'update'
  Object.assign(stdForm, row, {
    originAmount: row.maxAmount,
    effectiveAt: dayjs().format('YYYY-MM-DD'),
    reason: ''
  })
  stdDlg.value = true
}

async function saveStd () {
  if (!stdForm.reason) return ElMessage.warning('请填写调整原因')
  if (stdMode.value === 'create' && (!stdForm.cityLevel || !stdForm.trafficType)) {
    return ElMessage.warning('请选择城市等级与类别')
  }
  submitting.value = true
  try {
    const payload = {
      cityLevel: stdForm.cityLevel,
      trafficType: stdForm.trafficType,
      maxAmount: stdForm.maxAmount,
      effectiveAt: stdForm.effectiveAt,
      reason: stdForm.reason
    }
    if (stdMode.value === 'create') {
      await createStandard(payload)
      ElMessage.success('差标已新增')
    } else {
      await updateStandard(payload)
      ElMessage.success('差标已更新，旧版本保留')
    }
    stdDlg.value = false
    await refreshAfterChange()
  } finally {
    submitting.value = false
  }
}

async function removeStd (row) {
  let promptBox
  try {
    promptBox = await ElMessageBox.prompt(
      `${row.cityLevel}-${row.trafficType}（生效日期 ${row.effectiveAt}）删掉之后，这一档就没有标准了。`,
      '删除差标',
      {
        inputPlaceholder: '删除原因，写进调整留痕',
        inputValidator: (v) => (v && v.trim() ? true : '请填写删除原因')
      }
    )
  } catch (e) {
    return // 点取消，什么都不做
  }
  try {
    await deleteStandard(row.id, promptBox.value)
    ElMessage.success('差标已删除')
    await refreshAfterChange()
  } finally {
    // 失败时 request.js 已经弹了错误提示
  }
}

function openDeptCreate () {
  Object.assign(deptForm, { deptName: '', monthlyBudget: 0, standardLevel: '二级', reason: '' })
  deptDlg.value = true
}

function openBudget (row) {
  Object.assign(budgetForm, row, { originBudget: row.monthlyBudget, reason: '' })
  budgetDlg.value = true
}

async function saveBudget () {
  if (!budgetForm.reason) return ElMessage.warning('请填写调整原因')
  submitting.value = true
  try {
    await updateBudget({
      deptId: budgetForm.deptId,
      monthlyBudget: budgetForm.monthlyBudget,
      reason: budgetForm.reason
    })
    ElMessage.success('预算已更新')
    budgetDlg.value = false
    await refreshAfterChange()
  } finally {
    submitting.value = false
  }
}

async function saveDept () {
  if (!deptForm.deptName) return ElMessage.warning('请填写部门名称')
  if (!deptForm.reason) return ElMessage.warning('请填写新增原因')
  submitting.value = true
  try {
    await createDept({
      deptName: deptForm.deptName,
      monthlyBudget: deptForm.monthlyBudget,
      standardLevel: deptForm.standardLevel,
      reason: deptForm.reason
    })
    ElMessage.success('部门已新增')
    deptDlg.value = false
    await refreshAfterChange()
  } finally {
    submitting.value = false
  }
}

/** 名下有人或有单据的部门后端也会拒，这里先把按钮置灰，省得让用户白点一次 */
function deletable (row) {
  return !row.memberCount && !row.applyCount
}

function deleteTip (row) {
  if (row.memberCount) return `该部门下还有 ${row.memberCount} 名员工，先把人调走`
  if (row.applyCount) return `该部门名下有 ${row.applyCount} 张出差申请单，不能删`
  return '删除部门及其预算'
}

async function removeDept (row) {
  let promptBox
  try {
    promptBox = await ElMessageBox.prompt(
      `删除后「${row.deptName}」的月度预算 ¥ ${money(row.monthlyBudget)} 一并失效，留痕里仍保留记录。`,
      '删除部门',
      {
        inputPlaceholder: '删除原因，写进调整留痕',
        inputValidator: (v) => (v && v.trim() ? true : '请填写删除原因')
      }
    )
  } catch (e) {
    return
  }
  try {
    await deleteDept(row.deptId, promptBox.value)
    ElMessage.success('部门已删除')
    await refreshAfterChange()
  } finally {
    // 失败时 request.js 已经弹了错误提示
  }
}

watch(tab, (v) => {
  if (v === 'logs') loadLogs()
  if (v === 'budget') loadBudgetUsage()
  if (v === 'standard') loadStd()
})

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'MgtStandard' })

/**
 * 每次回到这页把四份数据拉一遍：差标、部门、预算用量、操作日志都可能被别人改过。
 * 正在编辑的弹窗（调差标 / 调预算 / 新增部门）不受影响——那是独立的弹窗状态，
 * 半途切走再回来，框里的输入还在。
 * （首次进入时 activated 也会触发，所以不必再留一个 onMounted）
 */
onActivated(async () => {
  await loadStd()
  await loadDepts()
  loadBudgetUsage()
  loadLogs()
})
</script>
