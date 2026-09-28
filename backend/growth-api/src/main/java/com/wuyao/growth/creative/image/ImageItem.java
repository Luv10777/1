package com.wuyao.growth.creative.image;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

@Entity @Table(name="image_items") @Getter @Setter
public class ImageItem {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false) private Long tenantId;
 @Column(nullable=false) private Long creationId;
 @Column(nullable=false) private int ordinal;
 @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private ImageDtos.Spec spec;
 @Column(nullable=false,length=24) private String status="QUEUED";
 private Long taskId;
 @Column(length=300) private String providerJobId;
 @Column(length=80) private String providerCode;
 @Column(length=200) private String model;
 @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") private Map<String,Object> usage;
 @Column(nullable=false) private int generation;
 @Column(nullable=false) private int pollRound;
 @Column(length=500) private String rawKey;
 @Column(length=500) private String outputKey;
 @Column(name="provider_image_url", columnDefinition="text") private String providerImageUrl;
 @Column(name="persisted_at") private Instant persistedAt;
 private Integer actualWidth;
 private Integer actualHeight;
 @Column(length=16) private String imageHash;
 @Column(nullable=false) private boolean similarityWarning;
 @Column(columnDefinition="text") private String error;
 @Column(nullable=false) private Instant createdAt=Instant.now();
}
