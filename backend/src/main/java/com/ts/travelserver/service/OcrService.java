package com.ts.travelserver.service;

import com.ts.travelserver.vo.OcrResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 票据 OCR 识别（全系统唯一的外部依赖）。
 *
 * <p>设计报告 7.6：识别票号、金额、开票日期、销售方四项回填表单，员工核对后确认；
 * 识别失败或额度用尽时返回 success=false，前端转手工录入，主流程不阻塞。
 *
 * <h3>为什么不用百度的「增值税发票识别」</h3>
 * <p>{@code vat_invoice} 是按真实发票模板匹配的，只有税务局监制的版式能命中，
 * 其余一律返回 {@code 282103 failed to match the template}——火车票、出租车票、
 * 行程单、以及联调用的自制票据全都被拒。这里改走「通用文字识别 + 结构化提取」：
 * 先把票面所有文字行取回来，再按标签提取四项。
 *
 * <p>代价是对真发票的准确率略低于模板接口（没有坐标信息帮忙定位列），
 * 好处是一套代码覆盖全部票种，换服务商也只改这个类。
 *
 * <h3>提取口径（照着 OCR 真实输出定的，不是猜的）</h3>
 * <ul>
 *   <li>票号：找「发票号码 / 发票号 / 票号」标签，取同行或随后 3 行内 15~25 位数字；
 *       再找不到就取全文最长的数字串。</li>
 *   <li>金额：优先「价税合计 / 小写 / 票价 / 金额 / 合计」标签随后 3 行内的 {@code ￥} 金额；
 *       都没有就取全文 {@code ￥} 金额的最大值（价税合计必然是票面最大数）。</li>
 *   <li>日期：优先「开票日期」行，其次全文第一个 {@code yyyy年MM月dd日}。
 *       刻意不取「乘车日期」——火车票上两个日期不一样，取错就核销不上。</li>
 *   <li>销售方：找「销售方」标签，同行没有就在随后 4 行内找「名称：」；
 *       都没有就取最后一个「名称：」行——购买方在前、销售方在后，取最后一个才不会取反。</li>
 * </ul>
 * <p>票号和金额缺任一项就判失败。只有日期和销售方没有票号，说明这根本不是一张票据
 * （比如手写收据），此时宁可让员工手工录入，也不能把垃圾值填进表单。
 */
@Slf4j
@Service
public class OcrService {

    /** 换 token 的接口 */
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    /** 通用文字识别（高精度版）。不用 vat_invoice，原因见类注释 */
    private static final String OCR_URL =
            "https://aip.baidubce.com/rest/2.0/ocr/v1/accurate_basic";

    /** 百度对图片大小的要求：base64 编码后不超过 4MB，原图按 3MB 卡 */
    private static final long MAX_IMAGE_BYTES = 3L * 1024 * 1024;

    private static final Pattern PT_NUM_LABEL =
            Pattern.compile("(发票号码|发票号|票据号码|票号| Number)");
    private static final Pattern PT_LONG_NUM = Pattern.compile("\\d{15,25}");
    // 金额标签分三级，顺序是照着真实票面排版定的：
    //   一级「小写」——增值税发票上紧跟它的就是价税合计，最准；
    //   二级「价税合计」——它后面那行是大写金额，再往后才是数字，隔了一行容易取错；
    //   三级「票价 / 金额 / 合计」——火车票、出租车票、行程单走这一档。
    // 不分级的话，「金额」是明细列的表头、位置最靠前，会先把不含税金额取回来
    // （1560 的票取成 1471.70）；倾斜票行序乱时「价税合计」也会抓错。
    private static final Pattern PT_AMOUNT_PRIMARY =
            Pattern.compile("(小写|TotalAmount)");
    private static final Pattern PT_AMOUNT_SECONDARY =
            Pattern.compile("(价税合计)");
    private static final Pattern PT_AMOUNT_TERTIARY =
            Pattern.compile("(票价|金额|合计)");
    private static final Pattern PT_MONEY =
            Pattern.compile("[￥¥]\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)");
    private static final Pattern PT_DATE_LABEL = Pattern.compile("(开票日期|日期|Date)");
    private static final Pattern PT_CN_DATE =
            Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    private static final Pattern PT_ISO_DATE = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");
    private static final Pattern PT_SELLER_LABEL = Pattern.compile("销售方");
    private static final Pattern PT_NAME_LINE = Pattern.compile("名称[：:]\\s*(.+)");
    private static final Pattern PT_COMPANY = Pattern.compile("[\\u4e00-\\u9fa5（）()·\\-]{4,}(有限公司|股份|公司|集团|铁路局|航空)");

    @Value("${travel.ocr.enabled:false}")
    private boolean enabled;

    @Value("${travel.ocr.app-id:}")
    private String appId;

    @Value("${travel.ocr.api-key:}")
    private String apiKey;

    @Value("${travel.ocr.secret-key:}")
    private String secretKey;

    @Value("${travel.ocr.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${travel.ocr.read-timeout-ms:10000}")
    private int readTimeoutMs;

    @Value("${travel.ocr.retry-times:1}")
    private int retryTimes;

    private final ObjectMapper objectMapper;

    /** 百度 token 有效期 30 天，缓存在内存里，别每次上传都去换一次 */
    private volatile String cachedToken;
    private volatile long tokenExpireAt = 0L;

    public OcrService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 识别一张票据影像。
     *
     * @return 识别结果；失败时 success=false，message 里给原因
     */
    /**
     * 识别一张本地磁盘上的票据影像（保留给工具类调用）。
     */
    public OcrResultVO recognize(File file) {
        if (file == null || !file.exists()) {
            return failed("影像文件不存在");
        }
        try {
            return recognize(Files.readAllBytes(file.toPath()), file.length());
        } catch (Exception e) {
            log.warn("读取票据影像失败：{}", e.getMessage());
            return failed("影像读取失败，请重新上传");
        }
    }

    /**
     * 识别一段影像字节。
     *
     * <p>影像改走 OSS 之后，文件不一定在本机磁盘上，上传时手里只有 MultipartFile 的字节，
     * 所以真正干活的重载吃 byte[]；上面那个 File 版本只是顺手读盘再转进来。
     *
     * @return 识别结果；失败时 success=false，message 里给原因
     */
    public OcrResultVO recognize(byte[] bytes, long size) {
        if (!enabled) {
            return failed("OCR 未开启，请手工录入票面信息");
        }
        if (bytes == null || bytes.length == 0) {
            return failed("影像内容为空");
        }
        if (!org.springframework.util.StringUtils.hasText(apiKey)
                || !org.springframework.util.StringUtils.hasText(secretKey)) {
            return failed("OCR 密钥未配置，请手工录入票面信息");
        }

        if (size > MAX_IMAGE_BYTES) {
            return failed("影像超过 3MB，服务商不接受，请压缩后再传");
        }

        // 失败重试 retryTimes 次，超时直接放弃，不让上传接口一直挂着
        for (int i = 0; i <= retryTimes; i++) {
            try {
                OcrResultVO vo = callProvider(bytes);
                if (vo != null && Boolean.TRUE.equals(vo.getSuccess())) {
                    return vo;
                }
                if (vo != null && vo.getMessage() != null
                        && vo.getMessage().contains("配额")) {
                    // 额度用尽重试也没用，直接返回
                    return vo;
                }
            } catch (Exception e) {
                log.warn("第 {} 次 OCR 调用失败：{}", i + 1, e.getMessage());
            }
        }
        return failed("OCR 识别失败，请手工录入票面信息");
    }

    /**
     * 调百度 OCR：换 token → 传 base64 → 解析文字行 → 提取四项。
     */
    private OcrResultVO callProvider(byte[] bytes) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        String token = accessToken(client);
        if (token == null) {
            return failed("OCR 鉴权失败，请检查 travel.ocr.api-key / secret-key");
        }

        String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
        String body = "image=" + URLEncoder.encode(base64, StandardCharsets.UTF_8)
                + "&recognize_granularity=small"
                + "&probability=false";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OCR_URL + "?access_token=" + token))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String payload = response.body();
        if (response.statusCode() != 200 || payload == null || payload.isBlank()) {
            log.warn("OCR 返回异常，HTTP {}：{}", response.statusCode(), payload);
            return failed("OCR 服务返回异常（HTTP " + response.statusCode() + "）");
        }

        JsonNode root = objectMapper.readTree(payload);
        if (root.get("error_code") != null) {
            int code = root.get("error_code").asInt();
            String msg = root.get("error_msg") == null ? "" : root.get("error_msg").asString();
            log.warn("OCR 接口报错 {}: {}", code, msg);
            if (code == 17 || code == 19 || code == 111 || code == 216633) {
                return failed("OCR 每日调用额度已用尽，请手工录入票面信息");
            }
            if (code == 110 || code == 111 || code == 6) {
                return failed("OCR 密钥无效（" + code + "），请检查配置");
            }
            return failed("OCR 识别失败（" + code + " " + msg + "），请手工录入票面信息");
        }

        List<String> lines = new ArrayList<>();
        JsonNode words = root.get("words_result");
        if (words != null && words.isArray()) {
            for (JsonNode node : words) {
                JsonNode w = node.get("words");
                if (w != null) {
                    lines.add(w.asString().trim());
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("OCR 识别到 {} 行：{}", lines.size(), lines);
        }
        return extract(lines);
    }

    /**
     * 从文字行里提取票号、金额、日期、销售方。
     * 票号与金额缺一即判失败——没有这两项基本说明这张图不是票据。
     */
    private OcrResultVO extract(List<String> lines) {
        OcrResultVO vo = new OcrResultVO();
        String invoiceNo = findInvoiceNo(lines);
        String amount = findAmount(lines);

        if (invoiceNo == null || amount == null) {
            vo.setSuccess(false);
            vo.setMessage("没认出票号或金额，请手工录入票面信息");
            log.info("OCR 未提取到票号/金额，判定为识别失败（行数 {}）", lines.size());
            return vo;
        }

        vo.setInvoiceNo(invoiceNo);
        vo.setAmount(new BigDecimal(amount));
        vo.setInvoiceDate(findDate(lines));
        vo.setSeller(findSeller(lines));
        vo.setSuccess(true);
        log.info("OCR 识别结果：票号 {} 金额 {} 日期 {} 销售方 {}",
                vo.getInvoiceNo(), vo.getAmount(), vo.getInvoiceDate(), vo.getSeller());
        return vo;
    }

    // ---------- 四项提取 ----------

    /**
     * 票号。发票号码可能和号码不在同一行（增值税发票上「发票号码」是标签，
     * 号码在下面几行），所以标签命中后要向下看几行。
     */
    private String findInvoiceNo(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!PT_NUM_LABEL.matcher(lines.get(i)).find()) {
                continue;
            }
            Matcher m = PT_LONG_NUM.matcher(lines.get(i));
            if (m.find()) {
                return m.group();
            }
            for (int j = i + 1; j < Math.min(i + 4, lines.size()); j++) {
                m = PT_LONG_NUM.matcher(lines.get(j));
                if (m.find()) {
                    return m.group();
                }
            }
        }
        // 兜底：全文取最长的数字串
        String best = null;
        for (String line : lines) {
            Matcher m = PT_LONG_NUM.matcher(line);
            while (m.find()) {
                if (best == null || m.group().length() > best.length()) {
                    best = m.group();
                }
            }
        }
        return best;
    }

    /**
     * 金额。票面上通常同时有不含税金额、税额、价税合计三个数，要的是价税合计。
     *
     * <p>依次按「小写」→「价税合计」→「票价 / 金额 / 合计」三级标签找；
     * 都没有就认票面最大的 ￥ 数——价税合计 = 金额 + 税额，必然是票面最大的那个。
     */
    private String findAmount(List<String> lines) {
        String byPrimary = amountNear(lines, PT_AMOUNT_PRIMARY);
        if (byPrimary != null) {
            return byPrimary;
        }
        String bySecondary = amountNear(lines, PT_AMOUNT_SECONDARY);
        if (bySecondary != null) {
            return bySecondary;
        }
        String byTertiary = amountNear(lines, PT_AMOUNT_TERTIARY);
        if (byTertiary != null) {
            return byTertiary;
        }
        BigDecimal max = null;
        String raw = null;
        for (String line : lines) {
            Matcher m = PT_MONEY.matcher(line);
            while (m.find()) {
                try {
                    BigDecimal v = new BigDecimal(m.group(1).replace(",", ""));
                    if (max == null || v.compareTo(max) > 0) {
                        max = v;
                        raw = m.group(1).replace(",", "");
                    }
                } catch (NumberFormatException ignored) {
                    // 认出来的不是数字就跳过
                }
            }
        }
        return raw;
    }

    /**
     * 在含指定标签的行、以及它随后 3 行内找 ￥ 金额。
     * 票面排版里标签和数字常常不在同一行（增值税发票尤其明显）。
     */
    private String amountNear(List<String> lines, Pattern label) {
        for (int i = 0; i < lines.size(); i++) {
            if (!label.matcher(lines.get(i)).find()) {
                continue;
            }
            Matcher m = PT_MONEY.matcher(lines.get(i));
            if (m.find()) {
                return m.group(1).replace(",", "");
            }
            for (int j = i + 1; j < Math.min(i + 4, lines.size()); j++) {
                m = PT_MONEY.matcher(lines.get(j));
                if (m.find()) {
                    return m.group(1).replace(",", "");
                }
            }
        }
        return null;
    }

    /**
     * 开票日期。优先「开票日期」标签，避免把火车票的「乘车日期」当成开票日期。
     */
    private String findDate(List<String> lines) {
        for (String line : lines) {
            if (PT_DATE_LABEL.matcher(line).find()) {
                String d = dateIn(line);
                if (d != null) {
                    return d;
                }
            }
        }
        for (String line : lines) {
            String d = dateIn(line);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    private String dateIn(String line) {
        Matcher m = PT_CN_DATE.matcher(line);
        if (m.find()) {
            return String.format("%s-%02d-%02d",
                    m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        m = PT_ISO_DATE.matcher(line);
        if (m.find()) {
            return String.format("%s-%02d-%02d",
                    m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        return null;
    }

    /**
     * 销售方。增值税发票上「销售方」是独立标签，公司名在下面几行的「名称：」里；
     * 火车票、出租车票上则是「销售方：xxx」写在同一行。
     */
    private String findSeller(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!PT_SELLER_LABEL.matcher(line).find()) {
                continue;
            }
            String inline = afterColon(line, "销售方");
            if (inline != null && inline.length() >= 4) {
                return inline;
            }
            for (int j = i + 1; j < Math.min(i + 5, lines.size()); j++) {
                Matcher m = PT_NAME_LINE.matcher(lines.get(j));
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        }
        // 没有「销售方」标签：取最后一个「名称：」行。购买方在前、销售方在后，
        // 取最后一个才不会取反。
        String last = null;
        for (String line : lines) {
            Matcher m = PT_NAME_LINE.matcher(line);
            if (m.find()) {
                last = m.group(1).trim();
            }
        }
        if (last != null) {
            return last;
        }
        // 再退一步：全文最后一个像公司名的串
        for (String line : lines) {
            Matcher m = PT_COMPANY.matcher(line);
            if (m.find() && !line.contains("购买方")) {
                last = m.group().trim();
            }
        }
        return last;
    }

    private String afterColon(String line, String label) {
        int idx = line.indexOf(label);
        if (idx < 0) {
            return null;
        }
        String rest = line.substring(idx + label.length()).trim();
        rest = rest.replaceAll("^[：:]\\s*", "").trim();
        return rest.isEmpty() ? null : rest;
    }

    // ---------- 鉴权 ----------

    /**
     * 换 access_token。缓存在内存，过期前 5 分钟提前刷新，避免临界点拿到失效 token。
     */
    private String accessToken(HttpClient client) throws Exception {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpireAt) {
            return cachedToken;
        }
        String body = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secretKey, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode token = root.get("access_token");
        if (token == null) {
            log.warn("换取 OCR token 失败：{}", response.body());
            return null;
        }
        long expires = root.get("expires_in") == null ? 2592000L : root.get("expires_in").asLong();
        cachedToken = token.asString();
        tokenExpireAt = System.currentTimeMillis() + (expires - 300) * 1000L;
        if (log.isDebugEnabled()) {
            log.debug("OCR token 已刷新，有效期 {} 秒", expires);
        }
        return cachedToken;
    }

    private static OcrResultVO failed(String message) {
        OcrResultVO vo = new OcrResultVO();
        vo.setSuccess(false);
        vo.setMessage(message);
        return vo;
    }

    /** 给运维/联调看的：当前用的是哪套密钥，不打印密钥本身 */
    public String describe() {
        return "provider=baidu(accurate_basic) appId=" + appId
                + " enabled=" + enabled
                + " keyConfigured=" + org.springframework.util.StringUtils.hasText(apiKey);
    }
}
