<template>
  <div class="page-wrap">
    <div class="card">
      <div class="card-hd">
        <h3>票据影像</h3>
        <div class="hd-tools">
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </div>
      <div class="card-bd">
        <el-form :inline="true" class="inv-form">
          <el-form-item label="申请号">
            <el-input v-model="form.applyNo" placeholder="TRIP 开头" clearable style="width:170px" />
          </el-form-item>
          <el-form-item label="票号或销售方">
            <el-input v-model="form.keyword" placeholder="模糊匹配" clearable style="width:170px" />
          </el-form-item>
          <el-form-item label="开票日期">
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              start-placeholder="起"
              end-placeholder="止"
              style="width:240px"
            />
          </el-form-item>
          <el-form-item label="影像">
            <el-select v-model="form.imageOption" style="width:110px">
              <el-option label="全部" value="ALL" />
              <el-option label="有影像" value="YES" />
              <el-option label="缺影像" value="NO" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="onSearch">查询</el-button>
            <el-button @click="onReset">重置</el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <div class="card mt-16">
      <div class="card-hd">
        <h3>查询结果</h3>
        <span class="weak">共 {{ total }} 张</span>
      </div>
      <div class="card-bd">
        <div v-if="loading" class="weak">加载中…</div>

        <!-- 空态要说清是没查到，还是压根没有票，别让人以为坏了 -->
        <div v-else-if="!list.length" class="tip-box">
          没有符合条件的票据。票据是申请通过之后由员工上传的，还没传的单这里查不到。
        </div>

        <div v-else class="inv-grid">
          <div v-for="item in list" :key="item.id" class="inv-card">
            <InvoiceImage
              :id="item.id"
              :img-path="item.imgPath || ''"
              :img-url="item.imgUrl || ''"
              :thumb-url="item.thumbUrl || ''"
              :invoice-no="item.invoiceNo || ''"
              :size="120"
            />
            <div class="inv-card__no mono">{{ item.invoiceNo || '—' }}</div>
            <div class="inv-card__line">{{ item.seller || '—' }}</div>
            <div class="inv-card__line weak">{{ item.invoiceDate || '—' }}</div>
            <div class="inv-card__line amount">¥ {{ money(item.amount) }}</div>
            <div class="inv-card__line weak">{{ item.applicantName || '—' }} · {{ item.applyNo }}</div>
          </div>
        </div>

        <div class="row-end mt-16" v-if="total > pageSize">
          <el-pagination
            v-model:current-page="pageNum"
            :page-size="pageSize"
            :total="total"
            layout="prev, pager, next"
            @current-change="load"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { invoiceSearch } from '@/api'
import { money } from '@/utils/dict'
import InvoiceImage from '@/components/InvoiceImage.vue'

/**
 * 票据影像查询（I-05）。
 *
 * 财务审票要对照票面看，之前全系统只有员工自己的上传页见过图，
 * 复核页只有一个「已上传」标签。这里按条件把影像捞出来，列表带缩略图、点开看原图。
 *
 * 能看到哪些票由后端按角色定死（员工自己的、审批人审过的、财务全部），
 * 前端不传 userId，也不必知道权限规则。
 */
const loading = ref(false)
const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(12)
const dateRange = ref([])

const form = reactive({
  applyNo: '',
  keyword: '',
  imageOption: 'ALL'
})

/** 'ALL' 不传这个条件，交给后端自己决定要不要过滤 */
function imageFlag () {
  if (form.imageOption === 'YES') return true
  if (form.imageOption === 'NO') return false
  return undefined
}

async function load () {
  loading.value = true
  try {
    const res = await invoiceSearch({
      applyNo: form.applyNo || '',
      keyword: form.keyword || '',
      dateFrom: dateRange.value?.[0] || '',
      dateTo: dateRange.value?.[1] || '',
      hasImage: imageFlag(),
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    list.value = res?.list || []
    total.value = res?.total || 0
  } catch (e) {
    // 拦截器已经弹过提示，这里只把列表清空，别留在上一次的结果上骗人
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onSearch () {
  pageNum.value = 1
  load()
}

function onReset () {
  form.applyNo = ''
  form.keyword = ''
  form.imageOption = 'ALL'
  dateRange.value = []
  pageNum.value = 1
  load()
}

onMounted(load)
</script>

<style scoped>
.inv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 14px;
}

.inv-card {
  padding: 10px;
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
</style>
