<template>
  <div class="app-shell">
    <!--
      手机抽屉：窄屏触屏设备上，侧栏换成从左侧滑出的抽屉。
      菜单本体跟 PC 侧栏共用 RoleMenu，两处不会各写一遍后走样。
      用 v-if 而不是 CSS 隐藏：PC 上这段 DOM 根本不生成，桌面端不受任何影响。
    -->
    <el-drawer
      v-if="isMobile"
      v-model="menuOpen"
      direction="ltr"
      size="78%"
      :with-header="false"
      class="mobile-menu-drawer"
    >
      <div class="mm-head" :style="{ '--role-color': meta.color }">
        <div class="brand">
          <div class="brand-text">
            <div class="brand-title">企业差旅管理</div>
            <div class="brand-sub">费用报销系统</div>
          </div>
        </div>
        <div class="role-chip" :style="{ background: meta.soft, color: meta.color }">
          <el-icon><User /></el-icon>
          <span>{{ meta.label }}端口</span>
        </div>
      </div>
      <RoleMenu :menus="menus" :active-path="activePath" @navigate="menuOpen = false" />
    </el-drawer>

    <!-- 侧栏：只渲染当前角色名下的菜单（PC 端形态，手机端由上面的抽屉承担） -->
    <aside v-if="!isMobile" class="app-aside" :style="{ '--role-color': meta.color }">
      <div class="brand">
        <div class="brand-text">
          <div class="brand-title">企业差旅管理</div>
          <div class="brand-sub">费用报销系统</div>
        </div>
      </div>

      <div class="role-chip" :style="{ background: meta.soft, color: meta.color }">
        <el-icon><User /></el-icon>
        <span>{{ meta.label }}端口</span>
      </div>

      <RoleMenu :menus="menus" :active-path="activePath" />

      <div class="aside-foot">
        <div class="mono weak">v1.0.0 · 原型演示</div>
      </div>
    </aside>

    <!-- 右侧主体 -->
    <div class="app-main">
      <header class="app-header">
        <div class="hd-left">
          <!-- 汉堡按钮：只在手机抽屉形态下出现，PC 端不渲染这个节点 -->
          <el-button
            v-if="isMobile"
            class="mm-btn"
            link
            @click="menuOpen = true"
            aria-label="打开菜单"
          >
            <el-icon :size="20"><Menu /></el-icon>
          </el-button>
          <h2>{{ pageTitle }}</h2>
        </div>
        <div class="hd-right">
          <el-tag :style="{ background: meta.soft, color: meta.color, borderColor: meta.soft }" effect="light">
            {{ meta.label }}
          </el-tag>
          <span class="user-name">{{ user.realName }} · {{ user.deptName }}</span>
          <el-button link type="primary" @click="handleLogout">
            <el-icon style="margin-right: 4px"><SwitchButton /></el-icon>退出
          </el-button>
        </div>
      </header>

      <section class="app-body">
        <!--
          来回切页面时，人已经填进去的东西要还在。
          套一层 keep-alive，组件就不再跟着路由销毁重建，表单状态自然留在内存里，
          各页面不必各建一套「把数据搬出去」的机制。

          include 白名单按端口分组登记（见下面的 KEEP_ALIVE）：
          列表页回来刷新数据、留着筛选与页码；详情页按地址栏的单号开内容，
          必须自己盯参数、换了单就重载并清表单，否则会拿上一张单的意见去批下一张。
        -->
        <router-view v-slot="{ Component }">
          <keep-alive :include="KEEP_ALIVE">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </section>
    </div>

    <!--
      AI 助手悬浮球 + 右侧面板：只在员工端那四个「填东西」的页面出现。
      助手页自己（/employee/policy）不挂——那里整页就是助手，再挂个球是重复入口。
    -->
    <template v-if="assistVisible">
      <AiAssistBall @open="assistOpen = true" />
      <AiAssistPanel v-model:visible="assistOpen" :page-key="assistPageKey" />
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { MENUS, ROLE_META } from '@/config/menus'
import RoleMenu from '@/layouts/RoleMenu.vue'
import AiAssistBall from '@/components/AiAssistBall.vue'
import AiAssistPanel from '@/components/AiAssistPanel.vue'
import { useIsMobile } from '@/utils/breakpoint'

const isMobile = useIsMobile()

/** 手机抽屉的开合；PC 端这个值一直是 false，没有任何作用 */
const menuOpen = ref(false)

/**
 * 走 keep-alive 的页面，按端口分组登记。keep-alive 是按组件名匹配的，
 * 所以这些页面都用 defineOptions 登记了同名 name，改名要两处一起改。
 *
 * 分两类，改之前先想清楚是哪种：
 * - 列表页 / 表单页：回来刷新数据，筛选、页码、填了一半的内容留在页面上。
 * - 详情页（ApvDetail / FinDetail / MgtDrill）：按地址栏的单号开内容，
 *   缓存住就会停在上一次那张单上，所以它们必须自己盯参数，换了单就重载并清掉表单。
 */
const KEEP_ALIVE = [
  // 员工端
  'EmpApplyCreate', 'EmpApplyList', 'EmpInvoiceUpload', 'EmpExpenseSubmit', 'EmpPolicyQa',
  // 审批端
  'ApvTodo', 'ApvDetail',
  // 财务端
  'FinList', 'FinDetail',
  // 管理层
  'MgtReport', 'MgtDrill', 'MgtPartner', 'MgrDataQa', 'MgtStandard',
  // 系统管理员
  'AdmPartner', 'AdmUser', 'AdmDept', 'AdmBrowse', 'AdmAiStatus'
]

const route = useRoute()
const router = useRouter()
const user = useUserStore()

const menus = computed(() => MENUS[user.role] || [])
const meta = computed(() => ROLE_META[user.role] || { label: '未知', color: '#666', soft: '#eee' })
const activePath = computed(() => route.path)

/**
 * 挂 AI 助手悬浮球的页面。键必须与后端 AiPanelState.PAGE_* 逐字一致——
 * 写错了不会报错，只会存到一个谁也找不到的键上，表现为「退出了却恢复不出来」。
 */
const AI_PAGES = {
  empApply: 'empApply',
  empList: 'empList',
  empInvoice: 'empInvoice',
  empExpense: 'empExpense'
}

const assistPageKey = computed(() => AI_PAGES[route.name] || null)
const assistVisible = computed(() => user.role === 'EMPLOYEE' && !!assistPageKey.value)
const assistOpen = ref(false)

/**
 * 页头大标题优先用菜单里登记的名字，而不是路由的 meta.title。
 * 因为 /approver/todo 这条路由两个角色共用，meta.title 只有一份「待我审批」，
 * 管理层进来页头会写成属于别人的叫法；菜单里各自写着「待我复审」，以那个为准。
 */
const pageTitle = computed(() => {
  const hit = menus.value
    .flatMap((group) => group.children || [])
    .find((item) => item.index === route.path)
  return hit?.title || route.meta?.title || '首页'
})

onMounted(() => {
  if (user.isLogin && !user.info?.username) {
    user.refreshMe()
  }
})

async function handleLogout () {
  await ElMessageBox.confirm('确认退出当前账号？', '提示', { type: 'warning' })
  await user.doLogout()
  router.push('/login')
}
</script>

<style scoped>
.app-shell {
  display: flex;
  height: 100%;
  min-height: 100vh;
}

.app-aside {
  width: 232px;
  flex: 0 0 232px;
  background: #fff;
  border-right: 1px solid var(--c-border);
  display: flex;
  flex-direction: column;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 18px 12px;
}

.brand-title { font-size: 14.5px; font-weight: 650; letter-spacing: .5px; }
.brand-sub { font-size: 11.5px; color: var(--c-text-weak); }

.role-chip {
  margin: 0 14px 10px;
  padding: 7px 12px;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
}

.aside-foot {
  margin-top: auto;
  padding: 12px 16px;
  border-top: 1px solid var(--c-border);
  font-size: 11.5px;
}

.app-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.app-header {
  height: 58px;
  background: #fff;
  border-bottom: 1px solid var(--c-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 22px;
  gap: 16px;
}

.hd-left { display: flex; align-items: baseline; gap: 12px; min-width: 0; }
.hd-left h2 { margin: 0; font-size: 16px; font-weight: 650; white-space: nowrap; }
.hd-left span { font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.hd-right { display: flex; align-items: center; gap: 12px; }
.user-name { font-size: 13px; color: var(--c-text-sub); }

.app-body {
  flex: 1;
  overflow: auto;
  padding: 0;
}

/* 手机抽屉里那几个节点（PC 端不渲染，所以这些样式在桌面端不起作用） */
.mm-btn {
  padding: 0 6px 0 0;
  height: 40px;
}

.mm-head {
  padding: 16px 14px 12px;
  border-bottom: 1px solid var(--c-border);
}

.mm-head .brand {
  padding: 0 0 12px;
}
</style>
