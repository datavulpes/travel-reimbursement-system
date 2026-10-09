<template>
  <view class="page page-tab">
    <view class="hd">
      <text class="hd-t">差标与部门预算</text>
    </view>

    <view class="rp">
      <SideNav :items="SECTIONS" :current="tab" @switch="tab = $event" />

      <scroll-view class="rp-main" scroll-y>
        <!-- ① 差标维护 -->
        <view v-if="tab === 'standard'">
          <view class="card">
            <view class="row1">
              <text class="sec">差旅标准</text>
              <text class="add" @click="openStdCreate">＋ 新增</text>
            </view>
            <text class="note">按「城市等级 + 交通方式」比对，住宿不进差标。每次调整都要求写原因</text>

            <view v-if="!stdRows.length" class="ph">还没有配置差标</view>
            <view v-for="(r, i) in stdRows" :key="i" class="srow">
              <view class="srow-l">
                <text class="srow-t">{{ r.cityLevel }} · {{ r.trafficType }}</text>
                <text class="srow-s">生效 {{ r.effectiveAt || '—' }} · 最近调整人 {{ r.updatedBy || '—' }}</text>
                <text v-if="r.prevMaxAmount != null" class="srow-s">上一版 ¥ {{ fmtMoney(r.prevMaxAmount) }}</text>
              </view>
              <view class="srow-r">
                <text class="money">¥ {{ fmtMoney(r.maxAmount) }}</text>
                <view class="ops">
                  <text class="op" @click="openStdUpdate(r)">调整</text>
                  <text class="op danger" @click="openDelStd(r)">删除</text>
                </view>
              </view>
            </view>
            <text class="note">调整是插一条新的生效日期行，旧版本留着给旧单据追溯；已经提交过的申请不受影响，差标结论在提交那一刻就写进申请单了</text>
          </view>
        </view>

        <!-- ② 部门预算 -->
        <view v-if="tab === 'budget'">
          <view class="card">
            <view class="row1">
              <text class="sec">部门月度预算</text>
              <text class="add" @click="openDeptCreate">＋ 新增部门</text>
            </view>

            <view v-if="!deptRows.length" class="ph">还没有部门</view>
            <view v-for="(d, i) in deptRows" :key="i" class="brow">
              <view class="row1">
                <text class="brow-t">{{ d.deptName }}</text>
                <text class="money">¥ {{ fmtMoney(d.monthlyBudget) }}</text>
              </view>
              <text class="srow-s">{{ d.memberCount }} 人 · 负责人 {{ d.approverName || '—' }} · 差标档 {{ d.standardLevel || '—' }} · 累计单据 {{ d.applyCount }}</text>
              <text class="srow-s">本月已付 ¥ {{ fmtMoney(d.usedBudget) }}，使用率 {{ d.monthlyBudget > 0 ? pct(d.usedBudget / d.monthlyBudget) : '—' }}</text>
              <view class="bbar">
                <view class="bbar-fill" :class="{ over: overBudget(d) }" :style="{ width: budgetWidth(d) }" />
              </view>
              <view class="ops right">
                <text class="op" @click="openBudget(d)">调整预算</text>
                <text :class="['op', deletable(d) ? 'danger' : 'off']" @click="openDelDept(d)">删除</text>
              </view>
            </view>
            <text class="note">删除部门要求名下没人也没单据，不然会留下指向空部门的脏数据；不满足时后端也会拒</text>
          </view>
        </view>

        <!-- ③ 调整留痕 -->
        <view v-if="tab === 'logs'">
          <view class="card">
            <text class="sec">调整留痕</text>
            <text class="note">谁改的、什么时候改的、前后值是多少。只追加，谁也不能改</text>

            <view class="filters">
              <picker mode="selector" :range="LOG_TYPES" range-key="label" @change="onLogType">
                <view class="fpick">
                  <text class="fpick-t">{{ logTypeLabel }}</text>
                  <text class="fpick-a">▾</text>
                </view>
              </picker>
              <input class="fipt" v-model="logKeyword" placeholder="搜变更项或原因" @confirm="searchLogs" />
              <text class="fbtn" @click="searchLogs">查询</text>
            </view>

            <view v-if="!logRows.length" class="ph">没有符合条件的记录</view>
            <view v-for="(g, i) in logRows" :key="i" class="lrow">
              <view class="row1">
                <text class="lrow-t">{{ g.targetName || g.targetId }}</text>
                <text class="lrow-d">{{ g.operateAt }}</text>
              </view>
              <view class="ltags">
                <text class="tag tag-blue">{{ CONFIG_TYPE[g.type] || g.type }}</text>
                <text :class="['tag', actionCls(g.action)]">{{ CONFIG_ACTION[g.action] || g.action }}</text>
              </view>
              <text class="srow-s">{{ g.before || '—' }} → {{ g.after || '—' }}</text>
              <text class="srow-s">原因：{{ g.reason || '—' }}　操作人 {{ g.operator || '—' }}</text>
            </view>
            <Pager
              v-if="logTotal > 0"
              :total="logTotal" :page-num="logPage" :page-size="logSize"
              @page="onLogPage" @size="onLogSize" />
          </view>
        </view>

        <view class="pad" />
      </scroll-view>
    </view>

    <!-- 表单弹层：差标 / 部门 / 预算 / 删除原因共用一套 -->
    <view v-if="sheet" class="mask" @click="sheet = null">
      <view class="sheet" @click.stop>
        <text class="sheet-t">{{ sheet.title }}</text>

        <template v-if="sheet.kind === 'stdCreate' || sheet.kind === 'stdUpdate'">
          <view v-if="sheet.kind === 'stdCreate'" class="f">
            <text class="f-l">城市等级</text>
            <picker mode="selector" :range="CITY_LEVELS" @change="(e) => form.cityLevel = CITY_LEVELS[e.detail.value]">
              <text class="f-v">{{ form.cityLevel || '请选择' }} ▾</text>
            </picker>
          </view>
          <view v-if="sheet.kind === 'stdCreate'" class="f">
            <text class="f-l">交通方式</text>
            <picker mode="selector" :range="TRAFFIC_TYPES" @change="(e) => form.trafficType = TRAFFIC_TYPES[e.detail.value]">
              <text class="f-v">{{ form.trafficType || '请选择' }} ▾</text>
            </picker>
          </view>
          <view v-else class="f">
            <text class="f-l">适用范围</text>
            <text class="f-v plain">{{ form.cityLevel }} · {{ form.trafficType }}（当前 ¥ {{ fmtMoney(form.originAmount) }}）</text>
          </view>
          <view class="f">
            <text class="f-l">上限金额</text>
            <input class="f-i" v-model="form.maxAmount" type="digit" placeholder="0.00" />
          </view>
          <view class="f">
            <text class="f-l">生效日期</text>
            <picker mode="date" :value="form.effectiveAt" @change="(e) => form.effectiveAt = e.detail.value">
              <text class="f-v">{{ form.effectiveAt || '请选择' }} ▾</text>
            </picker>
          </view>
        </template>

        <template v-if="sheet.kind === 'deptCreate'">
          <view class="f">
            <text class="f-l">部门名称</text>
            <input class="f-i" v-model="form.deptName" placeholder="与组织架构保持一致，不能重名" />
          </view>
          <view class="f">
            <text class="f-l">月度预算</text>
            <input class="f-i" v-model="form.monthlyBudget" type="digit" placeholder="0.00" />
          </view>
          <view class="f">
            <text class="f-l">差标等级</text>
            <picker mode="selector" :range="CITY_LEVELS" @change="(e) => form.standardLevel = CITY_LEVELS[e.detail.value]">
              <text class="f-v">{{ form.standardLevel || '请选择' }} ▾</text>
            </picker>
          </view>
        </template>

        <template v-if="sheet.kind === 'budgetUpdate'">
          <view class="f">
            <text class="f-l">部门</text>
            <text class="f-v plain">{{ form.deptName }}（当前 ¥ {{ fmtMoney(form.originBudget) }}）</text>
          </view>
          <view class="f">
            <text class="f-l">月度预算</text>
            <input class="f-i" v-model="form.monthlyBudget" type="digit" placeholder="0.00" />
          </view>
        </template>

        <view class="f col">
          <text class="f-l">{{ sheet.kind.indexOf('del') === 0 ? '原因' : '调整原因' }}</text>
          <textarea class="f-t" v-model="form.reason" :placeholder="sheet.ph" />
        </view>

        <text v-if="sheet.tip" class="sheet-tip">{{ sheet.tip }}</text>

        <view class="btn-row">
          <text class="btn btn-ghost blk" @click="sheet = null">取消</text>
          <text :class="['btn', 'blk', sheet.kind.indexOf('del') === 0 ? 'btn-danger-solid' : '']" @click="submit">
            {{ sheet.kind.indexOf('del') === 0 ? '确认删除' : '保存' }}
          </text>
        </view>
      </view>
    </view>

    <TabBar current="report" />
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import {
  standards, createStandard, updateStandard, deleteStandard,
  depts, createDept, updateBudget, deleteDept, configLogs, analyticsMonthly
} from '../../api'
import { fmtMoney, CITY_LEVELS, TRAFFIC_TYPES, CONFIG_TYPE, CONFIG_ACTION } from '../../utils/dict'
import SideNav from '../../components/SideNav.vue'
import Pager from '../../components/Pager.vue'
import TabBar from '../../components/TabBar.vue'
import { hasTab } from '../../utils/tabs'

const SECTIONS = [
  { key: 'standard', text: '差标维护', ic: '标' },
  { key: 'budget', text: '部门预算', ic: '预' },
  { key: 'logs', text: '调整留痕', ic: '痕' }
]
const tab = ref('standard')

const stdRows = ref([])
const deptRows = ref([])

/* ---------------- 留痕 ---------------- */

const logRows = ref([])
const logTotal = ref(0)
const logPage = ref(1)
const logSize = ref(10)
const logType = ref('')
const logKeyword = ref('')
const LOG_TYPES = [
  { value: '', label: '全部类型' },
  { value: 'STANDARD', label: '差标' },
  { value: 'BUDGET', label: '部门预算' },
  { value: 'DEPT', label: '部门' }
]
const logTypeLabel = computed(() => {
  const hit = LOG_TYPES.find((t) => t.value === logType.value)
  return hit ? hit.label : '全部类型'
})

const pct = (v) => (v == null ? '—' : (Number(v) * 100).toFixed(1) + '%')

/* ---------------- 表单弹层 ---------------- */

const sheet = ref(null)
const form = ref({})

function today () {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function openStdCreate () {
  form.value = {
    cityLevel: CITY_LEVELS[0], trafficType: TRAFFIC_TYPES[0],
    maxAmount: '', effectiveAt: today(), reason: '', originAmount: 0
  }
  sheet.value = { kind: 'stdCreate', title: '新增差标', ph: '例如：二线城市住宿价格上涨', tip: '这个组合从来没配过标准时才用它；配过了请走「调整」' }
}

function openStdUpdate (r) {
  form.value = {
    id: r.id, cityLevel: r.cityLevel, trafficType: r.trafficType,
    maxAmount: String(r.maxAmount), originAmount: r.maxAmount,
    effectiveAt: today(), reason: ''
  }
  sheet.value = { kind: 'stdUpdate', title: '调整差标', ph: '例如：高铁票涨价，上限上调', tip: '同城市等级 + 同类别 + 同一天只能有一条，改的是新的一版' }
}

function openDelStd (r) {
  form.value = { id: r.id, reason: '' }
  sheet.value = {
    kind: 'delStd', title: `删除差标 ${r.cityLevel}-${r.trafficType}`,
    ph: '删除原因，写进调整留痕',
    tip: '只能删该组最新的一档，历史版本留着给旧单据追溯'
  }
}

function openDeptCreate () {
  form.value = { deptName: '', monthlyBudget: '', standardLevel: '二级', reason: '' }
  sheet.value = { kind: 'deptCreate', title: '新增部门', ph: '例如：新设大客户部，单独核算差旅', tip: '部门审批人先留空，系统会指派一名现有审批人' }
}

function openBudget (d) {
  form.value = { deptId: d.deptId, deptName: d.deptName, monthlyBudget: String(d.monthlyBudget), originBudget: d.monthlyBudget, reason: '' }
  sheet.value = { kind: 'budgetUpdate', title: '调整部门预算', ph: '例如：三季度销售部出差计划增加' }
}

function openDelDept (d) {
  if (!deletable(d)) {
    uni.showToast({
      title: d.memberCount ? `该部门下还有 ${d.memberCount} 名员工，先把人调走` : `名下有 ${d.applyCount} 张申请单，不能删`,
      icon: 'none'
    })
    return
  }
  form.value = { deptId: d.deptId, reason: '' }
  sheet.value = {
    kind: 'delDept', title: `删除部门 ${d.deptName}`,
    ph: '删除原因，写进调整留痕',
    tip: '月度预算一并失效，留痕里仍保留记录'
  }
}

async function submit () {
  const f = form.value
  const kind = sheet.value.kind
  if (!f.reason || !String(f.reason).trim()) {
    uni.showToast({ title: '请填写原因', icon: 'none' })
    return
  }
  if (kind === 'stdCreate' || kind === 'stdUpdate') {
    if (!(Number(f.maxAmount) > 0)) {
      uni.showToast({ title: '上限金额必须大于 0', icon: 'none' })
      return
    }
  }
  if (kind === 'deptCreate') {
    if (!f.deptName) return uni.showToast({ title: '请填写部门名称', icon: 'none' })
    if (!(Number(f.monthlyBudget) > 0)) return uni.showToast({ title: '月度预算必须大于 0', icon: 'none' })
  }
  if (kind === 'budgetUpdate' && !(Number(f.monthlyBudget) > 0)) {
    return uni.showToast({ title: '月度预算必须大于 0', icon: 'none' })
  }

  uni.showLoading({ title: '提交中' })
  try {
    if (kind === 'stdCreate') {
      await createStandard({
        cityLevel: f.cityLevel, trafficType: f.trafficType,
        maxAmount: Number(f.maxAmount), effectiveAt: f.effectiveAt, reason: f.reason
      })
    } else if (kind === 'stdUpdate') {
      await updateStandard({
        cityLevel: f.cityLevel, trafficType: f.trafficType,
        maxAmount: Number(f.maxAmount), effectiveAt: f.effectiveAt, reason: f.reason
      })
    } else if (kind === 'delStd') {
      await deleteStandard(f.id, f.reason)
    } else if (kind === 'deptCreate') {
      await createDept({
        deptName: f.deptName, monthlyBudget: Number(f.monthlyBudget),
        standardLevel: f.standardLevel, reason: f.reason
      })
    } else if (kind === 'budgetUpdate') {
      await updateBudget({ deptId: f.deptId, monthlyBudget: Number(f.monthlyBudget), reason: f.reason })
    } else if (kind === 'delDept') {
      await deleteDept(f.deptId, f.reason)
    }
    sheet.value = null
    // 任何一处改完，两份列表和留痕都要一起刷新，否则留痕比数据慢一拍
    await Promise.all([loadStd(), loadDepts()])
    if (tab.value === 'logs') loadLogs()
  } catch (e) { /* request.js 已经弹了后端的人话 */ }
  uni.hideLoading()
}

/* ---------------- 取数 ---------------- */

async function loadStd () {
  try { stdRows.value = (await standards()) || [] } catch (e) { /* toast 已弹 */ }
}

async function loadDepts () {
  try {
    const list = (await depts()) || []
    // 已用额度 = 本月各部门实际付款额，dept 表没有这一列，跟 Web 端同一个口径
    let paid = {}
    try {
      const rep = await analyticsMonthly(nowMonth())
      paid = Object.fromEntries(((rep && rep.rows) || []).map((r) => [r.deptId, Number(r.totalPay) || 0]))
    } catch (e) { /* 月报挂了不影响预算列表，已用按 0 显示 */ }
    deptRows.value = list.map((d) => ({ ...d, usedBudget: paid[d.deptId] || 0 }))
  } catch (e) { /* toast 已弹 */ }
}

function nowMonth () {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

async function loadLogs () {
  try {
    const params = { pageNum: logPage.value, pageSize: logSize.value }
    if (logType.value) params.type = logType.value
    if (logKeyword.value) params.keyword = logKeyword.value
    const data = await configLogs(params)
    logRows.value = (data && data.list) || []
    logTotal.value = (data && data.total) || 0
  } catch (e) { /* toast 已弹 */ }
}

function searchLogs () {
  logPage.value = 1
  loadLogs()
}
function onLogPage (n) { logPage.value = n; loadLogs() }
function onLogSize (n) { logSize.value = n; logPage.value = 1; loadLogs() }
function onLogType (e) { logType.value = LOG_TYPES[e.detail.value].value; searchLogs() }

function budgetWidth (d) {
  if (!(Number(d.monthlyBudget) > 0)) return '0%'
  const p = (Number(d.usedBudget) || 0) / Number(d.monthlyBudget) * 100
  return Math.min(p, 100).toFixed(1) + '%'
}
const overBudget = (d) => Number(d.monthlyBudget) > 0 && (Number(d.usedBudget) || 0) / Number(d.monthlyBudget) > 0.9
// 名下有人或有单据的部门后端也会拒，这里先把按钮置灰，省得让用户白点一次
const deletable = (d) => !d.memberCount && !d.applyCount
const actionCls = (a) => (a === 'INSERT' ? 'tag-green' : (a === 'UPDATE' ? 'tag-amber' : 'tag-red'))

onLoad((o) => {
  // 差标与预算是管理层的功能（/config 整体限管理层），别的角色不给进
  if (!hasTab('report')) {
    uni.showToast({ title: '当前角色没有这个功能', icon: 'none' })
    uni.redirectTo({ url: '/pages/home/home' })
    return
  }
  if (o && o.tab) tab.value = o.tab
  loadStd()
  loadDepts()
  if (tab.value === 'logs') loadLogs()
})

// 从概览页切进来时带 tab 参数，这里只在已经加载过一次后补拉对应数据
onShow(() => { if (tab.value === 'logs' && !logRows.value.length) loadLogs() })
</script>

<style>
.hd { display: flex; justify-content: space-between; align-items: center;
  height: 76rpx; margin-bottom: 16rpx; }
.hd-t { font-size: 34rpx; font-weight: 600; color: #1f2328; }
.rp { display: flex; gap: 16rpx; height: calc(100vh - 300rpx); }
.rp-main { flex: 1; height: 100%; }
.pad { height: 40rpx; }

.add { font-size: 25rpx; color: #2f6fed; }
.note { display: block; margin-top: 8rpx; font-size: 22rpx; color: #8a919c; }
.ph { padding: 28rpx 0; text-align: center; font-size: 24rpx; color: #9aa2ad; }

.srow { display: flex; justify-content: space-between; align-items: flex-start;
  padding: 20rpx 0; border-bottom: 1rpx solid #f0f1f3; }
.srow-l { flex: 1; }
.srow-t { font-size: 28rpx; color: #1f2328; }
.srow-s { display: block; font-size: 22rpx; color: #8a919c; margin-top: 4rpx; }
.srow-r { flex: none; margin-left: 20rpx; text-align: right; }
.ops { display: flex; justify-content: flex-end; gap: 24rpx; margin-top: 10rpx; }
.ops.right { justify-content: flex-end; }
.op { font-size: 25rpx; color: #2f6fed; }
.op.danger { color: #d83931; }
.op.off { color: #c4c9d0; }

.brow { padding: 20rpx 0; border-bottom: 1rpx solid #f0f1f3; }
.brow-t { font-size: 28rpx; color: #1f2328; }
.bbar { height: 10rpx; background: #f0f1f3; border-radius: 5rpx; overflow: hidden; margin-top: 12rpx; }
.bbar-fill { height: 100%; background: #2f6fed; opacity: .55; }
.bbar-fill.over { background: #d83931; opacity: .8; }

.filters { display: flex; align-items: center; gap: 12rpx; margin: 16rpx 0 8rpx; }
.fpick { display: flex; align-items: center; background: #eef3fe; border-radius: 10rpx;
  padding: 12rpx 16rpx; }
.fpick-t { font-size: 24rpx; color: #2f6fed; }
.fpick-a { font-size: 20rpx; color: #2f6fed; margin-left: 8rpx; }
.fipt { flex: 1; background: #f5f7fa; border-radius: 10rpx; padding: 12rpx 16rpx;
  font-size: 24rpx; }
.fbtn { flex: none; background: #2f6fed; color: #fff; border-radius: 10rpx;
  padding: 12rpx 24rpx; font-size: 24rpx; }

.lrow { padding: 18rpx 0; border-bottom: 1rpx solid #f0f1f3; }
.lrow-t { font-size: 27rpx; color: #1f2328; }
.lrow-d { font-size: 22rpx; color: #8a919c; }
.ltags { display: flex; gap: 12rpx; margin: 10rpx 0; }

/* 弹层 */
.mask { position: fixed; left: 0; right: 0; top: 0; bottom: 0; z-index: 200;
  background: rgba(0, 0, 0, .45); display: flex; align-items: flex-end; }
.sheet { width: 100%; background: #fff; border-radius: 24rpx 24rpx 0 0;
  padding: 32rpx 32rpx 40rpx; box-sizing: border-box; max-height: 80vh; overflow-y: auto; }
.sheet-t { display: block; font-size: 30rpx; font-weight: 600; color: #1f2328; margin-bottom: 8rpx; }
.sheet-tip { display: block; font-size: 22rpx; color: #8a919c; margin-top: 12rpx; }

.f { display: flex; justify-content: space-between; align-items: center;
  min-height: 88rpx; border-bottom: 1rpx solid #f0f1f3; }
.f.col { flex-direction: column; align-items: stretch; }
.f-l { font-size: 27rpx; color: #5f6773; flex: none; }
.f.col .f-l { margin: 16rpx 0 8rpx; }
.f-v { font-size: 27rpx; color: #2f6fed; }
.f-v.plain { color: #5f6773; font-size: 25rpx; }
.f-i { flex: 1; text-align: right; font-size: 27rpx; color: #1f2328; padding: 10rpx 0; }
.f-t { height: 140rpx; background: #f5f7fa; border-radius: 12rpx; padding: 16rpx;
  font-size: 26rpx; box-sizing: border-box; width: 100%; }

.blk { display: block; }
.btn-danger-solid { background: #d83931; color: #fff; }
</style>
