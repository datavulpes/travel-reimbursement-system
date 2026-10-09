package com.ts.travelserver.service;

import com.ts.travelserver.entity.Partner;
import com.ts.travelserver.mapper.PartnerMapper;
import com.ts.travelserver.vo.PartnerOptionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 往来对象的公共读取。
 *
 * <p>只有一件事：给填单页的下拉框供数据。增删改归 {@link AdminService}，
 * 投入产出分析归 {@code AnalyticsService}，这里不掺和。
 *
 * <p>排序按名称而不是 id：下拉里躺着几十个对象时，按 id 排出来的顺序在员工看来是随机的，
 * 他找一家公司只能从头扫到尾。
 */
@Service
@RequiredArgsConstructor
public class PartnerService {

    private final PartnerMapper partnerMapper;

    /**
     * 全部往来对象。量级是档案级的（几十条），不分页。
     */
    public List<PartnerOptionVO> options() {
        List<Partner> list = partnerMapper.selectList(null);
        return list.stream()
                .sorted(Comparator.comparing(p -> p.getPartnerName() == null ? "" : p.getPartnerName()))
                .map(p -> {
                    PartnerOptionVO vo = new PartnerOptionVO();
                    vo.setId(p.getId());
                    vo.setPartnerName(p.getPartnerName());
                    vo.setPartnerType(p.getPartnerType());
                    return vo;
                })
                .toList();
    }
}
