package com.ts.travelserver.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 分页返回体。
 *
 * <p>设计报告 7.2：列表接口统一 { list, total, pageNum, pageSize }，
 * 前端 el-pagination 直接吃这四个字段。
 *
 * @param <T> 列表元素类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private List<T> list;

    /** 总条数 */
    private long total;

    /** 当前页码，从 1 开始 */
    private long pageNum;

    /** 每页条数 */
    private long pageSize;

    /**
     * 直接包一个已有的集合，不查库时用这个。
     */
    public static <T> PageResult<T> of(List<T> list, long total, long pageNum, long pageSize) {
        return new PageResult<>(list == null ? Collections.emptyList() : list, total, pageNum, pageSize);
    }

    /**
     * 从 MyBatis-Plus 的分页对象转换，列表元素原样返回。
     */
    public static <T> PageResult<T> of(IPage<T> page) {
        if (page == null) {
            return new PageResult<>(Collections.emptyList(), 0, 1, 10);
        }
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 查出来是 entity、要转成 VO 时用这个，省掉外面写一遍 stream。
     *
     * @param page    数据库分页结果
     * @param converter entity -> VO 的转换函数
     */
    public static <E, V> PageResult<V> of(IPage<E> page, Function<E, V> converter) {
        if (page == null) {
            return new PageResult<>(Collections.emptyList(), 0, 1, 10);
        }
        List<V> records = page.getRecords().stream().map(converter).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 空页，查询条件没命中时比返回 null 省心。
     */
    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return new PageResult<>(Collections.emptyList(), 0, pageNum, pageSize);
    }
}
