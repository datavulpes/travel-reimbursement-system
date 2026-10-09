# 数据库脚本说明

## 当前环境

- MySQL 8.0.34，本机 `127.0.0.1:3306`，客户端在 `D:\develop\mysql\install\bin\mysql.exe`
- 库名：**`travel_server`**（下划线，DataGrip 里已建好）
- 连接用户 `root`，密码由本人掌握（未写进任何脚本文件）
- 状态：18 张表 + 3 个视图已建好，初始化数据已灌入

> 曾用名 `travel-server`（连字符）。连字符库名在 SQL 里必须反引号包住
> （`` `travel-server` ``），否则会被当成减法表达式报错；下划线 `travel_server` 不用。
> JDBC URL 里两种写法都直接写名字即可。

## 文件

| 文件 | 作用 |
|---|---|
| `schema.sql` | 建库 + 18 张表 + 3 个视图，可重复执行（先 DROP 再 CREATE）。**新环境只用这一份** |
| `初始化数据.sql` | 联调用的基础数据与演示单据，可重复执行（先 DELETE 再 INSERT） |
| `历史增量\` | 早期分次补表补列的脚本，**内容已并入 `schema.sql`**，只作变更记录留着 |
| `放宽草稿必填.sql` + `.bat` | 对已有库放宽草稿必填约束的运维脚本，不是建表增量，仍在原地 |

> 归档进 `历史增量\` 的四份：`新增AI能力表与字段.sql`、`新增配置留痕表.sql`、
> `新增出差类型补充说明.sql`、`新增理由草稿表与报销说明列.sql`（都带同名 `.bat`）。
> 它们补出来的 `field_draft` / `config_log` / `ai_log` 等，现在 `schema.sql` 里直接就有，
> 新环境建库**不要再跑这些**；只有「库早就建好、不想重建」的场景才用得上。
> bat 双击后输一次 root 密码即可执行，重复执行报「表已存在 / 列已存在」属正常，不影响已有数据。

## 执行顺序

```bash
# 方式一：命令行
mysql -u root -p < schema.sql
mysql -u root -p < 初始化数据.sql

# 方式二：Navicat / DataGrip
# 建库建表：整份执行 schema.sql
# 灌数据：整份执行 初始化数据.sql
```

校验：

```sql
SHOW TABLES;                                   -- 18 张表
SHOW FULL TABLES WHERE Table_type = 'VIEW';    -- 3 个视图
SELECT COUNT(*) FROM sys_user;                 -- 4
SELECT * FROM v_approval_todo;                 -- 应有 TA202609180001
```

## 四个演示账号

密码统一 `123456`，已在 `初始化数据.sql` 里写成 BCrypt 密文，Spring Boot 用 `BCryptPasswordEncoder` 能直接校验。

| 账号 | 姓名 | 角色 | 进入端口 |
|---|---|---|---|
| wengshaogang | 翁绍刚 | EMPLOYEE | `/employee` |
| daiqinghao | 戴庆豪 | APPROVER | `/approver` |
| wusiting | 吴思挺 | FINANCE | `/finance` |
| manager | 管理层 | MANAGER | `/manager` |

> 后端如果没上 Spring Security，就用 `new BCryptPasswordEncoder().matches(raw, password)` 手动校验，别自己写 MD5。

## 表清单（18 张）

| 分类 | 表 |
|---|---|
| 组织与基础 | `sys_user`、`dept`、`partner`、`trip_standard`、`cost_ref` |
| 申请与审批 | `trip_apply`、`trip_itinerary`、`approval_record`、`eval_result` |
| 票据报销付款 | `invoice`、`loan_record`、`expense_report`、`payment_record` |
| 结果回填 | `biz_result` |
| 留痕与报告 | `config_log`、`ai_log`、`monthly_insight` |
| 暂存 | `field_draft`（理由字段草稿，不参与业务计算） |

主线是**申请号 `apply_no`**：`TA+yyyyMMdd+4位序号`，一串到底贯穿申请 → 审批 → 票据 → 报销 → 复核 → 分析。报销单号 `ER+yyyyMMdd+4位序号`。

## 三个视图

- `v_approval_todo` —— 审批待办，把参考均值、差标结论、评价档位一次带出来
- `v_finance_pending` —— 财务待复核，附票据数与结果回填数
- `v_dept_monthly` —— 部门月度投入产出，费效比月报数据源

## 几个约定

- 字符集统一 `utf8mb4`，引擎 `InnoDB`
- **不建物理外键**，关联靠 `apply_no` / `id` 在应用层维护（省去联调时插数据的顺序麻烦）
- `invoice.invoice_no` 唯一索引是查重的根基，别删
- 票据影像只存相对路径 `img_path`，不存 BLOB
- MySQL 8 才支持 `JSON` 类型（`approval_record.snapshot`、`eval_result.explain_json`），别用 5.7 以下的库
