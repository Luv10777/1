package com.wuyao.growth.store;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 店员的门店范围，供员工管理使用。只管名单本身；谁有权改名单由调用方判断。 */
@Service
@RequiredArgsConstructor
public class StoreMembershipService {
    private final StoreRepository storeRepository;
    private final StoreMemberRepository memberRepository;

    /** 每个店员能进的营业中门店；一家都没有的人不出现在结果里。 */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> storeIdsByUser() {
        Set<Long> open = openStoreIds();
        return memberRepository.findAllByStatusOrderByStoreIdAsc("ACTIVE").stream()
                .filter(member -> open.contains(member.getStoreId()))
                .collect(Collectors.groupingBy(StoreMember::getUserId,
                        Collectors.mapping(StoreMember::getStoreId, Collectors.toList())));
    }

    /** 把这个店员能进的门店换成给定的这几家，返回最终的门店列表。 */
    @Transactional
    public List<Long> replace(Long userId, Collection<Long> storeIds) {
        Set<Long> wanted = new LinkedHashSet<>(storeIds == null ? List.of() : storeIds);
        Set<Long> open = openStoreIds();
        if (!open.containsAll(wanted)) throw BizException.of(ErrorCode.BAD_REQUEST, "所选门店不存在或已停用");
        List<StoreMember> current = memberRepository.findAllByUserId(userId);
        memberRepository.deleteAll(current.stream().filter(member -> !wanted.contains(member.getStoreId())).toList());
        memberRepository.flush();
        Set<Long> kept = current.stream().map(StoreMember::getStoreId).collect(Collectors.toSet());
        Long tenantId = TenantContext.require();
        for (Long storeId : wanted) {
            if (kept.contains(storeId)) continue;
            StoreMember member = new StoreMember();
            member.setTenantId(tenantId);
            member.setStoreId(storeId);
            member.setUserId(userId);
            memberRepository.save(member);
        }
        return wanted.stream().sorted().toList();
    }

    @Transactional
    public void removeAll(Long userId) {
        memberRepository.deleteAll(memberRepository.findAllByUserId(userId));
    }

    private Set<Long> openStoreIds() {
        return storeRepository.findAllByStatusOrderByIdAsc("ACTIVE").stream().map(Store::getId).collect(Collectors.toSet());
    }
}
