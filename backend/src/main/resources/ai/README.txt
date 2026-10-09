这一目录放的是运行时真正生效的 prompt 模板。改模板改这里，改完重启后端即可，
不用动 Java 代码。

三个文件与场景的对应关系（文件名被 AiPrompts.load() 写死，别改名）：

  semantic-eval.txt      AI_SEMANTIC_EVAL    出差合理性语义评价 Δ
  apply-assist.txt       APPLY_ASSIST        员工端填单助手
  monthly-insight.txt    MONTHLY_INSIGHT     管理层月度洞察

两点容易踩的：

1. Java 的 AiPrompts 里还有一份同名的 FALLBACK_* 常量，只在【这个文件读不到时】兜底。
   只改 Java 那一处、不改这里的 txt 是无效的。启动时日志出现「未读到 prompt 模板」，
   就说明走的是兜底，文件没生效。

2. 模板里的 {input} 占位符不能删。删了结构化入参就填不进去，模型看不到任何数据，
   结果是输出一堆空结论而且不报错。

改完 prompt 记得升 application.yml 的 travel.ai.prompt-version，
否则 ai_log 留痕分不清某条结论是第几版算出来的。
