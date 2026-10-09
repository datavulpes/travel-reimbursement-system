package com.ts.travelserver.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.ResultCode;
import com.ts.travelserver.common.UserContext;
import com.ts.travelserver.dto.InvoiceQuery;
import com.ts.travelserver.config.FileStorageConfig;
import com.ts.travelserver.config.StorageProperties;
import com.ts.travelserver.service.support.FileStorage;
import com.ts.travelserver.ai.AiCorrectionService;
import com.ts.travelserver.ai.InvoiceMatchSuggester;
import com.ts.travelserver.dto.InvoiceMatchRequest;
import com.ts.travelserver.entity.ApprovalRecord;
import com.ts.travelserver.entity.ExpenseReport;
import com.ts.travelserver.entity.Invoice;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.entity.TripApply;
import com.ts.travelserver.entity.TripItinerary;
import com.ts.travelserver.mapper.ApprovalRecordMapper;
import com.ts.travelserver.mapper.ExpenseReportMapper;
import com.ts.travelserver.mapper.InvoiceMapper;
import com.ts.travelserver.mapper.SysUserMapper;
import com.ts.travelserver.mapper.TripApplyMapper;
import com.ts.travelserver.mapper.TripItineraryMapper;
import com.ts.travelserver.vo.ApplyDetailVO;
import com.ts.travelserver.vo.InvoiceListVO;
import com.ts.travelserver.vo.InvoiceMatchSuggestVO;
import com.ts.travelserver.vo.InvoiceUploadVO;
import com.ts.travelserver.vo.InvoiceVO;
import com.ts.travelserver.vo.MissingTicketVO;
import com.ts.travelserver.vo.OcrResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 票据（模块 02，C-01 ~ C-04 与 I-04）。
 *
 * <p>三件事：影像落盘、票号查重、票据必须挂到具体行程段（BR-07）。
 * 影像不入库，只存相对路径，读取走带签名的临时链接（设计报告 5.7）。
 *
 * <p>TODO-需要你补基础数据：invoice 表没有「票据类型」列，
 * 缺票推算只能按段判断张数（跨天段建议 2 张），分不清哪张是住宿票、哪张是交通票。
 * 想算得更准，给 invoice 加一列 invoice_type VARCHAR(20)（住宿 / 交通 / 其他），
 * 再在 {@link #missingTickets} 里按类型判断即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceMapper invoiceMapper;
    private final TripApplyMapper tripApplyMapper;
    private final TripItineraryMapper tripItineraryMapper;
    private final ExpenseReportMapper expenseReportMapper;
    private final ApprovalRecordMapper approvalRecordMapper;
    private final SysUserMapper sysUserMapper;
    private final FileStorageConfig fileStorageConfig;
    /**
     * 影像存取口子。本地磁盘与 OSS 两套实现对外行为一致，
     * 换 travel.storage.type 时这个类一行都不用改。
     */
    private final FileStorage fileStorage;
    private final StorageProperties storageProperties;
    private final OcrService ocrService;
    private final TokenService tokenService;
    private final InvoiceMatchSuggester invoiceMatchSuggester;
    private final AiCorrectionService aiCorrectionService;

    // ---------- C-01 上传票据 ----------

    /**
     * 文件落盘 + OCR 回显。识别失败只回 ocrSuccess=false，不抛异常，
     * 前端直接转手工录入，主流程不阻塞（设计报告 6.4.2）。
     */
    public InvoiceUploadVO upload(MultipartFile file, String applyNo, Boolean skipOcr) {
        TripApply apply = tripApplyMapper.selectOne(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getApplyNo, applyNo));
        if (apply == null) {
            throw BusinessException.paramError("申请单不存在：" + applyNo);
        }
        if (!TripApply.STATUS_APPROVED.equals(apply.getStatus())
                && !TripApply.STATUS_CLOSED.equals(apply.getStatus())) {
            throw BusinessException.statusForbidden("申请未通过，还不能上传票据");
        }
        if (file == null || file.isEmpty()) {
            throw BusinessException.paramError("请选择要上传的票据影像");
        }

        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extension(original);
        if (!fileStorageConfig.allowedExtensionList().contains(ext)) {
            throw BusinessException.paramError("不支持的文件类型 " + ext
                    + "，允许：" + String.join("、", fileStorageConfig.allowedExtensionList()));
        }

        LocalDate today = LocalDate.now();
        String relative = today.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM"))
                + "/invoice_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "." + ext;

        // 影像可能在 OSS 上，手里没有本地 File，所以先读成字节：存一份、OCR 一份，同一段内容
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            log.error("读取上传的票据影像失败：{}", original, e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "影像读取失败，请重试");
        }
        fileStorage.save(relative, bytes, file.getContentType());

        InvoiceUploadVO vo = new InvoiceUploadVO();
        vo.setTempPath(relative);
        vo.setFileName(original);
        vo.setFileSize(file.getSize());
        vo.setPreviewUrl(fileStorage.signedUrl(relative));
        vo.setThumbUrl(fileStorage.thumbUrl(relative));

        boolean skip = Boolean.TRUE.equals(skipOcr);
        OcrResultVO ocr = skip ? null : ocrService.recognize(bytes, bytes.length);
        vo.setOcr(ocr);
        vo.setOcrSuccess(ocr != null && Boolean.TRUE.equals(ocr.getSuccess()));

        // 挂段建议：拿 OCR 出来的日期与销售方去比行程段，一次往返就把建议带回去
        if (ocr != null && Boolean.TRUE.equals(ocr.getSuccess())) {
            fillSuggestion(vo, applyNo, ocr.getInvoiceDate(), ocr.getSeller());
        }
        return vo;
    }

    /**
     * 算一条挂段建议填进返回体。算不出来就算了，不影响上传本身。
     */
    private void fillSuggestion(InvoiceUploadVO vo, String applyNo, String invoiceDateText, String seller) {
        InvoiceMatchSuggestVO suggestion = match(applyNo, invoiceDateText, seller);
        if (suggestion != null && suggestion.getItineraryId() != null) {
            vo.setSuggestItineraryId(suggestion.getItineraryId());
            vo.setSuggestConfidence(suggestion.getConfidence());
            vo.setSuggestReason(suggestion.getReason());
        }
    }

    /**
     * C-05 单独查挂段建议。
     *
     * <p>上传接口（C-01）里已经顺带返回过一次建议，但那是用 OCR 结果算的；
     * 页面上人改了开票日期或销售方之后，再重传一次文件只为问「挂哪段」不合理，
     * 所以单开这一个只读接口。它不落库，纯粹是给人看的一句提示。
     *
     * @param invoiceDateText yyyy-MM-dd；为空时只按销售方城市判断
     * @return 行程段为空时返回 null，前端据此不显示「AI 推荐」那一行
     */
    public InvoiceMatchSuggestVO match(String applyNo, String invoiceDateText, String seller) {
        try {
            LocalDate invoiceDate = parseDate(invoiceDateText);
            List<TripItinerary> itineraries = tripItineraryMapper.selectList(
                    new LambdaQueryWrapper<TripItinerary>()
                            .eq(TripItinerary::getApplyNo, applyNo)
                            .orderByAsc(TripItinerary::getSeq));
            InvoiceMatchSuggester.Suggestion suggestion =
                    invoiceMatchSuggester.suggest(invoiceDate, seller, itineraries);
            if (suggestion == null) {
                return null;
            }
            return new InvoiceMatchSuggestVO(
                    suggestion.itineraryId(), suggestion.confidence(), suggestion.reason());
        } catch (Exception e) {
            // 建议是锦上添花，算不出来也不能把页面搞挂——返回 null，前端不显示这一行即可
            log.warn("挂段建议计算失败（不影响主流程）：{}", e.getMessage());
            return null;
        }
    }

    // ---------- C-02 确认票面字段并挂接行程段 ----------

    @Transactional(rollbackFor = Exception.class)
    public InvoiceVO confirm(InvoiceMatchRequest request) {
        TripApply apply = tripApplyMapper.selectOne(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getApplyNo, request.getApplyNo()));
        if (apply == null) {
            throw BusinessException.paramError("申请单不存在：" + request.getApplyNo());
        }
        if (!apply.getUserId().equals(UserContext.currentId())) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "只能给自己的申请挂接票据");
        }

        // 票据必须落到具体行程段（BR-07）
        TripItinerary itinerary = tripItineraryMapper.selectById(request.getItineraryId());
        if (itinerary == null || !request.getApplyNo().equals(itinerary.getApplyNo())) {
            throw BusinessException.paramError("行程段不属于该申请单");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.paramError("票据金额必须大于 0");
        }
        if (!org.springframework.util.StringUtils.hasText(request.getImgPath())) {
            throw BusinessException.paramError("请先上传票据影像");
        }

        // 入库前按票号查重：命中就拦下，并把原申请号告诉前端标红（FR-0203）
        Invoice dup = invoiceMapper.selectOne(new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getInvoiceNo, request.getInvoiceNo())
                .last("limit 1"));
        if (dup != null) {
            throw new BusinessException(ResultCode.INVOICE_DUPLICATE,
                    "该发票号已被申请 " + dup.getApplyNo() + " 使用，请核对票号");
        }

        Invoice invoice = new Invoice();
        invoice.setInvoiceNo(request.getInvoiceNo());
        invoice.setApplyNo(request.getApplyNo());
        invoice.setItineraryId(request.getItineraryId());
        invoice.setAmount(request.getAmount());
        invoice.setInvoiceDate(parseDate(request.getInvoiceDate()));
        invoice.setSeller(request.getSeller());
        invoice.setImgPath(request.getImgPath());
        invoice.setOcrFlag(request.getOcrFlag() == null ? Invoice.OCR_FLAG_MANUAL : request.getOcrFlag());
        invoiceMapper.insert(invoice);

        // 人工修正留痕：OCR 代填的才记。手工录入没有「系统给的值」，
        // 记进去只会把「哪个字段最常被改」这个统计稀释掉。
        if (Invoice.OCR_FLAG_OCR == invoice.getOcrFlag()) {
            aiCorrectionService.recordOcr(
                    invoice.getInvoiceNo(),
                    UserContext.currentId(),
                    ocrValues(request.getOcrValues()),
                    new AiCorrectionService.OcrValues(
                            request.getInvoiceNo(),
                            String.valueOf(request.getAmount()),
                            request.getInvoiceDate(),
                            request.getSeller()));
        }
        return toVO(invoice, fileStorage.signedUrl(invoice.getImgPath()),
                fileStorage.thumbUrl(invoice.getImgPath()));
    }

    private static AiCorrectionService.OcrValues ocrValues(InvoiceMatchRequest.OcrValues v) {
        if (v == null) {
            return null;
        }
        return new AiCorrectionService.OcrValues(v.getInvoiceNo(), v.getAmount(),
                v.getInvoiceDate(), v.getSeller());
    }

    // ---------- C-03 本单票据清单 + 缺票推算 ----------

    public InvoiceListVO list(String applyNo) {
        TripApply apply = tripApplyMapper.selectOne(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getApplyNo, applyNo));
        if (apply == null) {
            throw BusinessException.paramError("申请单不存在：" + applyNo);
        }
        assertVisible(apply);

        List<TripItinerary> itineraries = tripItineraryMapper.selectList(
                new LambdaQueryWrapper<TripItinerary>()
                        .eq(TripItinerary::getApplyNo, applyNo)
                        .orderByAsc(TripItinerary::getSeq));
        List<Invoice> invoices = invoiceMapper.selectList(
                new LambdaQueryWrapper<Invoice>()
                        .eq(Invoice::getApplyNo, applyNo)
                        .orderByDesc(Invoice::getId));

        BigDecimal total = BigDecimal.ZERO;
        List<InvoiceVO> invoiceVOs = new ArrayList<>();
        for (Invoice invoice : invoices) {
            total = total.add(invoice.getAmount() == null ? BigDecimal.ZERO : invoice.getAmount());
            invoiceVOs.add(toVO(invoice, fileStorage.signedUrl(invoice.getImgPath()),
                    fileStorage.thumbUrl(invoice.getImgPath())));
        }

        List<ApplyDetailVO.ItineraryVO> itineraryVOs = new ArrayList<>();
        for (TripItinerary item : itineraries) {
            ApplyDetailVO.ItineraryVO vo = new ApplyDetailVO.ItineraryVO();
            vo.setId(item.getId());
            vo.setSeq(item.getSeq());
            vo.setStartDate(item.getStartDate() == null ? null : item.getStartDate().toString());
            vo.setEndDate(item.getEndDate() == null ? null : item.getEndDate().toString());
            vo.setFromCity(item.getFromCity());
            vo.setToCity(item.getToCity());
            vo.setTrafficType(item.getTrafficType());
            vo.setCityLevel(item.getCityLevel());
            long cnt = invoices.stream().filter(i -> item.getId().equals(i.getItineraryId())).count();
            vo.setInvoiceCnt((int) cnt);
            itineraryVOs.add(vo);
        }

        List<MissingTicketVO> missing = missingTickets(itineraries, invoices);

        InvoiceListVO vo = new InvoiceListVO();
        vo.setApplyNo(applyNo);
        vo.setInvoices(invoiceVOs);
        vo.setTotalAmount(total);
        vo.setMissing(missing);
        vo.setHasMissing(!missing.isEmpty());
        vo.setItineraries(itineraryVOs);
        return vo;
    }

    // ---------- C-04 删除未提交的票据 ----------

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Invoice invoice = invoiceMapper.selectById(id);
        if (invoice == null) {
            throw BusinessException.paramError("票据不存在");
        }
        TripApply apply = tripApplyMapper.selectOne(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getApplyNo, invoice.getApplyNo()));
        if (apply == null || !apply.getUserId().equals(UserContext.currentId())) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "只能删除自己的票据");
        }
        // 报销单已提交就不能再改票据挂接（BR-10）
        Long reportCnt = expenseReportMapper.selectCount(
                new LambdaQueryWrapper<ExpenseReport>().eq(ExpenseReport::getApplyNo, invoice.getApplyNo()));
        if (reportCnt != null && reportCnt > 0) {
            throw BusinessException.statusForbidden("报销单已提交，不能再删除票据");
        }
        // 影像跟着一起删，不然磁盘/OSS 上会堆一堆没人认领的票
        String imgPath = invoice.getImgPath();
        invoiceMapper.deleteById(id);
        if (storageProperties.isDeleteWithInvoice() && imgPath != null && !imgPath.isBlank()) {
            fileStorage.delete(imgPath);
        }
    }

    // ---------- I-04 影像访问 ----------

    /**
     * 生成带签名的临时链接。过期时间从配置来，过期后前端要重新取链（NFR-04）。
     *
     * <p>本地模式返回后端代理地址 /api/invoice/file，OSS 模式返回私有桶预签名 URL；
     * 两种都在 travel.file.url-expire-minutes 分钟后失效，对前端是同一件事。
     */
    public String signedUrl(String path) {
        return fileStorage.signedUrl(path);
    }

    /** 缩略图地址，列表与票据墙用；本地存储没有图片处理，与 signedUrl 同一条 */
    public String thumbUrl(String path) {
        return fileStorage.thumbUrl(path);
    }

    /**
     * 校验签名，通过则返回影像 key。签名不对或已过期返回 3001。
     *
     * <p>只验签、不读文件：影像放在 OSS 上时这里根本读不到东西，
     * 真正的读取统一走 {@link #readImage(String)}，本地与 OSS 是同一个口子。
     */
    public String resolveSigned(String path, Long exp, String sign) {
        if (path == null || path.isBlank() || exp == null || sign == null || sign.isBlank()) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "影像链接不完整");
        }
        if (System.currentTimeMillis() > exp) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "影像链接已过期，请重新获取");
        }
        String expected = tokenService.signPath(path, exp, fileStorageConfig.getSignSecret());
        if (!expected.equals(sign)) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "影像签名不正确");
        }
        // 关键：把 ../ 挡住，否则能顺着参数读到磁盘上任意文件
        String normalized = Paths.get(path).normalize().toString().replace("\\", "/");
        if (normalized.startsWith("..") || normalized.contains("../")) {
            throw new BusinessException(ResultCode.NO_PERMISSION, "影像路径不合法");
        }
        return normalized;
    }

    /**
     * 读影像字节。不存在返回 null，由调用方决定是 404 还是占位图。
     */
    public byte[] readImage(String key) {
        return fileStorage.load(key);
    }

    /** 影像 MIME。后端代理输出与 OSS 存对象用同一套推断，别各写一份 */
    public String imageContentType(String key) {
        return fileStorage.contentType(key);
    }

    // ---------- I-05 票据影像查询 ----------

    /**
     * 按条件查票据影像。
     *
     * <p>之前全系统没有「查票据」这件事：员工只在自己上传页见过图，
     * 财务复审页只有个「已上传」标签，审批端连接口都调不通。
     * 这里把影像按条件捞出来，列表带缩略图、点开看原图，
     * 审票的人不用再靠想象判断这张票对不对。
     *
     * <p>可见范围按角色定死，不让前端传 userId 之类——谁能看谁是后端的事。
     */
    public PageResult<InvoiceVO> search(InvoiceQuery query) {
        long pageNum = query.safePageNum();
        long pageSize = query.safePageSize();

        List<String> visible = visibleApplyNos(UserContext.currentId(), UserContext.currentRole());
        if (visible != null && visible.isEmpty()) {
            return PageResult.empty(pageNum, pageSize);
        }

        LambdaQueryWrapper<Invoice> wrapper = new LambdaQueryWrapper<>();
        if (visible != null) {
            wrapper.in(Invoice::getApplyNo, visible);
        }
        if (org.springframework.util.StringUtils.hasText(query.getApplyNo())) {
            wrapper.eq(Invoice::getApplyNo, query.getApplyNo().trim());
        }
        if (org.springframework.util.StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(Invoice::getInvoiceNo, kw).or().like(Invoice::getSeller, kw));
        }
        if (org.springframework.util.StringUtils.hasText(query.getDateFrom())) {
            wrapper.ge(Invoice::getInvoiceDate, parseDate(query.getDateFrom()));
        }
        if (org.springframework.util.StringUtils.hasText(query.getDateTo())) {
            wrapper.le(Invoice::getInvoiceDate, parseDate(query.getDateTo()));
        }
        if (query.getMinAmount() != null) {
            wrapper.ge(Invoice::getAmount, query.getMinAmount());
        }
        if (query.getMaxAmount() != null) {
            wrapper.le(Invoice::getAmount, query.getMaxAmount());
        }
        if (Boolean.TRUE.equals(query.getHasImage())) {
            wrapper.isNotNull(Invoice::getImgPath).ne(Invoice::getImgPath, "");
        } else if (Boolean.FALSE.equals(query.getHasImage())) {
            wrapper.and(w -> w.isNull(Invoice::getImgPath).or().eq(Invoice::getImgPath, ""));
        }
        wrapper.orderByDesc(Invoice::getId);

        IPage<Invoice> page = invoiceMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<InvoiceVO> vos = page.getRecords().stream()
                .map(invoice -> toVO(invoice, fileStorage.signedUrl(invoice.getImgPath()),
                        fileStorage.thumbUrl(invoice.getImgPath())))
                .toList();
        fillApplicantName(vos);
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 单张票据重新取链。
     *
     * <p>影像地址带过期时间，页面停留久了图片会 404。前端拿到加载失败时调这个换一条新的，
     * 不用整页刷新，也不用把申请单重新拉一遍。
     */
    public InvoiceVO url(Long id) {
        Invoice invoice = invoiceMapper.selectById(id);
        if (invoice == null) {
            throw BusinessException.paramError("票据不存在");
        }
        TripApply apply = tripApplyMapper.selectOne(
                new LambdaQueryWrapper<TripApply>().eq(TripApply::getApplyNo, invoice.getApplyNo()));
        if (apply == null) {
            throw BusinessException.paramError("票据对应的申请单不存在");
        }
        assertVisible(apply);
        return toVO(invoice, fileStorage.signedUrl(invoice.getImgPath()),
                fileStorage.thumbUrl(invoice.getImgPath()));
    }

    /**
     * 可见的申请号集合。返回 null 表示不限制（财务看全部）。
     */
    private List<String> visibleApplyNos(Long userId, String role) {
        if (SysUser.ROLE_FINANCE.equals(role)) {
            return null;
        }
        if (userId == null) {
            return List.of();
        }
        if (SysUser.ROLE_EMPLOYEE.equals(role)) {
            return tripApplyMapper.selectList(new LambdaQueryWrapper<TripApply>()
                            .eq(TripApply::getUserId, userId))
                    .stream().map(TripApply::getApplyNo).toList();
        }
        if (SysUser.ROLE_APPROVER.equals(role) || SysUser.ROLE_MANAGER.equals(role)) {
            return approvalRecordMapper.selectList(new LambdaQueryWrapper<ApprovalRecord>()
                            .eq(ApprovalRecord::getApproverId, userId))
                    .stream().map(ApprovalRecord::getApplyNo).distinct().toList();
        }
        return List.of();
    }

    /**
     * 审批侧的两个角色：部门审批人（初审）与管理层（复审）。
     *
     * <p>管理层是复审那一环，复审详情里同样要摆票据影像——把他挡在门外的话，
     * 复审单上会一片空白，而前端是 catch 静默的，不报错、只「恰好没有票据」。
     * 判据跟初审一样用 approval_record：他审过这一单，才看得到这一单的票。
     */
    private boolean approverRole(String role) {
        return SysUser.ROLE_APPROVER.equals(role) || SysUser.ROLE_MANAGER.equals(role);
    }

    /**
     * 这张票能不能看。
     *
     * <p>票据是审批通过之后才让传的（upload 里卡了单据状态），所以审批人只要审过这一单，
     * approval_record 里必定有他——拿这条记录当边界，比按部门去猜准得多。
     */
    private void assertVisible(TripApply apply) {
        Long userId = UserContext.currentId();
        String role = UserContext.currentRole();
        if (SysUser.ROLE_FINANCE.equals(role)) {
            return;
        }
        if (userId != null && userId.equals(apply.getUserId())) {
            return;
        }
        if (approverRole(role) && approvedByMe(apply.getApplyNo(), userId)) {
            return;
        }
        throw new BusinessException(ResultCode.NO_PERMISSION, "只能查看自己相关的票据");
    }

    private boolean approvedByMe(String applyNo, Long userId) {
        if (userId == null || applyNo == null) {
            return false;
        }
        Long cnt = approvalRecordMapper.selectCount(new LambdaQueryWrapper<ApprovalRecord>()
                .eq(ApprovalRecord::getApplyNo, applyNo)
                .eq(ApprovalRecord::getApproverId, userId));
        return cnt != null && cnt > 0;
    }

    /**
     * 回填申请人姓名。一页最多 100 条，先攒齐再查，别在循环里一条条问库。
     */
    private void fillApplicantName(List<InvoiceVO> vos) {
        if (vos.isEmpty()) {
            return;
        }
        List<String> applyNos = vos.stream()
                .map(InvoiceVO::getApplyNo).filter(Objects::nonNull).distinct().toList();
        Map<String, Long> ownerMap = new HashMap<>();
        for (TripApply apply : tripApplyMapper.selectList(
                new LambdaQueryWrapper<TripApply>().in(TripApply::getApplyNo, applyNos))) {
            ownerMap.put(apply.getApplyNo(), apply.getUserId());
        }
        Set<Long> userIds = ownerMap.values().stream()
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = new HashMap<>();
        for (SysUser user : sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>().in(SysUser::getId, userIds))) {
            nameMap.put(user.getId(), user.getRealName());
        }
        for (InvoiceVO vo : vos) {
            Long uid = ownerMap.get(vo.getApplyNo());
            vo.setApplicantName(uid == null ? null : nameMap.get(uid));
        }
    }

    // ---------- 内部 ----------

    /**
     * 缺票推算（设计报告 6.4.4）：跨天行程应有住宿票，其余段至少一张票；
     * 开票日期落在所有行程段之外的，单独提示「疑似无关」。只提示不阻拦（BR-05）。
     */
    private List<MissingTicketVO> missingTickets(List<TripItinerary> itineraries, List<Invoice> invoices) {
        List<MissingTicketVO> result = new ArrayList<>();
        if (itineraries == null) {
            return result;
        }
        for (TripItinerary item : itineraries) {
            long cnt = invoices.stream().filter(i -> item.getId().equals(i.getItineraryId())).count();
            long days = (item.getStartDate() == null || item.getEndDate() == null)
                    ? 0
                    : ChronoUnit.DAYS.between(item.getStartDate(), item.getEndDate());
            String route = (item.getFromCity() == null ? "" : item.getFromCity())
                    + " → " + (item.getToCity() == null ? "" : item.getToCity());
            if (cnt == 0) {
                MissingTicketVO vo = new MissingTicketVO();
                vo.setItineraryId(item.getId());
                vo.setSeq(item.getSeq());
                vo.setRoute(route);
                vo.setTicketType(days >= 1 ? "住宿发票 / 交通票据" : "交通票据");
                vo.setHint("第 " + item.getSeq() + " 段（" + route + "）还没挂接票据");
                result.add(vo);
            } else if (days >= 1 && cnt < 2) {
                MissingTicketVO vo = new MissingTicketVO();
                vo.setItineraryId(item.getId());
                vo.setSeq(item.getSeq());
                vo.setRoute(route);
                vo.setTicketType("住宿发票");
                vo.setHint("第 " + item.getSeq() + " 段（" + route + "）跨 " + days + " 天，通常还应有住宿票");
                result.add(vo);
            }
        }

        for (Invoice invoice : invoices) {
            if (invoice.getInvoiceDate() == null || !inAnyRange(invoice.getInvoiceDate(), itineraries)) {
                MissingTicketVO vo = new MissingTicketVO();
                vo.setItineraryId(invoice.getItineraryId());
                vo.setSeq(null);
                vo.setRoute(null);
                vo.setTicketType("日期存疑");
                vo.setHint("票号 " + invoice.getInvoiceNo() + " 的开票日期 "
                        + invoice.getInvoiceDate() + " 不在任何行程段内，请核对是否属于本次出差");
                result.add(vo);
            }
        }
        return result;
    }

    private boolean inAnyRange(LocalDate date, List<TripItinerary> itineraries) {
        for (TripItinerary item : itineraries) {
            if (item.getStartDate() == null || item.getEndDate() == null) {
                continue;
            }
            if (!date.isBefore(item.getStartDate()) && !date.isAfter(item.getEndDate())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 票据 VO。imgUrl 与 thumbUrl 必须成对给出，缺一个都不行。
     *
     * <p>这里原先还有一个只传 imgUrl 的两参重载，**已经删掉**。留着它的代价是：
     * 调用方以为 VO 组装好了，实际 thumbUrl 是 null，前端缩略图那格拿不到小图
     * 只能退回原图——财务复核页那张 44px 的小格子，每张都在拉几百 KB 的原图。
     * 页面既不报错、也不慢得明显，只有把图片原始尺寸打出来才发现对不上。
     * 删掉之后漏传就编译不过，比在注释里写一百遍「记得带」管用。
     *
     * <p>没有影像的票据（imgPath 为空）由 signedUrl / thumbUrl 返回空串，别传 null 进来
     * 指望它自动省掉——空串让前端一眼看出这张票缺影像。
     */
    public static InvoiceVO toVO(Invoice invoice, String imgUrl, String thumbUrl) {
        InvoiceVO vo = new InvoiceVO();
        vo.setId(invoice.getId());
        vo.setInvoiceNo(invoice.getInvoiceNo());
        vo.setApplyNo(invoice.getApplyNo());
        vo.setItineraryId(invoice.getItineraryId());
        vo.setAmount(invoice.getAmount());
        vo.setInvoiceDate(invoice.getInvoiceDate() == null ? null : invoice.getInvoiceDate().toString());
        vo.setSeller(invoice.getSeller());
        vo.setImgPath(invoice.getImgPath());
        vo.setImgUrl(imgUrl);
        vo.setThumbUrl(thumbUrl);
        vo.setOcrFlag(invoice.getOcrFlag());
        vo.setDupOf(invoice.getDupOf());
        return vo;
    }

    private String extension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx < 0 ? "" : filename.substring(idx + 1).toLowerCase();
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (Exception e) {
            throw BusinessException.paramError("开票日期格式不正确，应为 yyyy-MM-dd");
        }
    }

    /**
     * 影像链接过期时间的 LocalDateTime，前端展示用。
     */
    public LocalDateTime urlExpireAt() {
        return LocalDateTime.now(ZoneId.systemDefault()).plusMinutes(fileStorageConfig.getUrlExpireMinutes());
    }
}
