package com.ts.travelserver.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一张表的只读浏览结果。
 *
 * <p>行对象是 Map 而不是某个具体 VO：数据浏览要覆盖 17 张结构各不相同的表，
 * 为每张表写一个 VO 只会让 VO 目录翻倍，而页面要的不过是「按列显示」。
 * 代价是丢了字段级的类型提示，但这个页面是排查数据用的，认列名就够了。
 *
 * <p>values 已经被 BrowseService 处理过：时间列转成了可读字符串、
 * 敏感列替换成星号、超长文本截断，页面直接渲染即可。
 */
@Data
public class BrowseVO {

    /** 表元信息 */
    private TableVO table;

    /** 列名，与 rows 里每行的 key 一致 */
    private List<String> columns = new ArrayList<>();

    /** 当前页数据 */
    private List<Map<String, Object>> rows = new ArrayList<>();

    private long total;

    private long pageNum;

    private long pageSize;
}
