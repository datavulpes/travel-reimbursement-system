# 企业差旅管理与费用报销系统 · 前端

Vue 3 + Vite 5 + Element Plus 全角色端口工程，四个角色登录后只看到自己的功能区。

## 一、目录结构

```
前端/
├── index.html
├── package.json
├── vite.config.js
├── .env.development         开发：默认走 Mock
├── .env.production          生产：默认连真实后端
├── deploy/nginx.conf        nginx 托管配置
└── src/
    ├── main.js              入口（注册 Element Plus 与图标）
    ├── App.vue
    ├── router/index.js      路由 + 角色守卫
    ├── stores/user.js       登录态、角色、落地页
    ├── layouts/RoleLayout.vue   角色布局：侧栏只渲染本角色菜单
    ├── config/menus.js      四个角色菜单配置 + 演示账号
    ├── utils/dict.js        全系统枚举中文口径
    ├── styles/index.css     主题变量与通用样式
    ├── api/
    │   ├── request.js       axios 封装：token、错误码、401 处理
    │   └── index.js         35 个接口（与 openapi3.json 一一对应）
    ├── mock/                演示数据（后端就绪后可删）
    └── views/
        ├── Login.vue            登录页
        ├── employee/            员工：填申请 / 我的申请 / 票据 / 报销
        ├── approver/            审批：待办 / 详情
        ├── finance/             财务：待复核 / 四维比对 + 付款
        └── manager/             管理层：月报 / 钻取 / 对象对照 / 差标预算
```

## 二、跑起来

```bash
npm install
npm run dev          # http://127.0.0.1:5173
```

演示账号（登录页可点击自动填入）：

| 账号 | 密码 | 角色 | 进入的端口 |
|---|---|---|---|
| wengshaogang | 123456 | 出差员工 | /employee |
| daiqinghao | 123456 | 部门审批人 | /approver |
| wusiting | 123456 | 财务复核 | /finance |
| manager | 123456 | 企业管理层 | /manager |

登录接口返回的 `role` 决定跳转目标，账号与端口严格绑定；手动改地址栏越权访问会被守卫踢回自己的落地页。

## 三、Mock 开关（后端没好也能跑全流程）

`.env.development` 里：

```
VITE_USE_MOCK=true     # 请求由 src/mock 接管，不发网络请求
VITE_USE_MOCK=false    # 直连 http://127.0.0.1:8080
```

`src/mock/index.js` 是一个 axios adapter，Mock 覆盖了全部 35 个接口，包含几处刻意保留的错误行为方便演示异常处理：

- 差标超限不填说明提交 → 返回 `2003`
- 挂接重复发票号 24412000000012345678 → 返回 `2001` 并附原申请号
- 驳回不填审批意见 → 返回 `1001`
- 预检 FAIL 未写强制说明 → 返回 `2004`

后端接口写完后：把 `.env.development` 改成 `false`，`src/mock` 整个目录可以直接删掉，业务代码一行不用改。

## 四、nginx 部署

```bash
npm run build        # 产物输出到 dist/
```

当前 `.env.production` 里 `VITE_USE_MOCK=true`，所以现在这份 `dist` 是**演示态**——后端没启动也能完整走通四个角色。等你把 Spring Boot 起来，把那行改成 `false` 重新 `npm run build` 一次，就会直连真实接口。

1. 把 `deploy/nginx.conf` 的内容整份替换到 nginx 安装目录的 `conf/nginx.conf`；
   （若不想覆盖主配置，只把 `server { ... }` 那段放进主配置的 `http { }` 里）
2. 确认 `root` 指向你的 `dist` 绝对路径，Windows 下用正斜杠：**`${USER_HOME}/Desktop/金舟面试项目/前端/dist`**
3. 启动：

```bash
cd <nginx安装目录>
start nginx                 # 首次启动
nginx -s reload             # 改完配置重载
nginx -s stop               # 停止
```

4. 浏览器访问 **http://localhost:8088**

前端用的是 **hash 路由**（地址形如 `http://localhost:8088/#/employee/apply`），所以放在子目录也不需要配 `try_files`。若想改成 history 路由，改 `router/index.js` 里的 `createWebHashHistory` 为 `createWebHistory`，并放开 nginx.conf 里注释掉的那两行 `try_files`。

### nginx 转发规则

| Location | 去向 |
|---|---|
| `/` | dist 静态资源 |
| `/assets/` | 带 hash 的 js/css，缓存一年 |
| `/api/` | 反代到 `127.0.0.1:8080`（Spring Boot） |

后端没启动时点任何接口会看到"后端服务异常，确认 Spring Boot 已启动"的提示，属正常现象。上传上限已在 nginx 设为 10MB，与 `application.yml` 的 multipart 配置保持一致。

## 五、和后端对接的约定

- 统一前缀 `/api`，统一返回体 `{ code, msg, data }`，`code === 0` 视为成功
- 鉴权：`Authorization: Bearer <token>`，由 `request.js` 的请求拦截器自动带上
- 错误码口径：`1001` 参数校验、`2001` 发票重复、`2002` 状态不允许、`2003` 超限缺说明、`2004` 预检未通过、`3001` 无权限、`4001` token 失效、`5000` 系统异常
- `4001` / HTTP `401` 会清掉本地 token 并跳回登录页
- 票据上传接口 `/api/invoice/upload` 是 multipart（字段 `file` + `applyNo`），其余接口都是 JSON

> 一个待办：接口 `/api/invoice/file/{path}` 的 path 形如 `2026/09/xxx.jpg` 是多级路径，Spring 侧需要用 `/file/**` 或改成 query 传参，否则路径会被截断。

## 六、已实现的业务规则落点

| 编号 | 规则 | 落在哪 |
|---|---|---|
| BR-01 | 差标超限必须写超标说明 | 申请提交前校验 + 单独的超标说明卡片 |
| BR-04 | 复审通过生成预借款 | 报销页展示预借额度与可冲抵余额 |
| BR-05 | 预检 WARN/FORCE 需强制说明 | 报销页"强制提交说明"输入框 |
| BR-06 | 只标异常不判违规 | 复核页 `canForcePass` 提示语 |
| BR-07 | 票据必须挂接到行程段 | 上传后必须选行程段才能确认挂接 |
| BR-08 | 评价结论仅作建议不拦截 | 审批页评价卡下方说明 |
| BR-10 | 复核后不允许改挂接 | 已提交票据不给删除入口 |
