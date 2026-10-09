<template>
  <div class="page-wrap">
    <div class="card">
      <div class="card-hd">
        <h3>{{ tab === 'pending' ? '待复核报销单' : (tab === 'payable' ? '待付款报销单' : '已结清报销单') }}</h3>
        <div class="hd-tools">
          <el-checkbox v-if="tab === 'pending'" v-model="onlyIssue" @change="load">只看有异常的</el-checkbox>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </div>
      <div class="card-bd">
        <el-tabs v-model="tab" @tab-change="onTabChange">
          <el-tab-pane label="待复核" name="pending" />
          <el-tab-pane label="待付款" name="payable" />
          <el-tab-pane label="已结清" name="paid" />
        </el-tabs>

        <el-table :data="list" v-loading="loading" border stripe size="small">
          <el-table-column prop="reportNo" label="报销单号" width="155" class-name="mono" fixed="left" />
          <el-table-column prop="applyNo" label="关联申请号" width="155" class-name="mono" />
          <el-table-column prop="applicant" label="报销人" width="90" />

          <el-table-column label="票据合计" width="110" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.totalAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="预借冲抵" width="110" align="right">
            <template #default="{ row }"><span class="amount-out">¥ {{ money(row.offsetAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="应付净额" width="110" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.netAmount) }}</span></template>
          </el-table-column>

          <el-table-column prop="invoiceCnt" label="票据数" width="80" align="center" />

          <el-table-column label="业务结果回填" width="130" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.resultCnt" type="success" size="small">已回填 {{ row.resultCnt }}</el-tag>
              <el-tag v-else type="info" size="small" effect="plain">未回填</el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="precheckResult" label="预检" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="PRECHECK_RESULT[row.precheckResult]?.type" size="small" effect="plain">
                {{ PRECHECK_RESULT[row.precheckResult]?.text }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="createdAt" label="提交时间" width="155" />

          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="EXPENSE_STATUS[row.status]?.type" size="small" effect="plain">
                {{ EXPENSE_STATUS[row.status]?.text }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="110" fixed="right" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="goReview(row.reportNo)">
                {{ tab === 'pending' ? '复核' : (tab === 'payable' ? '去付款' : '查看') }}
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

    <!-- 待复核那一档不需要说明：列表本身已经是全部待办，没有需要解释的东西 -->
    <div v-if="tab !== 'pending'" class="tip-box tip-info" style="margin-top:14px">
      {{ tab === 'payable'
        ? '复核通过的单会从「待复核」挪到这里。点「去付款」进详情页，付款卡片在页面右下。'
        : '已付款的单留在这里，数据同时进入管理层的月度报表与费效分析。' }}
    </div>
  </div>
</template>

<script setup>
import { computed, onActivated, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { financePending, financePayable } from '@/api'
import { PRECHECK_RESULT, EXPENSE_STATUS, money } from '@/utils/dict'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const onlyIssue = ref(false)
// 详情页付款完成后带着 tab=paid 回来，直接落在「已结清」上
const tab = ref(['pending', 'payable', 'paid'].includes(route.query.tab) ? route.query.tab : 'pending')
const all = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

const list = computed(() => (onlyIssue.value ? all.value.filter((r) => r.precheckResult !== 'PASS') : all.value))

async function load () {
  loading.value = true
  try {
    // 后端 /finance/pending 支持 status 过滤，只是之前漏了「复核通过待付款」这一档，
    // 单子一过会就从列表蒸发，付款入口在详情页，财务点不回去。
    const data = tab.value === 'payable'
      ? await financePayable({ pageNum: pageNum.value, pageSize: pageSize.value })
      : await financePending({
        pageNum: pageNum.value,
        pageSize: pageSize.value,
        status: tab.value === 'paid' ? 'PAID' : 'PENDING'
      })
    all.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function onTabChange () {
  pageNum.value = 1
  onlyIssue.value = false
  load()
}

function goReview (reportNo) {
  router.push({ path: '/finance/review', query: { reportNo } })
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'FinList' })

/**
 * 每次回到列表都重新拉：复核完、付完款的单要换个栏位待着。
 * 筛选与页码留在原处，不被这次刷新带跑。
 *
 * tab 跟着地址栏走：详情页付完款是带着 tab=paid 回来的，
 * 只靠 setup 读一次的话，付完回到列表还停在待复核，那张单像凭空消失了一样。
 * （首次进入时 activated 也会触发，所以不必再留一个 onMounted）
 */
onActivated(() => {
  const fromUrl = ['pending', 'payable', 'paid'].includes(route.query.tab) ? route.query.tab : 'pending'
  if (fromUrl !== tab.value) {
    tab.value = fromUrl
    // 跟 onTabChange 保持一致：换栏就回第一页，并把「只看有问题的」取消掉
    pageNum.value = 1
    onlyIssue.value = false
  }
  load()
})
</script>

<style scoped>
.hd-tools { display: flex; align-items: center; gap: 14px; }
</style>
