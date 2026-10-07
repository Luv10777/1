package com.wuyao.growth.voice;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "voice_samples") @Getter @Setter
public class VoiceSample {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long tenantId;
    @Column(nullable = false) private Long storeId;
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false, length = 500) private String storageKey;
    @Column(nullable = false, length = 120) private String mimeType;
    private Long sizeBytes;
    @Column(nullable = false, length = 30) private String status = "PENDING_UPLOAD";
    @Column(length = 40) private String providerCode;
    @Column(length = 200) private String providerVoiceId;
    @Column(nullable = false) private Instant consentAt;
    @Column(nullable = false) private Long consentBy;
    @Column(nullable = false, length = 300) private String consentText;
    @Column(length = 500) private String errorMessage;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    @Version private Long version = 0L;
}
