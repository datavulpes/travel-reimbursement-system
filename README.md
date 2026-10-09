# 企业差旅管理与费用报销系统（开源整理版）

企业差旅报销系统，我在校完成的第一个全栈项目。它把「出差申请→贴票报销→多级审批→财务打款→月底对账」搬到线上，员工免手工对账，财务和经理在后台即可看清每笔差旅费是否合理、有无超标或可疑。

后端 Spring Boot + MyBatis-Plus，MySQL 存储，nginx 反代；前端 Vue3 + Vite 管理后台，移动端 uni-app 一套代码出 H5/小程序/App，覆盖员工、审批、财务、经理四角色。AI 方面，月度洞察按月汇总各部门花费与可疑项并附原始依据；费效钻取能从汇总追到单张发票。模型（DeepSeek）挂了回退规则，离线可演示。

## 仓库结构

```
github/
├── README.md                 # 本文件
├── backend/                  # 后端：Spring Boot + MyBatis-Plus
│   ├── pom.xml
│   ├── src/                  # 主代码（com.ts.travelserver）
│   ├── database/             # 库表结构(schema.sql) + 初始化数据 + 数据字典 + 增量脚本
│   └── docs/backend-classification.md   # 后端 215 个类的分类说明
├── frontend/                 # PC 管理后台：Vue3 + Vite
│   ├── src/                  # 页面/组件/接口/工具
│   └── deploy/nginx.conf     # 反代配置样例（本地路径已脱敏）
└── miniprogram/              # 移动端：uni-app（Vue3），一套代码出 H5/小程序/App
    └── src/                  # 16 页，4 角色权限
```

`node_modules / dist / target / .idea` 等依赖与构建产物**未纳入**（按需自行安装/构建）。

## 三大子项目

| 子项目 | 技术栈 | 端口/入口 | 说明 |
| --- | --- | --- | --- |
| backend | Spring Boot 4.x、MyBatis-Plus、MySQL 8 | `:8080/api` | 统一返回 `R<T>`，分页 `PageResult`，业务异常 `BusinessException`；AI（DeepSeek）、OCR（百度）、OSS 存储均可独立开关降级 |
| frontend | Vue3 + Vite | 经 nginx `:8088` 托管 | `.env` 控制 `VITE_USE_MOCK`（是否走本地 mock）与 `VITE_AI_MODE`（mock/remote/off） |
| miniprogram | uni-app（Vue3 CLI） | H5 `/m/`、App/小程序绝对地址 | `manifest.json` 的 appid 已清空，需自备 DCloud appid |

## 已做的脱敏与整理

为便于公开，本仓库已对源码做以下处理（**未上传、仅本地整理**）：

1. **密钥全部替换为占位符**：`application.yml` 中的数据库密码、OSS 密钥、OCR 密钥、
   登录令牌密钥、AI api-key、影像签名密钥等，一律改为 `${...}` 形式，并在文件头注明如何填写。
2. **个人/本地信息去除**：本地路径（`C:/Users/31259`、`D:/develop`、`D:/nginx`、`D:/travel-uploads`）
   与个人热点 IP（`192.168.137.1`）替换为通用占位符；小程序 `manifest.json` 的个人 DCloud appid 已清空。
3. **SQL 注释里的库密码**（`mysql -uroot -p08031513`）改为 `p<DB_PASSWORD>`。
4. **去除冗余注释**：代码中整行的噪声注释（TODO/FIXME、调试/临时标记、被注释掉的死代码）已清理；
   有业务价值的说明注释保留。
5. **排除项**：真实密钥文件（`deepSeek_KEY.txt`）、本地 classpath 快照（`cp.txt`）、
   含个人路径的部署 `.bat` 脚本、构建产物与日志均**未复制**。

## 如何配置密钥并跑起来

以 backend 为例，编辑 `backend/src/main/resources/application.yml`：

- 必填：`${DB_PASSWORD}`（MySQL 密码）、`${TOKEN_SECRET}`（`python -c "import secrets;print(secrets.token_urlsafe(32))"` 生成）。
- 可选：填 `${AI_API_KEY}` 开启真实 AI；不填则把 `travel.ai.enabled` 设为 `false` 走本地演示。
- OSS / OCR 同理，不需要时可把 `travel.storage.type` 改 `local`、`travel.ocr.enabled` 改 `false`。

建库：依次执行 `backend/database/schema.sql` 与 `backend/database/初始化数据.sql`（默认账号密码见 `数据库/README.md`）。

前端/小程序详见各自目录内的 `README.md`。

## 备注

本目录仅做本地整理，未执行任何 `git push` 或上传操作。
