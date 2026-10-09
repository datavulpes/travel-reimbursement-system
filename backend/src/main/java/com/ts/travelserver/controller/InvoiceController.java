package com.ts.travelserver.controller;

import com.ts.travelserver.annotation.RequireRole;
import com.ts.travelserver.common.BusinessException;
import com.ts.travelserver.common.PageResult;
import com.ts.travelserver.common.R;
import com.ts.travelserver.dto.InvoiceMatchRequest;
import com.ts.travelserver.dto.InvoiceQuery;
import com.ts.travelserver.entity.SysUser;
import com.ts.travelserver.service.InvoiceService;
import com.ts.travelserver.vo.InvoiceListVO;
import com.ts.travelserver.vo.InvoiceMatchSuggestVO;
import com.ts.travelserver.vo.InvoiceUploadVO;
import com.ts.travelserver.vo.InvoiceVO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;

/**
 * 票据（模块 02）。
 *
 * <p>I-04 改成用 query 参数传路径：imgPath 形如 2026/09/invoice_xxx.jpg 带斜杠，
 * 用 @PathVariable("{path}") 会被截断，取不到完整路径（见接口开发文档第 2 节第 7 条）。
 */
@RestController
@RequestMapping("/invoice")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;

    /**
     * C-01 上传票据。文件落盘 + OCR 回显；识别失败只回 ocrSuccess=false，不抛异常。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<InvoiceUploadVO> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam("applyNo") String applyNo,
                                     @RequestParam(value = "skipOcr", required = false) Boolean skipOcr) {
        return R.ok(invoiceService.upload(file, applyNo, skipOcr));
    }

    /**
     * C-05 只问一句「这张票该挂到哪段」。
     *
     * <p>上传接口已经顺带返过一次建议，但那是用 OCR 的结果算的。OCR 把日期认错是常事，
     * 人在表单里改一下就得重算——总不能为了问这句话把图片再传一遍。
     * 只读，不落库。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @GetMapping("/match")
    public R<InvoiceMatchSuggestVO> match(@RequestParam("applyNo") String applyNo,
                                          @RequestParam(value = "invoiceDate", required = false) String invoiceDate,
                                          @RequestParam(value = "seller", required = false) String seller) {
        return R.ok(invoiceService.match(applyNo, invoiceDate, seller));
    }

    /**
     * C-02 确认票面字段并挂接行程段。写库前按票号查重，命中返回 2001 并带上原申请号。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @PostMapping("/confirm")
    public R<InvoiceVO> confirm(@Valid @RequestBody InvoiceMatchRequest request) {
        return R.ok("票据已挂接", invoiceService.confirm(request));
    }

    /**
     * C-03 本单票据清单 + 缺票推算。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER, SysUser.ROLE_FINANCE, SysUser.ROLE_MANAGER})
    @GetMapping("/list")
    public R<InvoiceListVO> list(@RequestParam String applyNo) {
        return R.ok(invoiceService.list(applyNo));
    }

    /**
     * C-04 删除未提交的票据。报销单已提交就不能删（BR-10）。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER})
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        invoiceService.delete(id);
        return R.<Void>ok("票据已删除", null);
    }

    /**
     * I-05 票据影像查询。
     *
     * <p>员工看自己的、审批人与管理层看自己审过的、财务看全部——范围在 Service 里按角色定死，
     * 前端不用也不能传 userId 之类的参数。
     * 管理层必须在这里：他是复审那一环，复审详情里同样摆票据影像。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER, SysUser.ROLE_FINANCE, SysUser.ROLE_MANAGER})
    @GetMapping("/search")
    public R<PageResult<InvoiceVO>> search(InvoiceQuery query) {
        return R.ok(invoiceService.search(query));
    }

    /**
     * I-06 单张票据重新取链。
     *
     * <p>影像地址带过期时间，页面停留久了图片会加载失败。
     * 前端拿到失败时调这个换一条新的，不用整页刷新。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER, SysUser.ROLE_FINANCE, SysUser.ROLE_MANAGER})
    @GetMapping("/{id}/url")
    public R<InvoiceVO> url(@PathVariable Long id) {
        return R.ok(invoiceService.url(id));
    }

    /**
     * I-04 票据影像。带签名与过期时间，验不过返回 3001。
     *
     * <p>OSS 模式下前端主要走预签名 URL 直连 OSS，这个接口是兜底：
     * 老链接、内网没放开 OSS 域名、或者想让流量过一遍后端做权限校验时都用它。
     */
    @RequireRole({SysUser.ROLE_EMPLOYEE, SysUser.ROLE_APPROVER, SysUser.ROLE_FINANCE, SysUser.ROLE_MANAGER})
    @GetMapping("/file")
    public void file(@RequestParam String path,
                     @RequestParam Long exp,
                     @RequestParam String sign,
                     HttpServletResponse response) throws Exception {
        String key = invoiceService.resolveSigned(path, exp, sign);
        byte[] bytes = invoiceService.readImage(key);
        if (bytes == null) {
            throw BusinessException.paramError("影像文件不存在");
        }
        response.setContentType(invoiceService.imageContentType(key));
        response.setContentLength(bytes.length);
        response.setHeader("Content-Disposition", "inline;filename=" + fileNameOf(key));
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
            out.flush();
        }
    }

    private static String fileNameOf(String key) {
        int idx = key == null ? -1 : key.lastIndexOf('/');
        return idx < 0 ? key : key.substring(idx + 1);
    }
}
