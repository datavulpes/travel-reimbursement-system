<template>
  <div class="page-wrap">
    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane :label="isManager ? '待我复审' : '待我审批'" name="todo" />
      <el-tab-pane label="已办" name="done" />
    </el-tabs>

    <div class="card">
      <div class="card-hd">
        <h3>{{ tab === 'todo' ? titleText : '我处理过的单据' }}</h3>
        <span class="weak">{{ tipText }}</span>
      </div>
      <div class="card-bd">
        <el-table :data="list" v-loading="loading" border stripe size="small">
          <el-table-column prop="applyNo" label="申请号" width="155" class-name="mono" fixed="left" />
          <el-table-column prop="applicant" label="申请人" width="90" />
          <el-table-column prop="reason" label="事由" min-width="180" show-overflow-tooltip />

          <el-table-column label="预估合计" width="120" align="right">
            <template #default="{ row }">
              <span class="amount">¥ {{ money(row.estTotal) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="同类参考" width="150" align="center">
            <template #default="{ row }">
              <div class="ref-cell">
                <span class="amount">¥ {{ money(row.avgAmount) }}</span>
                <span
                  class="ref-flag"
                  :class="compareFlag(row)"
                >{{ compareText(row) }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column prop="standardResult" label="差标" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="STANDARD_RESULT[row.standardResult]?.type" size="small" effect="plain">
                {{ STANDARD_RESULT[row.standardResult]?.text }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="合理性评价" width="140" align="center">
            <template #default="{ row }">
              <el-tag :type="EVAL_LEVEL[row.evalLevel]?.type" size="small">
                {{ EVAL_LEVEL[row.evalLevel]?.text }}
              </el-tag>
              <div class="weak" style="font-size:11.5px">{{ row.totalScore }} 分</div>
            </template>
          </el-table-column>

          <el-table-column prop="status" label="状态" width="150" align="center">
            <template #default="{ row }">
              <el-tag :type="APPLY_STATUS[row.status]?.type" size="small">
                {{ APPLY_STATUS[row.status]?.text }}
              </el-tag>
              <div v-if="row.overdue" class="overdue">已逾期 {{ overdueText(row) }}</div>
              <div v-else-if="row.waitText" class="weak" style="font-size:11.5px">
                {{ row.waitText }}
              </div>
              <div v-if="row.remindCnt > 0" class="remind-flag">已催 {{ row.remindCnt }} 次</div>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="110" fixed="right" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="goDetail(row.applyNo)">
                {{ tab === 'todo' ? actionText : '查看' }}
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
  </div>
</template>

<script setup>
import { computed, onActivated, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { approvalTodo } from '@/api'
import { useUserStore } from '@/stores/user'
import { APPLY_STATUS, STANDARD_RESULT, EVAL_LEVEL, money } from '@/utils/dict'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

// 同一个页面两个角色在用：部门审批人做初审，管理层做复审，
// 后端 todo 接口已经按角色返回各自该看的单，这里只负责把话说对。
const isManager = computed(() => user.role === 'MANAGER')
const titleText = computed(() => (isManager.value ? '待复审单据' : '待审批单据'))
const actionText = computed(() => (isManager.value ? '去复审' : '去审批'))
const tipText = computed(() => (isManager.value
  ? '这些单已经过了部门初审，你签字后生成预借款'
  : '列表里已经把判断依据摆出来了，不用点进去翻'))
// 默认待办。带 ?tab=done 进来直接落在已办——详情页「返回列表」用的就是这个，
// 否则看完一张已办单回去会莫名其妙停在待办 tab
const tab = ref(route.query.tab === 'done' ? 'done' : 'todo')
const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)

function compareFlag (row) {
  if (row.estTotal > row.maxAmount) return 'up'
  if (row.estTotal < row.minAmount) return 'down'
  return 'in'
}

function compareText (row) {
  const f = compareFlag(row)
  return f === 'up' ? '高于上限' : f === 'down' ? '低于下限' : '区间内'
}

function overdueText (row) {
  const h = row.overdueHours || 0
  return h < 24 ? `${h} 小时` : `${Math.floor(h / 24)} 天`
}

/**
 * 补一行的「已等待多久」。
 *
 * 只在待办 tab 上算：已办那栏的单子状态早就走完了，
 * 摆一个「已等待 12 天」会让人以为它还在压着。
 */
function fillWait (rows) {
  const now = Date.now()
  for (const r of rows) {
    r.waitText = ''
    if (tab.value !== 'todo' || !r.statusAt || r.overdue) {
      continue
    }
    const t = new Date(String(r.statusAt).replace(/-/g, '/')).getTime()
    if (!t) {
      continue
    }
    const h = Math.floor((now - t) / 3600000)
    r.waitText = h < 1 ? '刚刚到这一步' : h < 24 ? `已等待 ${h} 小时` : `已等待 ${Math.floor(h / 24)} 天`
  }
}

async function load () {
  loading.value = true
  try {
    const data = await approvalTodo({
      listType: tab.value,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    list.value = data.list
    total.value = data.total
    fillWait(list.value)
  } finally {
    loading.value = false
  }
}

// 切 tab 要退回第一页：两个列表长度不一样，停在旧的页码上会翻出空页
function onTabChange () {
  pageNum.value = 1
  load()
}

function goDetail (applyNo) {
  // 把来源 tab 带给详情页，它「返回列表」才知道该回哪一栏
  router.push({ path: '/approver/detail', query: { applyNo, tab: tab.value } })
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'ApvTodo' })

/**
 * 每次回到列表都重新拉：批完的单要从待办里消失。
 * 筛选与页码是页面自己的状态，跟着留着，不被这次刷新带跑。
 *
 * tab 要跟着地址栏走——详情页提交完是带着 tab 回来的（看一张已办单就回已办栏），
 * 只靠 setup 里读一次的话，批完了回到列表还停在待办栏，人会以为没批成。
 * （首次进入时 activated 也会触发，所以不必再留一个 onMounted）
 */
onActivated(() => {
  const fromUrl = route.query.tab === 'done' ? 'done' : 'todo'
  if (fromUrl !== tab.value) {
    tab.value = fromUrl
    // 换 tab 退回第一页，跟 onTabChange 一个道理：两个列表长度不一样
    pageNum.value = 1
  }
  load()
})
</script>

<style scoped>
.ref-cell { display: flex; flex-direction: column; align-items: center; gap: 1px; }
.ref-flag { font-size: 11px; }
.ref-flag.up { color: var(--c-red); }
.ref-flag.down { color: var(--c-primary); }
.ref-flag.in { color: var(--c-green); }
.overdue {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--c-red);
}
.remind-flag {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--c-orange, #d46b08);
}
</style>
