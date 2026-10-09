<template>
  <div class="page-wrap">
    <div class="card">
      <div class="card-hd">
        <div style="display:flex;align-items:baseline;gap:10px">
          <h3>用户账号</h3>
          <span class="weak">密码只出不进，列表里的银行账号与财务侧一样脱敏</span>
        </div>
        <div style="display:flex;gap:8px">
          <el-select v-model="roleFilter" clearable size="small" placeholder="全部角色" style="width:140px" @change="search">
            <el-option v-for="(meta, key) in ROLE_META" :key="key" :label="meta.label" :value="key" />
          </el-select>
          <el-select v-model="deptFilter" clearable size="small" placeholder="全部部门" style="width:140px" @change="search">
            <el-option v-for="d in depts" :key="d.deptId" :label="d.deptName" :value="d.deptId" />
          </el-select>
          <el-input
            v-model="keyword" clearable size="small" placeholder="姓名或登录名"
            style="width:160px" @keyup.enter="search" @clear="search"
          />
          <el-button size="small" @click="search">查询</el-button>
          <el-button type="primary" size="small" @click="openCreate">新增账号</el-button>
        </div>
      </div>

      <div class="card-bd">
        <el-table :data="rows" v-loading="loading" border size="small">
          <el-table-column prop="id" label="ID" width="70" align="center" />
          <el-table-column prop="username" label="登录名" width="150" />
          <el-table-column prop="realName" label="姓名" width="110" />
          <el-table-column label="角色" width="120" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ roleName(row.role) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="deptName" label="部门" width="110" />
          <el-table-column label="银行账号" width="150" align="center">
            <template #default="{ row }">
              <span class="mono">{{ row.bankAccountMasked || '未设置' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="单据留存" width="100" align="center">
            <template #default="{ row }">
              <span :class="row.refCount > 0 ? '' : 'muted'">{{ row.refCount }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="建账号时间" width="160" />
          <el-table-column label="操作" width="130" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEdit(row)">修改</el-button>
              <el-tooltip :disabled="!row.self && row.refCount === 0" :content="deleteTip(row)" placement="top">
                <span>
                  <el-button
                    link type="danger" size="small"
                    :disabled="row.self || row.refCount > 0" @click="removeOne(row)"
                  >删除</el-button>
                </span>
              </el-tooltip>
            </template>
          </el-table-column>
          <template #empty>
            <span class="muted">没有符合条件的账号</span>
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

    <el-dialog v-model="dlg" :title="form.id ? '修改用户账号' : '新增用户账号'" width="480px">
      <el-form label-width="96px">
        <el-form-item label="登录名" required>
          <el-input v-model="form.username" :disabled="!!form.id" placeholder="用于登录，建完后不建议改" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.realName" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="form.role" style="width:100%" :disabled="isSelf">
            <el-option v-for="(meta, key) in ROLE_META" :key="key" :label="meta.label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属部门" required>
          <el-select v-model="form.deptId" style="width:100%">
            <el-option v-for="d in depts" :key="d.deptId" :label="d.deptName" :value="d.deptId" />
          </el-select>
        </el-form-item>
        <el-form-item label="银行账号">
          <el-input v-model="form.bankAccount" placeholder="报销付款用，留空表示不设置" />
        </el-form-item>
        <el-form-item :label="form.id ? '重置密码' : '初始密码'" :required="!form.id">
          <el-input v-model="form.password" type="password" show-password :placeholder="form.id ? '留空表示不修改密码' : '至少 6 位'" />
        </el-form-item>
        <el-form-item label="调整原因">
          <el-input v-model="form.reason" type="textarea" :rows="2" placeholder="写进调整留痕" />
        </el-form-item>
      </el-form>
      <div class="tip-box tip-warn" style="font-size:12.5px" v-if="isSelf">
        正在改自己的账号，角色这一项锁住了：把自己挪出管理员位置后，再想改回来就得动数据库。
      </div>
      <template #footer>
        <el-button size="small" @click="dlg = false">取消</el-button>
        <el-button type="primary" size="small" :loading="submitting" @click="submitOne">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onActivated, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminUserPage, createAccount, updateAccount, deleteAccount, deptList } from '@/api'
import { ROLE_META } from '@/config/menus'

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const roleFilter = ref('')
const deptFilter = ref(null)
const loading = ref(false)
const submitting = ref(false)
const dlg = ref(false)
const depts = ref([])

const emptyForm = () => ({ id: null, username: '', realName: '', role: 'EMPLOYEE', deptId: null, bankAccount: '', password: '', reason: '' })
const form = reactive(emptyForm())

/** 改自己时锁住角色：这是唯一一处前端替后端挡错的口径，后端也拦，两道都留着 */
const isSelf = computed(() => !!form.id && rows.value.some((r) => r.id === form.id && r.self))

function roleName (role) {
  return ROLE_META[role] ? ROLE_META[role].label : role
}

async function load () {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: pageSize.value }
    if (keyword.value) params.keyword = keyword.value
    if (roleFilter.value) params.role = roleFilter.value
    if (deptFilter.value) params.deptId = deptFilter.value
    const data = await adminUserPage(params)
    rows.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

async function loadDepts () {
  try {
    depts.value = await deptList()
  } catch (e) {
    // 部门列表加载失败不影响账号列表本身，只是在弹窗里选不了部门
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
  Object.assign(form, {
    id: row.id,
    username: row.username,
    realName: row.realName,
    role: row.role,
    deptId: row.deptId,
    bankAccount: '',
    password: '',
    reason: ''
  })
  dlg.value = true
}

async function submitOne () {
  if (!form.username.trim() || !form.realName.trim()) {
    ElMessage.warning('登录名与姓名都要填')
    return
  }
  if (!form.deptId) {
    ElMessage.warning('请选择所属部门')
    return
  }
  if (!form.id && form.password.length < 6) {
    ElMessage.warning('新增账号请设置至少 6 位的初始密码')
    return
  }
  if (form.id && form.password && form.password.length < 6) {
    ElMessage.warning('密码至少 6 位，不重置就留空')
    return
  }
  submitting.value = true
  try {
    const payload = {
      username: form.username,
      realName: form.realName,
      role: form.role,
      deptId: form.deptId,
      reason: form.reason
    }
    if (form.bankAccount.trim()) payload.bankAccount = form.bankAccount
    if (form.password) payload.password = form.password
    if (form.id) {
      await updateAccount(form.id, payload)
    } else {
      await createAccount(payload)
    }
    ElMessage.success(form.id ? '账号已更新' : '账号已新增')
    dlg.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

function deleteTip (row) {
  if (row.self) return '不能删掉正在登录的自己'
  return `名下有 ${row.refCount} 条单据或审批留痕，改姓名、改部门都行，删除不行`
}

async function removeOne (row) {
  let box
  try {
    box = await ElMessageBox.prompt(
      `「${row.realName}」将不能再登录，历史单据与审批留痕不受影响。`,
      '删除用户账号',
      { inputPlaceholder: '删除原因，写进调整留痕', inputValidator: (v) => (v && v.trim() ? true : '请填写删除原因') }
    )
  } catch (e) {
    return
  }
  await deleteAccount(row.id, box.value)
  ElMessage.success('账号已删除')
  await load()
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'AdmUser' })

// 回到这页重新拉一次：账号列表和部门下拉都可能被别人动过，筛选条件留在原处
onActivated(() => {
  load()
  loadDepts()
})
</script>
