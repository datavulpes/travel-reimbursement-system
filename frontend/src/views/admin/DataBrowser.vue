<template>
  <div class="page-wrap">
    <div class="card">
      <div class="card-hd">
        <div style="display:flex;align-items:baseline;gap:10px">
          <h3>全表只读浏览</h3>
          <span class="weak">用来核对数据和排查问题，这里没有任何修改入口</span>
        </div>
        <div style="display:flex;gap:8px;align-items:center">
          <el-select v-model="tableName" filterable size="small" style="width:220px" @change="pickTable">
            <el-option-group v-for="g in groups" :key="g.name" :label="g.name">
              <el-option v-for="t in g.tables" :key="t.name" :label="`${t.label}（${t.name}）`" :value="t.name" />
            </el-option-group>
          </el-select>
          <el-input
            v-model="idInput" clearable size="small" placeholder="按记录 ID 定位"
            style="width:150px" @keyup.enter="searchById" @clear="searchById"
          />
          <el-button size="small" @click="searchById">定位</el-button>
          <el-button size="small" @click="reload">刷新</el-button>
        </div>
      </div>

      <div class="card-bd">
        <div class="tip-box tip-info" style="font-size:12.5px;margin-bottom:12px" v-if="current?.note">
          {{ current.note }}
        </div>

        <div class="weak" style="margin-bottom:10px" v-if="current">
          共 {{ total }} 行 · 每列列名与库里的字段一致，单元格里的日期已转成可读格式
        </div>

        <el-table :data="rowList" v-loading="loading" border size="small" height="520" v-if="columns.length">
          <el-table-column
            v-for="col in columns" :key="col" :prop="col" :label="col"
            min-width="140" show-overflow-tooltip
          >
            <template #default="{ row }">
              <span class="mono">{{ showValue(row[col]) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <span class="muted">
              {{ idInput ? `没有 ID 为 ${idInput} 的记录` : '这张表当前没有数据' }}
            </span>
          </template>
        </el-table>

        <div class="muted" v-else>左侧选一张表开始浏览</div>

        <div class="row-end mt-12" v-if="columns.length">
          <el-pagination
            background layout="total, prev, pager, next"
            :total="total" :page-size="pageSize" v-model:current-page="pageNum"
            @current-change="loadRows"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onActivated, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { browseTables, browseTable } from '@/api'

const tables = ref([])
const tableName = ref('')
const columns = ref([])
const rowList = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const idInput = ref('')
const loading = ref(false)

/** 按后端返回的 group 归并成下拉的分组，顺序跟着注册表走 */
const groups = computed(() => {
  const map = []
  for (const t of tables.value) {
    let group = map.find((g) => g.name === t.group)
    if (!group) {
      group = { name: t.group, tables: [] }
      map.push(group)
    }
    group.tables.push(t)
  }
  return map
})

const current = computed(() => tables.value.find((t) => t.name === tableName.value) || null)

function showValue (v) {
  if (v === null || v === undefined || v === '') return '—'
  return v
}

async function loadTables () {
  try {
    tables.value = await browseTables()
    if (!tableName.value && tables.value.length) {
      tableName.value = tables.value[0].name
      await loadRows()
    }
  } catch (e) {
    ElMessage.error('没能取到可浏览的表清单')
  }
}

async function loadRows () {
  if (!tableName.value) return
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: pageSize.value }
    const id = Number(idInput.value)
    if (idInput.value && Number.isFinite(id) && id > 0) {
      params.id = id
    }
    const data = await browseTable(tableName.value, params)
    columns.value = data.columns
    rowList.value = data.rows
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function pickTable () {
  pageNum.value = 1
  idInput.value = ''
  loadRows()
}

function searchById () {
  pageNum.value = 1
  loadRows()
}

async function reload () {
  await loadTables()
  await loadRows()
}

// keep-alive 按组件名匹配，RoleLayout 的 KEEP_ALIVE 白名单里登记的就是这个名字
defineOptions({ name: 'AdmBrowse' })

/**
 * 回到这页重新拉当前这张表的数据。
 * 表名清单不跟着重拉：它基本不变，重拉还会把选中的表冲掉。
 * 选中的表、页码、id 定位都留在原处，回来接着上次的地方看。
 * （首次进入时 activated 也会触发，所以不必再留一个 onMounted）
 */
onActivated(async () => {
  if (tableName.value) await loadRows()
  else await loadTables()
})
</script>
