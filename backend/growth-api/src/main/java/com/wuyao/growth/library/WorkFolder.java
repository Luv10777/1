package com.wuyao.growth.library;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 作品库里的一个文件夹。只有一层，属于建它的账号。 */
@Entity
@Table(name = "work_folders")
@Getter
@Setter
public class WorkFolder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @Column(nullable = false, length = 30) private String name;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
}
