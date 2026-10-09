<template>
  <!--
    菜单本体。PC 侧栏和手机抽屉共用这一份，避免两处各写一遍菜单后走样。
    手机抽屉里点完菜单要自动收起，靠 navigate 事件通知外层。
  -->
  <el-menu :default-active="activePath" router unique-opened @select="emit('navigate')">
    <template v-for="group in menus" :key="group.index">
      <!-- 有子项：可展开的分组 -->
      <el-sub-menu v-if="group.children?.some((c) => !c.hidden)" :index="group.index">
        <template #title>
          <el-icon><component :is="group.icon" /></el-icon>
          <span>{{ group.title }}</span>
        </template>
        <el-menu-item
          v-for="child in group.children.filter((c) => !c.hidden)"
          :key="child.index"
          :index="child.index"
        >
          {{ child.title }}
        </el-menu-item>
      </el-sub-menu>

      <!-- 没子项：顶级菜单项，点一下直接跳页面，不再多一层下拉 -->
      <el-menu-item v-else :index="group.index">
        <el-icon><component :is="group.icon" /></el-icon>
        <span>{{ group.title }}</span>
      </el-menu-item>
    </template>
  </el-menu>
</template>

<script setup>
defineOptions({ name: 'RoleMenu' })

const props = defineProps({
  menus: { type: Array, default: () => [] },
  activePath: { type: String, default: '' }
})

const emit = defineEmits(['navigate'])
</script>
