package com.ts.travelserver.ai;

import com.ts.travelserver.config.AiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实模型通道：一个 HTTP 客户端打 OpenAI 兼容的 {@code /chat/completions}。
 *
 * <p>只在这个类里出现厂商相关的东西。换 DeepSeek / 智谱 / 本地 Ollama
 * 都只是改 yml 的三个值，Java 代码一行不动。
 *
 * <h3>两个实测出来的坑</h3>
 * <ul>
 *   <li>国产阵营大多只有 {@code response_format=json_object}：保证是合法 JSON，
 *       不保证字段名与嵌套。所以 schema 要写死在 prompt 里，且后端必须自己校验。</li>
 *   <li>模型偶尔会给输出包一层 {@code ```json} 代码块，这里统一剥掉再交给上层解析。</li>
 * </ul>
 *
 * <p>任何异常都不往外抛：返回 null，调用方降级。AI 不可用不能让提交失败。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "travel.ai", name = "enabled", havingValue = "true")
public class RemoteAiService implements AiService {

    private final AiConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RemoteAiService(AiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, config.getConnectTimeoutMs())))
                .build();
        if (!config.usable()) {
            log.warn("travel.ai.enabled=true 但 base-url / model / api-key 没配全，"
                    + "AI 能力将全部降级为本地启发式");
        }
    }

    @Override
    public boolean remote() {
        return config.usable();
    }

    @Override
    public String model() {
        return config.getModel();
    }

    @Override
    public AiTextResult complete(String scene, String inputJson) {
        return complete(scene, inputJson, null);
    }

    @Override
    public AiTextResult complete(String scene, String inputJson, Map<String, String> vars) {
        if (!config.usable()) {
            return null;
        }
        long start = System.currentTimeMillis();
        try {
            // 没给变量表就按老规矩把整段入参填进 {input}
            String prompt = vars == null ? AiPrompts.render(scene, inputJson) : AiPrompts.render(scene, vars);
            String body = buildBody(scene, prompt);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint()))
                    .timeout(Duration.ofMillis(config.readTimeoutMs(scene)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;
            if (response.statusCode() != 200) {
                // 只记状态码与返回体前 200 字符，不要把密钥打进日志
                log.warn("AI 调用失败，场景 {}，HTTP {}，返回：{}", scene, response.statusCode(),
                        clip(response.body(), 200));
                return null;
            }
            String content = extractContent(response.body());
            if (content == null || content.isBlank()) {
                log.warn("AI 返回为空，场景 {}", scene);
                return null;
            }
            AiTextResult.AiTextResultBuilder b = AiTextResult.builder()
                    .content(content)
                    .model(config.getModel())
                    .costMs(cost)
                    .remote(true);
            JsonNode usage = readUsage(response.body());
            if (usage != null) {
                b.requestTokens(intOrNull(usage.get("prompt_tokens")));
                b.responseTokens(intOrNull(usage.get("completion_tokens")));
            }
            return b.build();
        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("AI 调用超时（{} ms），场景 {}，本次降级", config.readTimeoutMs(scene), scene);
            return null;
        } catch (Exception e) {
            log.warn("AI 调用异常，场景 {}，本次降级：{}", scene, e.getMessage());
            return null;
        }
    }

    // ---------- 内部实现 ----------

    /**
     * 不经过模板、直接发一段 prompt 的调用。问数助手（DATA_QA）专用。
     *
     * <p>与 {@link #complete} 走同一个 {@code buildBody}：模型名、超时、maxTokens、
     * thinking 这些场景级覆盖全部沿用。差别只在 prompt 不是模板渲染出来的，
     * 而是调用方自己拼好的（能力目录 + 人问的话）。
     *
     * <p>任何异常都不往外抛：返回 null，调用方降级。AI 不可用不能让问数请求失败。
     */
    public AiTextResult completeRaw(String scene, String prompt) {
        if (!config.usable() || prompt == null || prompt.isBlank()) {
            return null;
        }
        long start = System.currentTimeMillis();
        try {
            String body = buildBody(scene, prompt);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint()))
                    .timeout(Duration.ofMillis(config.readTimeoutMs(scene)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;
            if (response.statusCode() != 200) {
                log.warn("AI 原始调用失败，场景 {}，HTTP {}，返回：{}", scene, response.statusCode(),
                        clip(response.body(), 200));
                return null;
            }
            String content = extractContent(response.body());
            if (content == null || content.isBlank()) {
                log.warn("AI 原始调用返回为空，场景 {}", scene);
                return null;
            }
            AiTextResult.AiTextResultBuilder b = AiTextResult.builder()
                    .content(content)
                    .model(config.getModel())
                    .costMs(cost)
                    .remote(true);
            JsonNode usage = readUsage(response.body());
            if (usage != null) {
                b.requestTokens(intOrNull(usage.get("prompt_tokens")));
                b.responseTokens(intOrNull(usage.get("completion_tokens")));
            }
            return b.build();
        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("AI 原始调用超时（{} ms），场景 {}，本次降级", config.readTimeoutMs(scene), scene);
            return null;
        } catch (Exception e) {
            log.warn("AI 原始调用异常，场景 {}，本次降级：{}", scene, e.getMessage());
            return null;
        }
    }

    /**
     * 请求体。system 里放通用约束，user 里放具体输入与 schema——
     * 分两段是为了让 schema 离输入更近，模型更容易照着给。
     */
    private String buildBody(String scene, String prompt) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", config.getModel());
        root.put("temperature", config.temperature(scene));
        root.put("max_tokens", config.maxTokens(scene));
        root.put("response_format", Map.of("type", "json_object"));
        // 非标准字段，厂商各自扩展：为 null 时整个不带，避免不认这个字段的厂商直接 400
        Boolean thinking = config.thinking(scene);
        if (thinking != null) {
            root.put("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        root.put("messages", List.of(message));
        return objectMapper.writeValueAsString(root);
    }

    /**
     * 地址兼容两种写法：填到 /v1 的根路径，或已经写全的 /chat/completions。
     */
    private String endpoint() {
        String base = config.getBaseUrl().trim();
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    /**
     * 取 choices[0].message.content，顺手剥掉模型爱加的代码块围栏。
     */
    private String extractContent(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode choices = root == null ? null : root.get("choices");
            if (choices == null || choices.size() == 0) {
                return null;
            }
            JsonNode message = choices.get(0).get("message");
            JsonNode content = message == null ? null : message.get("content");
            if (content == null || content.isNull()) {
                return null;
            }
            String text = content.asString();
            return stripFence(text);
        } catch (Exception e) {
            log.warn("AI 返回解析失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * usage 节点。解析失败返回 null——token 是锦上添花，拿不到不影响这次调用本身。
     */
    private JsonNode readUsage(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            return root == null ? null : root.get("usage");
        } catch (Exception e) {
            return null;
        }
    }

    private Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asInt();
    }

    private String stripFence(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('\n');
            int last = trimmed.lastIndexOf("```");
            if (first > 0 && last > first) {
                return trimmed.substring(first + 1, last).trim();
            }
        }
        return trimmed;
    }

    private String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
