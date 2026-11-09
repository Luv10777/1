package com.wuyao.growth.iam.repository;

import com.wuyao.growth.iam.entity.LoginEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface LoginEventRepository extends JpaRepository<LoginEvent, Long> {

    /** 最早一条登录记录的时间；没有记录时为 null。早于它的时段没有登录数据。 */
    @Query("select min(e.createdAt) from LoginEvent e")
    Instant firstLoginAt();

    /** [from, to) 内登录过的账号数，不含已被商户移除的账号，与用户列表的口径一致。 */
    @Query(value = """
            SELECT count(DISTINCT e.user_id) FROM login_events e JOIN users u ON u.id = e.user_id
            WHERE u.status <> 'REMOVED' AND e.created_at >= :from AND e.created_at < :to""", nativeQuery = true)
    long countUsersBetween(Instant from, Instant to);
}
