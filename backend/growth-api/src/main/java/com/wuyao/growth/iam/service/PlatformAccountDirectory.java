package com.wuyao.growth.iam.service;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.dto.AccountDtos;
import com.wuyao.growth.iam.repository.TenantRepository;
import com.wuyao.growth.iam.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Directory DTOs for explicitly authorized platform services; never exposes credentials. */
@Service
@RequiredArgsConstructor
public class PlatformAccountDirectory {
    private final TenantRepository tenants;
    private final UserRepository users;

    public record Scope(Long id, String name) {}

    @Transactional(readOnly = true)
    public List<Scope> scopes() {
        return tenants.findAll(Sort.by("id")).stream().map(t -> new Scope(t.getId(), t.getName())).toList();
    }

    @Transactional(readOnly = true)
    public AccountDtos.Account account(Long userId) {
        return users.findById(userId).filter(u -> TenantContext.require().equals(u.getTenantId()))
                .map(AccountDtos.Account::of).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    /** Serialize identity submissions/reviews for the same user, including the initial insert. */
    @Transactional
    public void lockAccount(Long userId) {
        users.findForUpdate(userId).filter(u -> TenantContext.require().equals(u.getTenantId()))
                .filter(u -> "ACTIVE".equals(u.getStatus()))
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "用户不存在或已停用"));
    }
}
