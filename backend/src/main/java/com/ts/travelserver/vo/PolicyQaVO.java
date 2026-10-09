package com.ts.travelserver.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 制度问答结果（员工问一句，系统照着库里的现行规定答一句）。
 *
 * <p>返回体里刻意带上 {@link #facts} 与 {@link #local} 两个字段：
 * 页面上把「答案依据哪几条查出来的」一并摆出来，看的人自己就能核对，
 * 不用去信一个没有出处的回答——这与全系统「AI 结论必须可溯源」是同一条口径。
 */
@Data
public class PolicyQaVO {

    /** 员工问的原话 */
    private String question;

    /** 答案；{@link #answerable} 为 false 时是那句统一的「查不到」 */
    private String answer;

    /** 库里有没有能回答这一问的现行规定 */
    private Boolean answerable;

    /** 回答时用到的那几条事实原文，页面上摆在答案下面 */
    private List<String> facts = new ArrayList<>();

    /** 全部可查的规定（不管这一问用没用到），供页面折叠展示 */
    private List<String> allFacts = new ArrayList<>();

    /** true = 模型没接上，这份答案是规则拼出来的 */
    private Boolean local;

    /** 模型名，未启用为空 */
    private String model;

    /** prompt 版本 */
    private String promptVersion;
}
