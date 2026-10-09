<template>
  <div class="page-wrap">
    <div class="card">
      <div class="card-hd">
        <div style="display:flex;align-items:baseline;gap:10px">
          <h3>部门档案</h3>
          <span class="weak">这里改名称与负责人；月度预算和差标等级归管理层</span>
        </div>
      </div>

      <div class="card-bd">
        <el-table :data="rows" v-loading="loading" border size="small">
          <el-table-column prop="deptId" label="ID" width="70" align="center" />
          <el-table-column prop="deptName" label="部门名称" min-width="150" />
          <el-table-column prop="approverName" label="部门负责人" width="130" />
          <el-table-column label="月度预算" width="140" align="right">
            <template #default="{ row }">
              <span class="amount muted">¥ {{ money(row.monthlyBudget) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="standardLevel" label="差标等级" width="100" align="center" />
          <el-table-column prop="memberCount" label="在册人数" width="90" align="center" />
          <el-table-column prop="applyCount" label="申请单数" width="90" align="center" />
          <el-table-column label="操作" width="130" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEdit(row)">修改档案</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <span class="muted">还没有部门</span>
          </template>
        </el-table>

        <div class="weak mt-12">
          预算与差标等级这两列之所以只读：前者直接决定员工能借多少钱（预借额度取预估合计与它的较小值），
          后者决定行程是否判超标、要不要写超标说明。改这两项要去管理层的「差标与预算」。
        </div>
      </div>
    </div>

    <el-dialog v-model="dlg" title="修改部门档案" width="460px">
      <el-form label-width="96px">
        <el-form-item label="部门名称" required>
          <el-input v-model="form.deptName" />
        </el-form-item>
        <el-form-item label="负责人" required>
          <el-select v-model="form.leaderId" filterable style="width:100%" placeholder="选一个人做部门初审">
            <el-option
              v-for="u in users" :key="u.id"
              :label="`${u.realName}（${roleName(u.role)}）`" :value="u.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input v-model="form.reason" type="textarea" :rows="2" placeholder="写进调整留痕" />
        </el-form-item>
      </el-form>
      <div class="tip-box tip-info" style="font-size:12.5px">
        负责人是这个部门单据的初审人，选错了员工提的单会停在别人的待办里。
      </div>
      <template #footer>
        <el-button size="small" @click="dlg = false">取消</el-button>
        <el-button type="primary" size="small" :loading="submitting" @click="submitOne">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onActivated, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { deptList, updateDeptInfo, adminUserPage } from '@/api'
import { ROLE_META } from '@/config/menus'
import { money } from '@/utils/dict'

const rows = ref([])
const users = ref([])
const loading = ref(false)
const submitting = ref(false)
const dlg = ref(false)

const form = reactive({ deptId: null, deptName: '', leaderId: null, reason: '' })

function roleName (role) {
  return ROLE_META[role] ? ROLE_META[role].label : role
}

async function load () {
  loading.value = true
  try {
    rows.value = await deptList()
  } finally {
    loading.value = false
  }
}

/** 负责人候选取全部在册账号，不做角色过滤——部门初审人由这里指定，而不是由角色倒推 */
async function loadUsers () {
  try {
    const data = await adminUserPage({ pageNum: 1, pageSize: 100 })
    users.value = data.list
  } catch (e) {
    users.value = []
  }
}

function openEdit (row) {
  Object.assign(form, { deptId: row.deptId, deptName: row.deptName, leaderId: row.leaderId, reason: '' })
  dlg.value = true
}

async function submitOne () {
  if (!form.deptName.trim() || !form.leaderId) {
    ElMessage.warning('部门名称与负责人都要填')
    return
  }
  submitting.value = true
  try {
    await updateDeptInfo(form.deptId, {
      deptName: form.deptName,
      leaderId: form.leaderId,
      reason: form.reason
    })
    ElMessage.success('部门档案已更新')
    dlg.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'AdmDept' })

// 回到这页重新拉一次：部门与负责人列表，以及编辑用的用户下拉
onActivated(() => {
  load()
  loadUsers()
})
</script>
