# 后端服务（travel-server）

Spring Boot 4.x + MyBatis-Plus 的差旅报销后端，包根 `com.ts.travelserver`，
统一以 `/api` 为上下文（与前端 `VITE_API_BASE=/api`、nginx 反代对齐）。

## 技术栈

- Spring Boot 4.1 / Spring Web MVC / Tomcat 11
- MyBatis-Plus 3.5（逻辑删除、下划线转驼峰）
- MySQL 8 驱动（Connector/J 9）
- 可选外部能力：**AI**（DeepSeek，可关）、**OCR**（百度，可关）、**OSS**（阿里云，可回滚 local）

## 目录

```
src/main/java/com/ts/travelserver
├── controller/   控制层（16 个，按业务域拆分）
├── service/      服务层（接口 + Impl，含 support 子包）
├── mapper/       MyBatis-Plus Mapper + xml 复杂查询
├── entity/       表映射实体
├── dto/          入参/出参契约
├── vo/           前端展示对象（含 AI 要点）
├── config/       Spring/业务配置（Ai/Storage/Sla 等）
├── common/       统一返回 R、分页 PageResult、异常码、UserContext
├── interceptor/  登录鉴权 + 角色校验
├── annotation/   @RequireRole
├── ai/           AI 能力（语义评价/填单助手/月度洞察/问数/远程调用/状态/日志）
└── eval/         合理性评价因子与评分
src/main/resources
├── application.yml   # 主配置（已脱敏，密钥见占位符）
├── application.properties
└── ai/*.txt          # AI prompt 模板（外化维护）
database/             # 库表结构、初始化数据、数据字典、增量脚本
docs/backend-classification.md  # 215 个类的分类说明
```

## 运行

```bash
# 1) 建库（MySQL 8）
mysql -uroot -p <your_password> < database/schema.sql
mysql -uroot -p <your_password> travel_server < database/初始化数据.sql

# 2) 填密钥：编辑 src/main/resources/application.yml，替换 ${DB_PASSWORD} / ${TOKEN_SECRET} 等

# 3) 启动
./mvnw spring-boot:run        # 或 mvn spring-boot:run
```

启动后接口根路径 `http://localhost:8080/api`。鉴权：账号密码登录，token 走 `Authorization`
请求头，拦截器按角色放行；`/api/system/login`、`/api/error` 免登录。

## 配置开关（都在 application.yml 的 `travel.*`）

| 项 | 默认 | 说明 |
| --- | --- | --- |
| `ai.enabled` | true | false 时走本地启发式，全链路照跑、页面标「本地演示」 |
| `storage.type` | oss | 改 `local` 可回滚到本地磁盘（无需任何外部账号） |
| `ocr.enabled` | true | false 时上传返回 `ocrSuccess=false`，前端转手工录入 |
| `eval.weight.*` | 0.35/0.30/0.20/0.15 | 合理性评价四因子权重，之和须为 1 |

## 脱敏说明

`application.yml` 已移除全部真实密钥与本地路径，相关值以 `${...}` 占位，文件头有填写指引。
请勿把真实密钥提交到公开仓库；推荐用环境变量或启动参数注入。
