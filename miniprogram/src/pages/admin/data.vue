<template>
  <view class="page page-tab">
    <view class="tabs">
      <text :class="['tab', seg === 'partner' ? 'on' : '']" @click="switchSeg('partner')">往来对象</text>
      <text :class="['tab', seg === 'user' ? 'on' : '']" @click="switchSeg('user')">用户账号</text>
    </view>

    <!-- 往来对象：名称与类型归系统管理员，两个累计金额只能看 -->
    <template v-if="seg === 'partner'">
      <view class="fbar">
        <view class="fbtn" @click="pDrawer = true">
          <text class="fbtn-ic">☰</text>
          <text class="fbtn-t">筛选</text>
          <text v-if="pChips.length" class="fbadge">{{ pChips.length }}</text>
        </view>
        <view v-if="pChips.length" class="fchips">
          <view v-for="c in pChips" :key="c.k" class="fchip" @click="clearChip('p', c.k)">
            <text class="fchip-t">{{ c.t }}</text>
            <text class="fchip-x">×</text>
          </view>
        </view>
        <text v-else class="fcount">共 {{ LP.total }} 个</text>
        <view class="add" @click="openForm(null)"><text class="add-t">＋ 新增</text></view>
      </view>

      <FilterDrawer
        v-if="pDrawer" :groups="pGroups" :values="LP.filters"
        local-note="对象名称交给后端查；类型、是否被引用、累计投入、排序在已取回的列表里筛"
        @close="pDrawer = false" @apply="onPartnerFilter" />

      <view v-if="form" class="card form">
        <text class="sec">{{ form.id ? '修改对象' : '新增对象' }}</text>
        <input v-model="form.partnerName" class="ipt" placeholder="对象名称" placeholder-class="ph" />
        <view class="picker-row">
          <text class="lbl">类型</text>
          <picker mode="selector" :range="typeNames" :value="typeIndex" @change="onType">
            <text class="pick">{{ typeNames[typeIndex] }} ▾</text>
          </picker>
        </view>
        <input v-model="form.reason" class="ipt" placeholder="调整说明（写进留痕）" placeholder-class="ph" />
        <view class="btn-row">
          <button class="btn btn-ghost f1" @click="form = null">取消</button>
          <button class="btn f1" @click="savePartner">保存</button>
        </view>
      </view>

      <view v-if="loading" class="empty"><text>加载中…</text></view>
      <template v-else>
        <view v-if="LP.total === 0" class="empty"><text>没有符合条件的对象</text></view>
        <view v-for="p in LP.pagedRows" :key="p.id" class="card">
          <view class="row1">
            <text class="name">{{ p.partnerName }}</text>
            <text :class="['tag', p.partnerType === 'CUSTOMER' ? 'tag-blue' : 'tag-gray']">
              {{ p.partnerType === 'CUSTOMER' ? '客户' : '供应商' }}
            </text>
          </view>
          <text class="sub">累计投入 ¥ {{ fmtMoney(p.totalInvest) }} → 产出 ¥ {{ fmtMoney(p.totalOutput) }}</text>
          <view class="row2">
            <text class="sub">关联 {{ p.applyCount }} 张申请单</text>
            <view class="ops">
              <text class="op" @click="openForm(p)">修改</text>
              <text :class="['op', p.applyCount > 0 ? 'op-off' : 'op-danger']" @click="removePartner(p)">删除</text>
            </view>
          </view>
          <text v-if="p.applyCount > 0" class="note">已被单据引用，删不掉</text>
        </view>
        <Pager
          v-if="LP.total > 0"
          :total="LP.total" :page-num="LP.pageNum" :page-size="LP.pageSize"
          @page="LP.onPage" @size="LP.onSize" />
        <text class="note pad">累计投入与产出由付款和成果回填维护，这里不给改</text>
      </template>
    </template>

    <!-- 用户账号：只读。改姓名角色部门去 Web 端，免得在手机上误操作 -->
    <template v-else>
      <view class="fbar">
        <view class="fbtn" @click="uDrawer = true">
          <text class="fbtn-ic">☰</text>
          <text class="fbtn-t">筛选</text>
          <text v-if="uChips.length" class="fbadge">{{ uChips.length }}</text>
        </view>
        <view v-if="uChips.length" class="fchips">
          <view v-for="c in uChips" :key="c.k" class="fchip" @click="clearChip('u', c.k)">
            <text class="fchip-t">{{ c.t }}</text>
            <text class="fchip-x">×</text>
          </view>
        </view>
        <text v-else class="fcount">共 {{ LU.total }} 个账号</text>
      </view>

      <FilterDrawer
        v-if="uDrawer" :groups="uGroups" :values="LU.filters"
        local-note="姓名（登录名）与角色交给后端查；部门在已取回的列表里筛"
        @close="uDrawer = false" @apply="onUserFilter" />

      <view v-if="loading" class="empty"><text>加载中…</text></view>
      <template v-else>
        <view v-if="LU.total === 0" class="empty"><text>没有符合条件的用户</text></view>
        <view v-for="u in LU.pagedRows" :key="u.id" class="card">
          <view class="row1">
            <text class="name">{{ u.realName || '—' }}</text>
            <text class="tag tag-blue">{{ roleOf(u.role) }}</text>
          </view>
          <text class="sub">{{ u.username }} · {{ u.deptName || '—' }}</text>
          <text class="note">银行账号 {{ dash(u.bankAccountMasked) }} · 被 {{ u.refCount }} 处引用{{ u.self ? ' · 本人' : '' }}</text>
        </view>
        <Pager
          v-if="LU.total > 0"
          :total="LU.total" :page-num="LU.pageNum" :page-size="LU.pageSize"
          @page="LU.onPage" @size="LU.onSize" />
        <text class="note pad">账号在这里只读，改姓名、部门、角色去 Web 端</text>
      </template>
    </template>

    <TabBar current="admin" />
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  adminPartners, adminCreatePartner, adminUpdatePartner, adminDeletePartner, adminUsers
} from '../../api'
import { ROLE, dash, fmtMoney } from '../../utils/dict'
import { usePagedList, inBand, uniqOptions, activeKeys } from '../../utils/paged'
import FilterDrawer from '../../components/FilterDrawer.vue'
import Pager from '../../components/Pager.vue'
import TabBar from '../../components/TabBar.vue'
import { hasTab } from '../../utils/tabs'

const seg = ref('partner')
const loading = ref(false)
const pDrawer = ref(false)
const uDrawer = ref(false)

// 对象类型：后端 Partner.TYPE_CUSTOMER / TYPE_SUPPLIER
const TYPES = [
  { value: 'CUSTOMER', label: '客户' },
  { value: 'SUPPLIER', label: '供应商' }
]
const typeNames = TYPES.map((t) => t.label)
const typeText = (v) => (TYPES.find((t) => t.value === v) || {}).label || v

const USED = { '': '不限', has: '已被单据引用', none: '还没被引用' }
const PBAND = {
  '': '不限',
  '0-1000': '1 千以下',
  '1000-10000': '1 千 – 1 万',
  '10000-0': '1 万以上'
}
const PSORT = { '': '默认', nameAsc: '名称 A → Z', investDesc: '投入高 → 低', investAsc: '投入低 → 高' }

const ROLE_KEYS = ['', 'EMPLOYEE', 'APPROVER', 'FINANCE', 'MANAGER', 'SYS_ADMIN']
const roleNames = ['全部角色'].concat(ROLE_KEYS.slice(1).map((k) => ROLE[k]))
const roleText = (v) => ROLE[v] || v || '—'

/* ---------------- 往来对象 ---------------- */

function filterPartner (list, f) {
  let out = list.slice()
  if (f.type) out = out.filter((p) => p.partnerType === f.type)
  if (f.used === 'has') out = out.filter((p) => (Number(p.applyCount) || 0) > 0)
  if (f.used === 'none') out = out.filter((p) => (Number(p.applyCount) || 0) === 0)
  if (f.band) out = out.filter((p) => inBand(Number(p.totalInvest) || 0, f.band))
  const byInvest = (a, b) => (Number(a.totalInvest) || 0) - (Number(b.totalInvest) || 0)
  if (f.sort === 'nameAsc') out = out.sort((a, b) => (a.partnerName || '').localeCompare(b.partnerName || ''))
  if (f.sort === 'investDesc') out = out.sort((a, b) => byInvest(b, a))
  if (f.sort === 'investAsc') out = out.sort(byInvest)
  return out
}

const LP = usePagedList(filterPartner, { keyword: '', type: '', used: '', band: '', sort: '' })

const pGroups = computed(() => [
  { key: 'keyword', label: '对象名称', type: 'text', placeholder: '名称关键字' },
  { key: 'type', label: '类型', type: 'select', options: [{ value: '', label: '全部' }].concat(TYPES) },
  { key: 'used', label: '是否被单据引用', type: 'select', options: Object.keys(USED).map((k) => ({ value: k, label: USED[k] })) },
  { key: 'band', label: '累计投入', type: 'select', options: Object.keys(PBAND).map((k) => ({ value: k, label: PBAND[k] })) },
  { key: 'sort', label: '排序', type: 'select', options: Object.keys(PSORT).map((k) => ({ value: k, label: PSORT[k] })) }
])

const pChips = computed(() => activeKeys(LP.filters, [
  { key: 'keyword', label: '名称' },
  { key: 'type', label: '类型', text: typeText },
  { key: 'used', label: '引用', text: (v) => USED[v] || v },
  { key: 'band', label: '投入', text: (v) => PBAND[v] || v },
  { key: 'sort', label: '排序', text: (v) => PSORT[v] || v }
]))

/* ---------------- 用户账号 ---------------- */

function filterUser (list, f) {
  if (!f.dept) return list.slice()
  return list.slice().filter((u) => u.deptName === f.dept)
}

const LU = usePagedList(filterUser, { keyword: '', role: '', dept: '' })

const uGroups = computed(() => [
  { key: 'keyword', label: '姓名或登录名', type: 'text', placeholder: '姓名或登录名' },
  { key: 'role', label: '角色', type: 'select', options: ROLE_KEYS.map((k) => ({ value: k, label: roleNames[ROLE_KEYS.indexOf(k)] })) },
  { key: 'dept', label: '部门', type: 'select', options: uniqOptions(LU.all, 'deptName') }
])

const uChips = computed(() => activeKeys(LU.filters, [
  { key: 'keyword', label: '姓名' },
  { key: 'role', label: '角色', text: roleText },
  { key: 'dept', label: '部门' }
]))

const roleOf = (r) => ROLE[r] || r || '—'

/* ---------------- 取数 ---------------- */

const FETCH_SIZE = 100
let pKey = ''
let uKey = ''

async function loadPartners (force) {
  const key = LP.filters.keyword || ''
  if (!force && key === pKey && LP.all.length > 0) return
  loading.value = true
  try {
    const page = await adminPartners({ keyword: key, pageNum: 1, pageSize: FETCH_SIZE })
    LP.setList(page.list || [])
    pKey = key
  } catch (e) { /* toast 已弹 */ }
  loading.value = false
}

async function loadUsers (force) {
  const key = `${LU.filters.keyword || ''}|${LU.filters.role || ''}`
  if (!force && key === uKey && LU.all.length > 0) return
  loading.value = true
  try {
    const page = await adminUsers({
      role: LU.filters.role, keyword: LU.filters.keyword, pageNum: 1, pageSize: FETCH_SIZE
    })
    LU.setList(page.list || [])
    uKey = key
  } catch (e) { /* toast 已弹 */ }
  loading.value = false
}

const switchSeg = (s) => {
  seg.value = s
  if (s === 'partner') loadPartners()
  else loadUsers()
}

function onPartnerFilter (f) {
  LP.filters = f
  pDrawer.value = false
  LP.resetPaging()
  loadPartners()
}
function onUserFilter (f) {
  LU.filters = f
  uDrawer.value = false
  LU.resetPaging()
  loadUsers()
}
function clearChip (which, k) {
  if (which === 'p') {
    const next = { ...LP.filters }
    next[k] = ''
    LP.filters = next
    LP.resetPaging()
    loadPartners()
  } else {
    const next = { ...LU.filters }
    next[k] = ''
    LU.filters = next
    LU.resetPaging()
    loadUsers()
  }
}

/* ---------------- 新增 / 修改 / 删除 ---------------- */

const form = ref(null)
const typeIndex = ref(0)

function onType (e) { typeIndex.value = Number(e.detail.value) }

function openForm (p) {
  form.value = p
    ? { id: p.id, partnerName: p.partnerName, partnerType: p.partnerType, reason: '' }
    : { id: null, partnerName: '', partnerType: 'CUSTOMER', reason: '' }
  typeIndex.value = Math.max(0, TYPES.findIndex((t) => t.value === form.value.partnerType))
}

async function savePartner () {
  const name = (form.value.partnerName || '').trim()
  if (!name) {
    uni.showToast({ title: '请填写对象名称', icon: 'none' })
    return
  }
  const body = {
    partnerName: name,
    partnerType: TYPES[typeIndex.value].value,
    reason: form.value.reason || ''
  }
  try {
    if (form.value.id) await adminUpdatePartner(form.value.id, body)
    else await adminCreatePartner(body)
    uni.showToast({ title: '已保存', icon: 'none' })
    form.value = null
    loadPartners(true)
  } catch (e) { /* toast 已弹 */ }
}

function removePartner (p) {
  if (p.applyCount > 0) {
    uni.showToast({ title: '已被单据引用，删不掉', icon: 'none' })
    return
  }
  uni.showModal({
    title: '删除对象',
    content: `确认删除「${p.partnerName}」？删完留痕里还能看到这条记录`,
    success: async (r) => {
      if (!r.confirm) return
      try {
        await adminDeletePartner(p.id, '移动端删除')
        uni.showToast({ title: '已删除', icon: 'none' })
        loadPartners(true)
      } catch (e) { /* toast 已弹 */ }
    }
  })
}

onShow(() => {
  // 这一页只挂在系统管理员的导航上
  if (!hasTab('admin')) {
    uni.showToast({ title: '基础档案是系统管理员的功能', icon: 'none' })
    uni.redirectTo({ url: '/pages/home/home' })
    return
  }
  if (seg.value === 'partner') loadPartners()
  else loadUsers()
})
</script>

<style>
.tabs { display: flex; background: #fff; border-radius: 16rpx; padding: 8rpx; margin-bottom: 24rpx; }
.tab { flex: 1; text-align: center; font-size: 28rpx; color: #5f6773; padding: 16rpx 0; border-radius: 12rpx; }
.tab.on { background: #2f6fed; color: #fff; }

.fbar { display: flex; align-items: center; flex-wrap: wrap; gap: 16rpx; margin-bottom: 24rpx; }
.fbtn { display: flex; align-items: center; background: #fff; border-radius: 12rpx;
  padding: 14rpx 24rpx; position: relative; }
.fbtn-ic { font-size: 30rpx; color: #2f6fed; margin-right: 10rpx; }
.fbtn-t { font-size: 26rpx; color: #1f2328; }
.fbadge { position: absolute; top: -12rpx; right: -12rpx; min-width: 32rpx; height: 32rpx;
  border-radius: 16rpx; background: #d83931; color: #fff; font-size: 20rpx;
  display: flex; align-items: center; justify-content: center; padding: 0 8rpx; box-sizing: border-box; }
.fchips { display: flex; flex-wrap: wrap; gap: 12rpx; flex: 1; }
.fchip { display: flex; align-items: center; background: #eef3fe; color: #2f6fed;
  border-radius: 999rpx; padding: 8rpx 20rpx; font-size: 22rpx; }
.fchip-x { margin-left: 8rpx; font-size: 26rpx; }
.fcount { font-size: 24rpx; color: #8a919c; }
.add { background: #2f6fed; border-radius: 12rpx; padding: 16rpx 24rpx; }
.add-t { color: #fff; font-size: 26rpx; }

.card { background: #fff; border-radius: 16rpx; padding: 28rpx 32rpx; margin-bottom: 24rpx; }
.row1 { display: flex; justify-content: space-between; align-items: center; }
.name { font-size: 30rpx; color: #1f2328; font-weight: 500; }
.sub { display: block; margin-top: 10rpx; font-size: 26rpx; color: #5f6773; }
.row2 { display: flex; justify-content: space-between; align-items: center; margin-top: 12rpx; }
.ops { display: flex; gap: 28rpx; }
.op { font-size: 26rpx; color: #2f6fed; }
.op-danger { color: #d83931; }
.op-off { color: #c4c9d0; }
.note { display: block; margin-top: 8rpx; font-size: 24rpx; color: #8a919c; }
.pad { padding: 0 8rpx 40rpx; }
.tag { font-size: 22rpx; padding: 4rpx 16rpx; border-radius: 8rpx; }
.tag-blue { background: #e8f1fd; color: #1a56a8; }
.tag-gray { background: #f0f1f3; color: #5f6773; }
.empty { text-align: center; color: #9aa2ad; font-size: 26rpx; padding: 80rpx 0; }

.form { border: 2rpx solid #cddffd; }
.ipt { background: #f4f7fd; border-radius: 12rpx; padding: 18rpx 24rpx; font-size: 28rpx;
  margin-top: 20rpx; color: #1f2328; }
.picker-row { display: flex; align-items: center; justify-content: space-between; margin-top: 20rpx; }
.lbl { font-size: 28rpx; color: #5f6773; }
.pick { font-size: 28rpx; color: #2f6fed; }
.btn-row { display: flex; gap: 20rpx; }
.f1 { flex: 1; }
</style>
