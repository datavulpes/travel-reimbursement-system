# 企业差旅管理工具 · 移动端（uni-app 微信小程序）

跟 Web 版共用同一个后端（`travel-server`，8080 端口），小程序端覆盖**五个角色的全部接口**：

- 员工：填单（多段行程 + AI 建议 + 同类花费参考）、拍照传票 OCR、报销、成果回填
- 部门主管：待我审批（参考值、差标比对、评价、AI 风险）
- 财务：四维校验、可疑票据分析、复核、付款
- 管理层：复审、经营概览、部门与对象投入产出、月度洞察、差标与预算维护
- 系统管理员：基础档案（往来对象增删改、用户账号）

选 uni-app 是因为主项目是 Vue 3，语法直接复用；同一套代码以后还能编到 App 端。

> **完整步骤看《运行操作手册.md》**：从起后端 → 装开发者工具 → 导入 → 模拟器走通全流程，含排错表、五个角色账号、手机真机演示、体验版说明，以及面试三分钟演示脚本。
> 下面这份是速查版。

---

## 跑起来（四步）

```
1. 起后端：travel-server 跑在 8080（先按 v1.0 根目录 README 的第三节恢复数据库）
2. 装依赖：
   cd 小程序端
   npm install
3. 起编译（会生成 dist/dev/mp-weixin）：
   npm run dev:mp-weixin
4. 微信开发者工具「导入项目」，目录选：
   小程序端/dist/dev/mp-weixin
```

不想装 node、或只是想先看一眼界面，跳过 2、3 步，直接导入 `dist/build/mp-weixin`（打包好的生产产物，仓库里已经带着）。

**必勾**：开发者工具「详情 → 本地设置 → 不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」。
开发期后端是 `http://<IP>:8080`，不是 https 合法域名，不勾这条所有请求都会失败。

### 没有 AppID 也能预览，但有个坑

uni 在 `manifest.json` 的 `mp-weixin.appid` 为空时，会把 `project.config.json` 的 appid 写成 **`touristappid`（游客模式）**——游客模式下「预览」和「真机调试」都没反应。

```
开发者工具右上角登录微信 →「详情 → 基本信息 → AppID → 修改 → 测试号」
拿到 wx 开头的 AppID 后，务必回填到 src/manifest.json 的 mp-weixin.appid 再重新编译
```

不回填的话，下次编译又被覆盖回 `touristappid`。测试号免费、扫码即得，详见手册 8.5。

### 后端地址怎么改

别人的机器上跑，后端 IP 基本都不一样，两条路任选（**不用改代码**更好）：

- 小程序里「我的 → 后端地址」，填 `http://<电脑IP>:8080/api`，末尾 `/api` 不能少
- 或者改 `src/utils/request.js` 顶部的 `DEFAULT_BASE`，然后重新编译

登录页底部会显示当前连的地址，扫一眼就知道连的是不是这台电脑。

### dist 里带了什么

仓库里**只带了 `dist/build/mp-weixin`**（生产产物，JS 已压缩、开了组件按需注入）。不想装 node 或只想先看一眼界面，直接用开发者工具导入它就行，改代码后要重新 `npm run build:mp-weixin`。

`dist/dev` 是开发产物，**不会出现在仓库里**——跑起 `npm run dev:mp-weixin` 之后自动生成，平时开发认这个目录。

---

## 页面（16 个）

### 底部导航按角色给不同的功能区

原生 tabBar 的项数是写死的，做不到「角色不同导航不同」，所以换成自研 `components/TabBar.vue`，项由 `utils/tabs.js` 给：

| 角色 | 导航 |
|---|---|
| 员工 | 工作台 · 申请单 · 报销 · 我的 |
| 部门主管 | 工作台 · 审批 · 我的 |
| 财务 | 工作台 · 财务 · 我的 |
| 管理层 | 工作台 · 复审 · 概览 · 我的 |
| 系统管理员 | 工作台 · 档案 · 我的 |

权限判定只有一处：`session.js` 的 `hasTab(key)`，导航渲染、页面守卫、首页「全部功能」列表三处共用，所以首页列的永远就是你导航里有的。手输路径闯别人的页面会被拦回工作台。

| 页面 | 作用 | 主要接口 |
|---|---|---|
| `login` | 登录（含五个角色的演示账号快填卡） | `POST /system/login` |
| `home` | 工作台：待你处理 → 概览数字 → 全部功能入口 | `/apply/page`、`/expense/page`、`/approval/todo`、`/finance/pending`、`/finance/payable`、`/analytics/monthly` |
| `list` | 我的申请单（筛选抽屉 + 分页） | `GET /apply/page` |
| `form` | 新建 / 改后重提（多段行程、AI 建议、同类参考） | `/apply/draft`、`/apply/submit`、`PUT /apply/{no}`、`/apply/advice`、`/apply/cost-ref` |
| `detail` | 申请单详情：票据拍照确认挂接、成果回填、结案、评价、预借款 | `/apply/{no}`、`/invoice/*`、`/eval/{no}`、`/loan/{no}` |
| `expense/list` | 我的报销单（筛选抽屉 + 分页） | `GET /expense/page` |
| `expense/form` | 预检 → 强制说明 → 提交 | `/expense/preview`、`/expense/submit` |
| `expense/detail` | 报销单详情（票据、冲抵、问题） | `GET /expense/{no}` |
| `approval/todo` | 待我审批 / 已办（筛选抽屉 + 分页） | `GET /approval/todo` |
| `approval/detail` | 差标比对、参考值、评价、AI 风险、通过 / 驳回 | `/approval/{no}`、`/approval/{no}/risk`、`/decision` |
| `finance/list` | 待复核 / 待付款（筛选抽屉 + 分页） | `/finance/pending`、`/finance/payable` |
| `finance/detail` | 四维校验、可疑分析、复核、付款 | `/finance/{no}/*` |
| `report` | 经营概览（**左侧八区块导航 + 两张手写图表**）、月度洞察 | `/analytics/*` |
| `standard` | 差标与部门预算（**可增删改**）、调整留痕 | `/config/*` |
| `admin/data` | 基础档案：往来对象增删改、用户账号只读 | `/admin/partners`、`/admin/users` |
| `mine` | 个人信息、后端地址、退出登录 | `/system/me`、`/system/logout` |

### 列表交互：筛选 + 分页是标配

两个组件做一次、五个列表页复用：

- `FilterDrawer.vue` —— 左侧筛选抽屉。后端接口只认 `status` 和 `keyword`，这两个真发请求；对象 / 金额档位 / 日期 / 排序等都在已取回的数据里筛。**没有让后端分页**——否则会出现「第 2 页筛完只剩 3 条」，条数和页码对不上。翻页不发请求，改后端条件才重拉。
- `Pager.vue` —— 底部分页条，默认 10 条/页，可切 20 / 50 / 100。「共 N 条」是**筛选后**的条数。

分页与筛选的公共逻辑抽在 `utils/paged.js`（返回 `reactive` 对象——装 ref 的普通对象在模板里不解包，档案页要同时开两份就没法写）。

### 概览页的两个图表

小程序里没挂 echarts，两张图都是手写的：`ChartBars`（按「值 ÷ 最大值」撑高度）、`ChartLine`（两点之间一根旋转细条）。折线画布宽度必须写死成 **rpx 常量**，用百分比算出来的角度会歪。

左侧八区块：总览 / 部门 / 出差类型 / 产出构成 / 趋势 / 往来对象 / 月度洞察 / 差标预算，进页面默认落在第一块，趋势和差标只在切到那一块时才请求。

---

## 真机演示

```
① 手机和电脑连同一个 WiFi，ipconfig 看电脑的局域网 IPv4
② 放行防火墙（管理员身份跑一次）：
   netsh advfirewall firewall add rule name="travel-server 8080" dir=in action=allow protocol=TCP localport=8080
③ 手机浏览器打开 http://<电脑IP>:8080/api/config/depts
   能返回一串 JSON（哪怕内容是「未授权」）就说明通了
④ 小程序「我的 → 后端地址」填 http://<电脑IP>:8080/api
⑤ 开发者工具点「预览」扫码；手机上第一次要开一次调试：
   小程序右上角 ··· → 开发调试 → 打开 → 自动重启
```

不开「开发调试」，手机会拒绝访问 http 的局域网地址。二维码 25 分钟过期，过期再点一次「预览」。

**关于体验版**：体验版不能勾「不校验合法域名」，request 合法域名必须 https + 已 ICP 备案 + 无端口（内网穿透的免费域名备不了案，走不通）。要有域名 + 云主机才可行，详见手册 8.6。

---

## 演示账号

密码统一 `123456`，登录页有快填卡，点一下自动填：

| 账号 | 角色 | 能看到 |
|---|---|---|
| `wengshaogang` | 员工（销售部） | 申请单 14 条、报销单 12 条、AI 填写建议 |
| `daiqinghao` | 部门主管 | 待我审批 3 条 |
| `wusiting` | 财务 | 待复核 / 待付款、票据影像 |
| `manager` | 管理层 | 待我复审、经营概览、差标与预算维护 |
| `sysadmin` | 系统管理员 | 基础档案（往来对象 5 个、用户账号 9 个） |

演示路线：员工建单 → 主管审批 → 管理层复审 → 财务复核付款 → 管理层看概览。

---

## 业务约定（与 Web 版同源，别改值）

- 出差类型六值：验货 / 商务拜访 / 项目交付 / 供应商审核 / 展会 / 其他——它是同类花费参考的匹配键，写错名字参考永远匹配不上且不报错。
- 交通方式：高铁 / 飞机 / 自驾 / 长途汽车 / 轮船（**没有「住宿」**，住宿单独走预估住宿费）。
- **票据只挂在已通过 / 已结案的单子上**：后端按单据状态拦。上传是两步——`upload` 存影像跑 OCR，`confirm` 才写库。
- 统一返回体 `R{code,msg,data}`：code=0 才算成功，其余直接弹 msg。
- 登录页的五个演示角色快填卡（`DEMO_ROLES`）是给演示用的，上线前要删——源码里打了 `[演示]` 标记，搜这个关键词一次删净。

---

## 目录

```
src/
  api/index.js        接口封装（按后端 Controller 分组）
  utils/request.js    uni.request 封装 + token + 后端地址读写
  utils/dict.js       枚举值域（与 Web 版 dict.js 逐字一致）
  utils/session.js    当前用户、角色判断、hasTab 权限判定
  utils/tabs.js       各角色的底部导航项
  utils/paged.js      筛选 + 分页公共逻辑
  components/TabBar.vue        底部导航（替代原生 tabBar，按角色渲染）
  components/FilterDrawer.vue  左侧筛选抽屉
  components/Pager.vue         底部分页条
  components/SideNav.vue       概览页左侧区块导航
  components/ChartBars.vue     柱状图（纯 view 拼）
  components/ChartLine.vue     折线图（旋转细条）
  static/tab/         tabBar 图标（Pillow 生成的线性 PNG，81x81）
  pages/              16 个页面，见上表

dist/
  build/mp-weixin     生产产物（JS 已压缩，上传 / 不想装 node 时直接导入）
```
