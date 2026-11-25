package com.wuyao.growth.library;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 一件已保存到作品库的作品：指向一张生成的图片（image_items）或一条视频（video_workflows）。 */
@Entity
@Table(name = "library_works")
@Getter
@Setter
public class LibraryWork {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    /** IMAGE / VIDEO */
    @Column(nullable = false, length = 10) private String kind;
    @Column(name = "source_id", nullable = false) private Long sourceId;
    /** 为空表示放在作品库根目录。 */
    @Column(name = "folder_id") private Long folderId;
    @Column(name = "saved_at", nullable = false) private Instant savedAt = Instant.now();
}
