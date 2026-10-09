package com.ts.travelserver.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 数据浏览的可选项：一张表的元信息。
 *
 * <p>rowCount 每次打开目录都现算一遍（17 张表 17 次 count）。规模就这么大，
 * 宁可查得勤一点，也不为一个管理后台引一层缓存。
 *
 * <p>note 放的是「看这张表要注意什么」：哪些列是派生数据、哪些被脱敏、哪些是 JSON 字符串。
 * 这些不看说明很容易读错，写在这里比写在页面上容易被遗漏。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableVO {

    /** 表名，浏览接口的路径参数就传它 */
    private String name;

    /** 中文名 */
    private String label;

    /** 主键列名，默认按它倒序 */
    private String pk;

    /** 当前行数 */
    private Long rowCount;

    /** 注意事项，可以为空 */
    private String note;

    /** 分组，前端按它归类展示 */
    private String group;

    /** 该表的列名，用于页面表头。空表时也有值，取自实体属性名转下划线 */
    private List<String> columns;
}
