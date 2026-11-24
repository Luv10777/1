package com.wuyao.growth.community;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 管理后台给账号起的备注名（V88，平台级表，不开行级隔离）。只在 PlatformUserService 核对 USERS 权限后使用。 */
@Repository
@RequiredArgsConstructor
class PlatformUserRemarks {
    private final EntityManager em;

    /** 全部备注名，按用户编号取。表很小，列表每次读一遍。 */
    @Transactional(readOnly = true)
    public Map<Long, String> all() {
        @SuppressWarnings("unchecked") List<Object[]> rows = em.createNativeQuery("SELECT user_id, remark FROM platform_user_remarks").getResultList();
        Map<Long, String> remarks = new HashMap<>();
        for (Object[] r : rows) remarks.put(((Number) r[0]).longValue(), (String) r[1]);
        return remarks;
    }

    @Transactional(readOnly = true)
    public String of(Long userId) {
        List<?> rows = em.createNativeQuery("SELECT remark FROM platform_user_remarks WHERE user_id = :u").setParameter("u", userId).getResultList();
        return rows.isEmpty() ? null : (String) rows.getFirst();
    }

    /** remark 为空时删掉备注。 */
    @Transactional
    public void set(Long userId, String remark, Long actor) {
        if (remark == null) {
            em.createNativeQuery("DELETE FROM platform_user_remarks WHERE user_id = :u").setParameter("u", userId).executeUpdate();
            return;
        }
        em.createNativeQuery("""
                INSERT INTO platform_user_remarks(user_id, remark, updated_by) VALUES (:u, :r, :a)
                ON CONFLICT (user_id) DO UPDATE SET remark = EXCLUDED.remark, updated_by = EXCLUDED.updated_by, updated_at = now()""")
                .setParameter("u", userId).setParameter("r", remark).setParameter("a", actor).executeUpdate();
    }
}
