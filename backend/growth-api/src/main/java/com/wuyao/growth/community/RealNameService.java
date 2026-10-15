package com.wuyao.growth.community;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.iam.service.PlatformAccountDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RealNameService {
    private final RealNameApplicationRepository applications;
    private final PlatformAccountDirectory accounts;
    private final RealNameCrypto crypto;
    private final PlatformManagementAuditRepository audits;

    @Transactional(readOnly = true)
    public RealNameDtos.ApplicationView current(Long userId) {
        return applications.findByTenantIdAndUserId(TenantContext.require(), userId).map(RealNameService::view).orElse(null);
    }

    @Transactional
    public RealNameDtos.ApplicationView submit(Long userId, RealNameDtos.ApplicationRequest request) {
        String number = request.idNumber().trim().toUpperCase(Locale.ROOT);
        validateIdNumber(number);
        if (!Boolean.TRUE.equals(request.declarationAccepted())) throw bad("请确认身份信息真实、准确");
        accounts.lockAccount(userId);
        var application = applications.findByTenantIdAndUserId(TenantContext.require(), userId).orElseGet(() -> {
            var created = new RealNameApplication();
            created.setTenantId(TenantContext.require());
            created.setUserId(userId);
            return created;
        });
        if (application.getId() != null && !"REJECTED".equals(application.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT, "已有审核中或审核通过的实名申请，请勿重复提交");
        }
        String name = request.legalName().trim();
        application.setLegalNameCiphertext(crypto.encrypt(name, application.getTenantId(), userId, "name"));
        application.setIdNumberCiphertext(crypto.encrypt(number, application.getTenantId(), userId, "number"));
        application.setLegalNameMasked(name.substring(0, 1) + "*".repeat(Math.max(1, name.length() - 1)));
        application.setIdNumberMasked(number.substring(0, 3) + "***********" + number.substring(14));
        application.setStatus("PENDING_REVIEW");
        application.setReviewNote(null);
        application.setReviewedBy(null);
        application.setReviewedAt(null);
        application.setSubmittedAt(Instant.now());
        application.setUpdatedAt(Instant.now());
        var saved = applications.saveAndFlush(application);
        var event = new PlatformManagementAudit();
        event.setTenantId(TenantContext.require()); event.setActorUserId(userId);
        event.setTargetType("IDENTITY"); event.setTargetId(saved.getId());
        event.setAction("SUBMITTED"); event.setNote("本人提交实名资料并确认真实性与审核用途");
        audits.saveAndFlush(event);
        return view(saved);
    }

    static void validateIdNumber(String number) {
        if (number == null || !number.matches("[1-9]\\d{16}[0-9X]")) throw bad("请输入有效的18位居民身份证号码");
        try {
            LocalDate birth = LocalDate.parse(number.substring(6, 14), DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT));
            if (birth.isAfter(LocalDate.now()) || birth.isBefore(LocalDate.of(1800, 1, 1))) throw bad("身份证出生日期无效");
        } catch (java.time.format.DateTimeParseException e) {
            throw bad("身份证出生日期无效");
        }
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        String checks = "10X98765432";
        int sum = 0;
        for (int i = 0; i < 17; i++) sum += (number.charAt(i) - '0') * weights[i];
        if (checks.charAt(sum % 11) != number.charAt(17)) throw bad("身份证号码校验未通过，请检查输入");
    }

    static RealNameDtos.ApplicationView view(RealNameApplication a) {
        return new RealNameDtos.ApplicationView(a.getId(), a.getLegalNameMasked(), a.getIdNumberMasked(), a.getStatus(),
                a.getReviewNote(), a.getSubmittedAt(), a.getReviewedAt());
    }

    private static BizException bad(String message) { return BizException.of(ErrorCode.BAD_REQUEST, message); }
}
