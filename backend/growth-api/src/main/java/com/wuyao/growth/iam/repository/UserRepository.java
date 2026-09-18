package com.wuyao.growth.iam.repository;

import com.wuyao.growth.iam.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPhone(String phone);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findByUsername(String username);
}
