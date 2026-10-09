# 后端代码分类（小类归类）

> 扫描自 `backend/src/main/java/com/ts/travelserver`，共 **214** 个 Java 类/接口。
> 分类口径：按**职责角色**划分（分层 + 业务域），便于评审与维护。

## 一、按职责角色汇总

| 角色类别 | 数量 | 说明 |
| --- | ---: | --- |
| 控制层 Controller | 16 | REST 入口，统一挂 `/api`，按业务域拆分 Controller |
| 服务层 Service | 30 | 业务逻辑层，含 Service 接口与 Impl 实现 |
| 数据访问 Mapper | 22 | MyBatis-Plus Mapper 接口 + 少量 XML 复杂查询 |
| 传输对象 DTO | 29 | 入参/出参契约（Request/Response/Param/Query/BO） |
| 视图对象 VO | 48 | 前端消费的最终展示对象（含 AI 要点等） |
| 配置 Config | 8 | Spring 配置类、属性绑定（@ConfigurationProperties） |
| 异常 Exception | 1 | 业务异常与全局异常处理 |
| 拦截器 Interceptor | 1 | 登录鉴权、角色校验拦截器 |
| 支撑组件 | 3 | 监听器/Runner/Aspect/Handler/Scheduler 等 |
| 业务/领域类 | 56 | AI、评估因子、通用支撑等领域类 |

## 二、按包（业务域）归类

### `travelserver/`  ——  （1 个）

- **业务/领域类**：TravelServerApplication

### `travelserver/ai/`  ——  （25 个）

- **服务层 Service**：AiAdviceService、AiCorrectionService、AiLogService、AiService、AiStatusService、DataQaService、DrillExplainService、MockAiService、MonthlyInsightService、PolicyQaService、RemoteAiService
- **支撑组件**：MonthlyInsightJob
- **业务/领域类**：AdviceGuard、AiCache、AiFmt、AiPrompts、AiScene、AiSemanticEvaluator、AiTextGuard、AiTextResult、CityHints、InvoiceMatchSuggester、MonthlyConclusionBuilder、RiskSummarizer、SuspicionDetector

### `travelserver/annotation/`  ——  （1 个）

- **业务/领域类**：RequireRole

### `travelserver/common/`  ——  （7 个）

- **异常 Exception**：BusinessException
- **支撑组件**：GlobalExceptionHandler
- **业务/领域类**：ApplyNoGenerator、PageResult、R、ResultCode、UserContext

### `travelserver/config/`  ——  （8 个）

- **配置 Config**：AiConfig、CorsConfig、FileStorageConfig、MybatisPlusConfig、SchedulingConfig、SlaConfig、StorageProperties、WebConfig

### `travelserver/controller/`  ——  （16 个）

- **控制层 Controller**：AdminController、AiController、AiPanelStateController、AnalyticsController、ApplyController、ApprovalController、ConfigController、EvalController、ExpenseController、FieldDraftController、FinanceController、InvoiceController、LoanController、MessageController、PartnerController、SystemController

### `travelserver/dto/`  ——  （29 个）

- **传输对象 DTO**：AiAdoptRequest、AiPanelSaveRequest、AiSemanticRequest、ApplyQuery、ApplySubmitRequest、ApprovalRequest、BatchApprovalRequest、BatchPayRequest、BatchReviewRequest、BudgetUpdateRequest、ConfigLogQuery、CostRefQuery、DataQaRequest、DeptCreateRequest、DeptInfoUpdateRequest、ExpenseSubmitRequest、FieldDraftQuery、FieldDraftSaveRequest、FinanceReviewRequest、InvoiceMatchRequest、InvoiceQuery、InvoiceUploadRequest、LoginRequest、PartnerSaveRequest、PaymentRequest、PolicyQaRequest、ResultBackfillRequest、StandardUpdateRequest、UserSaveRequest

### `travelserver/entity/`  ——  （22 个）

- **业务/领域类**：AiCorrection、AiLog、AiPanelState、ApplyRemind、ApprovalRecord、BizResult、ConfigLog、CostRef、Dept、EvalResult、ExpenseReport、FieldDraft、Invoice、LoanRecord、MonthlyInsight、Partner、PaymentRecord、SysMessage、SysUser、TripApply、TripItinerary、TripStandard

### `travelserver/eval/`  ——  （5 个）

- **业务/领域类**：EvalContext、EvalEngine、EvalExplain、EvalFactor、EvalLevel

### `travelserver/eval/factor/`  ——  （4 个）

- **业务/领域类**：CompletenessFactor、CostRatioFactor、FrequencyFactor、HistoryYieldFactor

### `travelserver/interceptor/`  ——  （1 个）

- **拦截器 Interceptor**：RoleInterceptor

### `travelserver/mapper/`  ——  （22 个）

- **数据访问 Mapper**：AiCorrectionMapper、AiLogMapper、AiPanelStateMapper、ApplyRemindMapper、ApprovalRecordMapper、BizResultMapper、ConfigLogMapper、CostRefMapper、DeptMapper、EvalResultMapper、ExpenseReportMapper、FieldDraftMapper、InvoiceMapper、LoanRecordMapper、MonthlyInsightMapper、PartnerMapper、PaymentRecordMapper、SysMessageMapper、SysUserMapper、TripApplyMapper、TripItineraryMapper、TripStandardMapper

### `travelserver/service/`  ——  （20 个）

- **服务层 Service**：AdminService、AiPanelStateService、AnalyticsService、ApplyService、ApprovalService、BatchService、BrowseService、CostRefService、EvalService、ExpenseService、FieldDraftService、FinanceService、InvoiceService、LoanService、MessageService、OcrService、PartnerService、StandardService、TokenService
- **支撑组件**：CostRefJob

### `travelserver/service/support/`  ——  （5 个）

- **业务/领域类**：AccessGuard、ConfigLogRecorder、FileStorage、LocalFileStorage、OssFileStorage

### `travelserver/vo/`  ——  （48 个）

- **视图对象 VO**：AdminUserVO、AiAdviceVO、AiFieldAdviceVO、AiPanelStateVO、AiPointVO、AiRiskVO、AiSemanticVO、AiStatusVO、AiSuspicionVO、ApplyChainVO、ApplyDetailVO、ApplyListItemVO、ApplySubmitResultVO、ApprovalViewVO、BatchResultVO、BrowseVO、ConfigLogVO、CostRefVO、DataQaVO、DeptVO、DrillExplainVO、DrillVO、EvalResultVO、ExpenseDetailVO、ExpenseListItemVO、FieldDraftVO、FinanceExceptionVO、InvoiceListVO、InvoiceMatchSuggestVO、InvoiceUploadVO、InvoiceVO、LoanVO、LoginVO、MessageVO、MissingTicketVO、MonthlyInsightVO、MonthlyReportVO、OcrResultVO、PartnerOptionVO、PartnerRoiVO、PartnerVO、PaymentVO、PolicyQaVO、PrecheckIssueVO、PrecheckVO、StandardVO、TableVO、UserInfoVO

## 三、测试

- `src/test/java/com/ts/travelserver` 下测试类 **1** 个。

## 四、说明

- 「小类」指工具类、枚举、常量、异常、拦截器、注解、配置等体量小、职责单一的支撑类，它们不承载主业务流程，却贯穿各层；本项目按包集中管理，便于复用与审查。
- 主业务流转集中在 `controller / service / mapper / entity` 四层，符合 Spring Boot 典型分层。
- AI 与评价为相对独立的领域包（`ai`、`eval`），通过 Service 注入主流程，可独立开关与降级。