<template>
  <div class="page-wrap">
    <div class="card">
      <div class="card-hd">
        <div style="display:flex;align-items:baseline;gap:10px">
          <h3>往来对象</h3>
          <span class="weak">累计投入与产出由付款和业务结果回填累加，页面不开放填写</span>
        </div>
        <div style="display:flex;gap:8px">
          <el-input
            v-model="keyword" clearable size="small" placeholder="按名称搜索"
            style="width:180px" @keyup.enter="search" @clear="search"
          />
          <el-button size="small" @click="search">查询</el-button>
          <el-button type="primary" size="small" @click="openCreate">新增对象</el-button>
        </div>
      </div>

      <div class="card-bd">
        <el-table :data="rows" v-loading="loading" border size="small">
          <el-table-column prop="id" label="ID" width="70" align="center" />
          <el-table-column prop="partnerName" label="对象名称" min-width="200" />
          <el-table-column label="类型" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="row.partnerType === 'CUSTOMER' ? 'success' : 'warning'">
                {{ partnerTypeName(row.partnerType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="累计投入" width="130" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.totalInvest) }}</span></template>
          </el-table-column>
          <el-table-column label="累计产出" width="130" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ money(row.totalOutput) }}</span></template>
          </el-table-column>
          <el-table-column label="被引用" width="90" align="center">
            <template #default="{ row }">
              <span :class="row.applyCount > 0 ? '' : 'muted'">{{ row.applyCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEdit(row)">修改</el-button>
              <el-tooltip :disabled="row.applyCount === 0" :content="deleteTip(row)" placement="top">
                <span>
                  <el-button
                    link type="danger" size="small"
                    :disabled="row.applyCount > 0" @click="removeOne(row)"
                  >删除</el-button>
                </span>
              </el-tooltip>
            </template>
          </el-table-column>
          <template #empty>
            <span class="muted">{{ keyword ? `没有名称含「${keyword}」的往来对象` : '还没有往来对象，点右上角新增' }}</span>
          </template>
        </el-table>

        <div class="row-end mt-12">
          <el-pagination
            background layout="total, prev, pager, next"
            :total="total" :page-size="pageSize" v-model:current-page="pageNum"
            @current-change="load"
          />
        </div>
      </div>
    </div>

    <el-dialog v-model="dlg" :title="form.id ? '修改往来对象' : '新增往来对象'" width="440px">
      <el-form label-width="96px">
        <el-form-item label="对象名称" required>
          <el-input v-model="form.partnerName" placeholder="如：深圳市某某科技有限公司" />
        </el-form-item>
        <el-form-item label="对象类型" required>
          <el-select v-model="form.partnerType" style="width:100%">
            <el-option label="客户" value="CUSTOMER" />
            <el-option label="供应商" value="SUPPLIER" />
          </el-select>
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input v-model="form.reason" type="textarea" :rows="2" placeholder="写进调整留痕，管理层留痕页能查到" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dlg = false">取消</el-button>
        <el-button type="primary" size="small" :loading="submitting" @click="submitOne">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onActivated, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminPartnerPage, createPartner, updatePartner, deletePartner } from '@/api'
import { PARTNER_TYPES, money } from '@/utils/dict'

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const loading = ref(false)
const submitting = ref(false)
const dlg = ref(false)

const emptyForm = () => ({ id: null, partnerName: '', partnerType: 'CUSTOMER', reason: '' })
const form = reactive(emptyForm())

/**
 * PARTNER_TYPES 的值是中文字符串本身，不是 { text } 结构，
 * 所以这里直接索引取值，不能用 dictText——那会给 String 取 .text，拿到 undefined。
 */
function partnerTypeName (type) {
  return PARTNER_TYPES[type] || type || '-'
}

async function load () {
  loading.value = true
  try {
    const data = await adminPartnerPage({ pageNum: pageNum.value, pageSize: pageSize.value, keyword: keyword.value })
    rows.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function search () {
  pageNum.value = 1
  load()
}

function openCreate () {
  Object.assign(form, emptyForm())
  dlg.value = true
}

function openEdit (row) {
  Object.assign(form, { id: row.id, partnerName: row.partnerName, partnerType: row.partnerType, reason: '' })
  dlg.value = true
}

async function submitOne () {
  if (!form.partnerName.trim()) {
    ElMessage.warning('请填写对象名称')
    return
  }
  submitting.value = true
  try {
    if (form.id) {
      await updatePartner(form.id, { partnerName: form.partnerName, partnerType: form.partnerType, reason: form.reason })
    } else {
      await createPartner({ partnerName: form.partnerName, partnerType: form.partnerType, reason: form.reason })
    }
    ElMessage.success(form.id ? '往来对象已更新' : '往来对象已新增')
    dlg.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

function deleteTip (row) {
  return `已被 ${row.applyCount} 张单据引用，删了这些单据就找不到对象了`
}

async function removeOne (row) {
  let box
  try {
    box = await ElMessageBox.prompt(
      `删除后员工填申请时就选不到「${row.partnerName}」了，留痕里仍保留记录。`,
      '删除往来对象',
      { inputPlaceholder: '删除原因，写进调整留痕', inputValidator: (v) => (v && v.trim() ? true : '请填写删除原因') }
    )
  } catch (e) {
    return
  }
  await deletePartner(row.id, box.value)
  ElMessage.success('往来对象已删除')
  await load()
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'AdmPartner' })

// 回到这页重新拉一次，先前的搜索条件留在原处
onActivated(load)
</script>
