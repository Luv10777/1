package com.wuyao.growth.voice;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** 一条"这个声音样本开放给这家门店使用"的记录。样本归商户所有，能在哪里用由这些记录决定。 */
@Entity @Table(name = "voice_sample_stores") @Getter @Setter
public class VoiceSampleStore {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long tenantId;
    @Column(nullable = false) private Long sampleId;
    @Column(nullable = false) private Long storeId;
    private Long grantedBy;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
