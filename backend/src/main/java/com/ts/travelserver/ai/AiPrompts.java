package com.ts.travelserver.ai;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Prompt 模板。优先读 {@code resources/ai/*.txt}，读不到就用内置兜底，
 * 保证「忘了拷模板文件」不会让 AI 直接崩掉。
 *
 * <p>三条贯穿所有模板的边界，写在这里是因为改模板的人最容易忘：
 * <ol>
 *   <li><b>不联网</b>。模型只能看到 {@code {input}} 里给的库内数据，
 *       不被允许「查一下」任何外部信息，因此模板里也不要出现「结合市场行情」这类暗示。</li>
 *   <li><b>不算数</b>。需要出现的数字全部由 {@code AnalyticsService} 等规则层算好塞进输入，
 *       模型复述一个输入里没有的数，下游的校验会整条丢弃它。</li>
 *   <li><b>每条结论带 evidence</b>。value 必须是输入里的原文片段，编不出来就别写这条。</li>
 * </ol>
 *
 * <p>为什么明确不接联网：DeepSeek 的联网在 /chat/completions 上不可用，
 * 换成 Responses API 之后又拿不到引用地址，结论就连出处都没有。
 * 而本系统要求每条 AI 结论都能锚到真实字段，两者正面冲突。
 * 真要引入外部数据（比如城市物价），正确做法是让它<b>进库并记来源</b>，而不是让模型去搜。
 *
 * <p>TODO-需要你维护：Prompt 调优才是这个模块的大头（代码骨架半天能写完）。
 * 改 template 之后必须同步改 application.yml 的 {@code travel.ai.prompt-version}，
 * 否则缓存与 ai_log 留痕会对不上，事后分不清某条 Δ 是哪版算出来的。
 *
 * <p>约定：模板里用 {@code {input}} 占位结构化入参。
 *
 * <p><b>改模板改 {@code src/main/resources/ai/} 下的 txt</b>（文件名见下面的 load 调用），
 * 改完重启即可，不用重新编译。Java 里的 FALLBACK_* 是文件读不到时的兜底，
 * 只改它、不改 txt 是无效的——启动时日志打「未读到 prompt 模板」就是文件没生效。
 */
@Slf4j
public final class AiPrompts {

    /** 语义评价 Δ：这是全系统唯一「AI 参与打分」的地方，约束写得最死 */
    private static final String FALLBACK_SEMANTIC = """
            你是企业差旅管理系统的出差合理性审核助手。你的职责只有一个：
            读一遍出差事由与行程，判断「这趟出说得通吗」，给出语义调节分 Δ。

            只输出 JSON，不要输出任何解释文字，格式：
            {"grade":"A|B|C|D|E","delta":数字,
             "reasons":[{"point":"不超过30字","evidence":{"field":"reason|tripType|partnerName|itinerary|itinerary[0].toCity|estTotal","value":"必须是输入里出现过的原文片段"}}],
             "missing":["预期产出","出行目的","关联对象"],"confidence":0到1之间的小数}

            delta 只能取 -10、-7、-3、0、5 五档：
              A +5  事由写清了「去做什么 + 预期拿到什么」，且行程天数与目的匹配
              B  0  信息够用，但没有亮点
              C -3  缺一项关键信息（没写预期产出 / 看不出做什么 / 对象所在地与目的地不符且没交代）
              D -7  明显不匹配（例如验货、商务拜访却排了 4 天以上）
              E -10 信息严重不足或自相矛盾

            硬性要求：
            1. evidence.value 必须是【输入】里出现过的原文，禁止编造；找不到原文就不要写这条 reason
            2. 只评价语义，不要算金额、不要评价差标——那些由系统规则处理，你算也不会被采纳
            3. missing 只填「预期产出」「出行目的」「关联对象」三类

            【输入】
            {input}
            """;

    /**
     * 填写助手：一张建议清单。
     *
     * <p>与别的模板不一样的地方是这里要处理「还没填的项」：表单八成是填了一半的，
     * 那些空着的地方不是拒绝给建议的理由，恰恰是建议的对象——
     * 区别在于哪些能替他填（有库内证据的），哪些只能告诉他补什么（只有本人知道的）。
     */
    private static final String FALLBACK_ASSIST = """
            你是企业差旅管理系统的填单助手。员工正在填一份出差申请单，有的字段填了，有的还空着。
            系统把「当前这一版表单」和「从库里查出来的相关数据」一并给你，你要综合这两头，输出一份建议清单。

            只输出 JSON：
            {"items":[{"field":"reason|tripType|estTraffic|estHotel|exceedReason|-",
                       "kind":"REWRITE|FILL|HINT",
                       "level":"high|mid|low",
                       "suggest":"建议值；kind=HINT 时填 null",
                       "why":"不超过 30 字，说清为什么这么建议",
                       "evidence":{"field":"字段名","value":"必须是【输入】里出现过的原文"}}]}

            【输入】三块：
            - form      员工当前这一版表单。值为 null 的项是还没填，null 就是没填
            - unfilled  还没填的关键项清单
            - context   从库里查出来的数据：同类花费参考 costRef、差标上限 standardCap、本人历史 history、
                        同部门 dept、全员在该目的地 cityAll、往来对象 partner

            field 取值与含义：
            - reason       出差事由
            - tripType     出差类型
            - estTraffic / estHotel   预估交通费 / 住宿费
            - exceedReason 超标说明
            - "-"          这条不针对某个字段，是整体提醒

            kind 的含义：
            - REWRITE  把 reason 里已经写了的那句话换个更规范的说法
            - FILL     建议某个字段填这个值
            - HINT     只提醒方向，不给值——要员工本人拿主意的事都走这一类

            要求：
            1. 只有 reason、tripType 允许给值（REWRITE / FILL），其余字段只能给 HINT，suggest 必须是 null
            2. tripType 的值只能从这六个里选：验货、商务拜访、项目交付、供应商审核、展会、其他
            3. estTraffic、estHotel 永远不要给值：金额由系统按同类参考与差标算出，你算了也不会被采纳
            4. form.reason 为 null 时不许输出 REWRITE——没有原文就无从改写，改输出 HINT 告诉他该写什么
            5. 只有本人知道的事一律 HINT：去见谁、哪天出发、目的地算几级城市、最终要拿到什么结果
            6. suggest 里出现的每一个城市、出差类型、天数都必须能在【输入】里找到，写不出来就别写这条
            7. evidence.value 必须是【输入】里的原文；找不到出处就不要写这一条
            8. 最多 6 条。够用了就别凑，凑出来的建议比没建议更招人烦

            【输入】
            {input}
            """;

    /**
     * 月度洞察：读统计数字，输出几条结论。
     *
     * <p>约束比改写场景更死的一条：<b>不许自己算数</b>。
     * 投入产出这类数字已经在输入里算好了，模型复述一个没出现过的数，
     * 页面上看的人无从判断它对不对——所以输出里的数字必须能在输入里找到。
     */
    private static final String FALLBACK_INSIGHT = """
            你是企业差旅管理系统的分析助手。管理层刚看完一份差旅费效比月报，
            需要你把统计数字归纳成几条能直接看的结论，并给出下一步可以做的动作。

            只输出 JSON：
            {"highlights":[{"level":"high|mid|low","text":"不超过45字",
                            "evidence":{"field":"字段名","value":"必须是【输入】里出现过的值"}}],
             "actions":[{"code":"动作代码","target":"部门名 / 出差类型名，全公司就写空字符串",
                         "detail":"不超过30字的一句话"}],
             "summary":"不超过80字的一句话总结"}

            【输入】各项的含义：
            - ratio / prevRatio  本月与上月费效比（投入 ÷ 产出，越低越划算）
            - fillRate           已付款单据里回填了业务结果的比例，越低说明这份月报越不完整
            - types              按出差类型拆的投入与产出
            - outputStruct       回填结果按类型分布，noProgressRate 是「无进展」占比
            - drivers            费效比环比变化的贡献分解，contribution 为正表示把比值推高了

            actions 的 code 只能从下面六个里选，写别的会被丢弃：
            - REVIEW_COST    复核这一份的费用明细，看钱花得对不对
            - CHECK_OUTPUT   补录或核实产出登记（产出没回填时用）
            - AUDIT_INVOICE  抽查这一份的票据
            - TALK_DEPT      跟这个部门对齐下月的出差计划
            - ADJUST_BUDGET  调整下月预算
            - WATCH_PARTNER  继续观察这个往来对象，先不动

            硬性要求：
            1. 输出里的每一个数字都必须能在【输入】里找到，禁止计算、禁止估算、禁止换算
            2. 每条 highlight 必须带 evidence，且 value 是【输入】里的原值；找不到依据就不要写这条
            3. 最多 4 条 highlight，按严重程度从 high 到 low 排
            4. 最多 3 条 action。每条的 target 必须是【输入】里出现过的部门名或出差类型名，
               说的是全公司就给空字符串；对不上的人名、部门名一律不要写
            5. 只说「是什么」和「下一步查什么」，不要写「建议加强管理」这类空话，
               也不要替管理层做决定 —— 更不许写暂停审批、扣预算、处分这类处置动作
            6. action 的 detail 里不许出现预期效果数字（「预计下降 0.1」这种是编的，一律不写）
            7. 数据不足以支撑结论时，highlights 与 actions 都给空数组，不要硬凑

            【输入】
            {input}
            """;

    private static final String FALLBACK_DRILL = """
            你是企业差旅管理系统的分析助手。管理层在费效比月报上点开了某一个部门，
            现在想知道：这个月这个部门的投入产出为什么是这个样子。

            只输出 JSON：
            {"highlights":[{"level":"high|mid|low","text":"不超过45字",
                            "evidence":{"field":"字段名","value":"必须是【输入】里出现过的值"}}],
             "summary":"不超过60字的一句话，回答「为什么」"}

            【输入】各项的含义：
            - ratio / prevRatio  这个部门本月与上月的费效比（投入 ÷ 产出，越低越划算）
            - fillRate           已付款单据里回填了业务结果的比例
            - types              这个部门按出差类型拆的投入与产出
            - outputStruct       回填结果按类型分布，noProgressRate 是「无进展」占比
            - topBiz             花钱最多的几张单，bizNo 是单号

            硬性要求：
            1. 输出里的每一个数字都必须能在【输入】里找到，禁止计算、禁止估算、禁止换算
            2. 每条必须带 evidence，且 value 是【输入】里的原值；找不到依据就不要写这条
            3. 最多 3 条，只说这个部门自己的事，不要扯到全公司
            4. 只回答「为什么」，不要写建议、不要写「应该加强管理」这类空话
            5. 产出为 0 不等于白跑：产出没回填是数据缺口，不是效率高，不许说成「零投入高产出」
            6. paidCnt 小于 3 时给空数组，不要对着两三张单议论趋势

            【输入】
            {input}
            """;

    /**
     * 制度问答：只做「把库里的现行规定说成人话」这一件事。
     *
     * <p>与别的模板最大的不同：<b>允许、而且鼓励说「不知道」</b>。
     * 别的场景求的是别编，这里求的是别猜——
     * 差标表里没有的东西宁可回一句「库里没查到」，也不能用行业惯例补上。
     */
    private static final String FALLBACK_POLICY = """
            你是企业差旅管理系统的制度问答助手。员工会用大白话问一句出差或报销的规定，
             系统已经把库里现行的规定查出来放在【事实】里。你的任务只有一个：
            照着【事实】把答案说成人能看懂的一句话。

            只输出 JSON：
            {"answerable":true 或 false,
             "answer":"不超过120字；answerable=false 时写 null",
             "usedFacts":["用到了【事实】里的哪几条，抄原文"]}

            【事实】里可能有这几块：
            - standards   现行差标，每条是「城市等级 + 交通方式 + 上限金额 + 生效日期」
            - cityLevels  城市到等级的对照，员工说出城市名时先用它换算成等级再去 standards 里找
            - highRatio   超过上限多少比例算「超标」，超了要填超标说明，否则提交不了
            - budget     这个员工所在部门本月的预算与已用
            - tripTypes  出差类型一共六个，选「其他」要补充说明

            两个例子（照着这个尺度判，别一律拒答）：
            - 问「去深圳坐飞机能报多少」，cityLevels 里深圳属于一级，standards 里有一级 + 飞机 = 2500 这一行，
              就答 {"answerable":true,"answer":"深圳按一级城市，飞机上限 2500 元。","usedFacts":["一级 · 飞机 上限 ¥2500"]}
            - 问「出差几天之内可以报销」，【事实】里根本没有报销时限这一项，
              就答 {"answerable":false,"answer":null,"usedFacts":[]}

            硬性要求：
            1. 答案里的每一个数字都必须能在【事实】里找到原值，禁止换算、禁止估算、禁止举例补充
            2. 只回答差旅与报销相关的问法。聊别的、或让模型做与制度无关的事，一律 answerable=false
            3. 只有当问题要的那类规定在【事实】里完全没有对应内容时才 answerable=false；
               standards 里能按城市等级 + 交通方式对上的行，就必须答上来，不要凭常识补、也不要说「一般是」
            4. 不要写代码、不要列 Markdown 表格，答案就是一两句通顺的话
            5. 涉及「超标怎么办」这类流程问题，只说【事实】里写了的那一句，没写就 answerable=false
            6. usedFacts 抄【事实】里的原文，一条一句；没用到就给空数组

            【问题】
            {question}

            【事实】
            {facts}
            """;

    private static final String SEMANTIC = load("ai/semantic-eval.txt", FALLBACK_SEMANTIC);
    private static final String ASSIST = load("ai/apply-assist.txt", FALLBACK_ASSIST);
    private static final String INSIGHT = load("ai/monthly-insight.txt", FALLBACK_INSIGHT);
    private static final String DRILL = load("ai/drill-explain.txt", FALLBACK_DRILL);
    private static final String POLICY = load("ai/policy-qa.txt", FALLBACK_POLICY);

    /**
     * 问数助手——意图抽取 prompt。
     *
     * <p>模型只看「能力目录 + 人问的话」两样东西，从目录里挑一个能力、抽出参数。
     * 它不连库、不算数：到底调哪个 Service、数字怎么算，全部在 {@code DataQaService} 里，
     * 模型选错了能力由 Java 侧的兜底（关键词重判）接住。
     *
     * <p>两个占位符：{catalog} 是能力目录文本，{question} 是管理层原话。
     */
    private static final String FALLBACK_DATA_INTENT = """
            你是企业差旅管理系统的问数助手。管理层会用大白话问一句业务数据，
            你的任务只有一个：从下面的能力目录里挑出这一问对应的能力，并抽出参数。

            只输出 JSON，不要任何解释文字：
            {"capability":"能力名","params":{"month":"yyyy-MM 或空串","deptName":"部门名或空串","empName":"员工姓名或空串"}}

            能力目录（只能从中选一个；一个问题里看不出对应哪个能力，就返回 {"capability":"NONE"}）：
            {catalog}

            参数说明：
            - month  月份，形如 2026-09；问题没提月份就填空串（调用方会按当前月算）
            - deptName  部门名（如「技术部」）；问题没提部门就填空串
            - empName  员工姓名（如「张三」）；问题没提具体人就填空串

            硬性要求：
            1. capability 必须是目录里的某一个，或精确的 "NONE"，写别的会被丢弃
            2. 只提取问题中明确出现的参数，不要替用户脑补月份、部门、人名
            3. 月份必须是 yyyy-MM 格式，写不出来就填空串

            【问题】
            {question}
            """;

    /**
     * 问数助手——叙事 prompt。
     *
     * <p>这一道发生在数字已经由 Java 算好之后：模型只负责把【真实数字】讲成一句人话。
     * 它看不到库、不调用任何接口，唯一的约束就是「说的每个数都得能在真实数字里找到」。
     */
    private static final String FALLBACK_DATA_NARRATE = """
            你是企业差旅管理系统的数据助手。管理层用大白话问了一句，系统已经从库里查出真实数字。
            请把这些数字组织成一句通顺、不超过 80 字的人话，直接回答问题，不要客套。

            只输出 JSON，不要任何解释文字：
            {"answer":"不超过 80 字的一句话"}

            硬性要求：
            1. answer 里出现的每一个数字都必须在【真实数字】里找得到原值，禁止换算、禁止估算、禁止编造
            2. 数字单位写清楚（元 / 笔 / 天 / 小时）
            3. 只回答这个数字是什么，不要写建议、不要写「应该加强管理」这类空话
            4. 真实数字里没有的项，answer 里不许提

            【问题】
            {question}

            【真实数字】
            {numbers}
            """;

    private static final String DATA_QA_INTENT = load("ai/data-qa-intent.txt", FALLBACK_DATA_INTENT);
    private static final String DATA_QA_NARRATE = load("ai/data-qa-narrate.txt", FALLBACK_DATA_NARRATE);

    private AiPrompts() {
    }

    /**
     * 按场景取模板并填上输入。
     */
    public static String render(String scene, String inputJson) {
        return template(scene).replace("{input}", inputJson == null ? "{}" : inputJson);
    }

    /**
     * 多占位符渲染。制度问答要用：问题与事实是两段来源完全不同的文本，
     * 拼成一个 {input} 会让模型分不清哪句是人问的、哪句是库里查出来的——
     * 而「分得清」正是它敢说「查不到」的前提。
     */
    public static String render(String scene, Map<String, String> vars) {
        String text = template(scene);
        if (vars == null) {
            return text;
        }
        for (Map.Entry<String, String> e : vars.entrySet()) {
            text = text.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return text;
    }

    /**
     * 问数助手——意图抽取 prompt（不经过模板，动态拼能力目录）。
     *
     * @param catalog  能力目录文本（{@code DataQaService} 写死的那份）
     * @param question 管理层原话
     */
    public static String dataQaIntent(String catalog, String question) {
        return DATA_QA_INTENT
                .replace("{catalog}", catalog == null ? "" : catalog)
                .replace("{question}", question == null ? "" : question);
    }

    /**
     * 问数助手——叙事 prompt（动态拼真实数字）。
     *
     * @param question 管理层原话
     * @param numbers  Java 算好的真实数字（文本，{@code DataQaService} 拼好）
     */
    public static String dataQaNarrate(String question, String numbers) {
        return DATA_QA_NARRATE
                .replace("{question}", question == null ? "" : question)
                .replace("{numbers}", numbers == null ? "" : numbers);
    }

    /**
     * 当前生效的模板文本，写进日志与留痕方便排查「到底是 prompt 的问题还是模型的问题」。
     *
     * <p>这里刻意不做「未知场景兜底」：早年留了一句 {@code return SEMANTIC}，
     * 后果是传错场景不会报错，而是悄悄用语义评价的模板去跑别的场景——
     * 出来的内容看着像那么回事，极难发现。现在按场景显式匹配，对不上就抛。
     */
    public static String template(String scene) {
        return switch (scene) {
            case AiScene.SEMANTIC_EVAL -> SEMANTIC;
            case AiScene.APPLY_ASSIST -> ASSIST;
            case AiScene.MONTHLY_INSIGHT -> INSIGHT;
            case AiScene.DRILL_EXPLAIN -> DRILL;
            case AiScene.POLICY_QA -> POLICY;
            default -> throw new IllegalArgumentException("没有这个 AI 场景：" + scene);
        };
    }

    private static String load(String path, String fallback) {
        try (InputStream in = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return fallback;
            }
            return text;
        } catch (Exception e) {
            log.info("未读到 prompt 模板 {}（{}），使用内置兜底模板", path, e.getMessage());
            return fallback;
        }
    }
}
