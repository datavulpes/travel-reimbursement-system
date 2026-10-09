package com.ts.travelserver.ai;

/**
 * AI 通道。
 *
 * <p>刻意做成一个「笨」接口：只负责把一段结构化输入交给模型、把原文带回来。
 * Prompt 怎么拼、JSON 怎么校验、失败了怎么降级，都归调用方——
 * 这样换模型只改 {@code RemoteAiService} 一个类，业务代码一行不动。
 *
 * <p>TODO-答辩可讲：AI 在本系统里的定位是「调节量」不是「决定者」。
 * 所有能力必须能在 {@link #complete} 返回 null 时继续跑（Δ 取 0、改写为空），
 * 这是兜底设计，也是它敢上生产的前提。
 */
public interface AiService {

    /**
     * 当前是否真的会调模型。false 表示走本地启发式，调用方据此标注「本地演示」。
     */
    boolean remote();

    /** 实际使用的模型名，写进留痕与返回体，方便事后对账 */
    String model();

    /**
     * 让模型处理一份输入。
     *
     * @param scene     场景，见 {@link AiScene}，用于缓存键与留痕
     * @param inputJson 结构化入参（JSON 文本）；数字一律由调用方算好再放进这里，
     *                  不要让模型做算术
     * @return 模型输出的原文；null 表示不可用或失败，调用方自行降级
     */
    AiTextResult complete(String scene, String inputJson);

    /**
     * 带多占位符的调用。
     *
     * <p>只有制度问答走这一条：它要把「人问的那句」和「库里查出来的事实」分两段塞进模板，
     * 拼成一个 {@code {input}} 会让模型分不清哪句是人说的——而分得清，
     * 正是它敢回答「查不到」的前提（见 {@code AiScene.POLICY_QA}）。
     *
     * <p>其余场景别用：占位符一多，模板与调用方之间就多了一处要靠约定的耦合，
     * 换模板时忘了同步是很难发现的错误。
     */
    default AiTextResult complete(String scene, String inputJson, java.util.Map<String, String> vars) {
        return complete(scene, inputJson);
    }

    /**
     * 不经过模板、直接把一段 prompt 发给模型的调用。
     *
     * <p>只给问数助手（{@code DATA_QA}）用：它的 prompt 是「能力目录 + 人问的话」
     * 这种动态拼出来的东西，不存在一个适合外置成 txt 的固定模板，
     * 硬塞进 {@code AiPrompts.render} 反而要把能力目录也写进模板、调用方再传变量，
     * 耦合比直接拼更重。所以这里开一个口子，让调用方自己拼好整段 prompt。
     *
     * <p>契约与 {@link #complete} 一致：返回 null 表示不可用或失败，调用方自行降级；
     * 模型输出的数字校验仍归调用方（问数助手用 {@code AiTextGuard}）。
     */
    default AiTextResult completeRaw(String scene, String prompt) {
        return complete(scene, prompt == null ? "{}" : prompt);
    }
}
