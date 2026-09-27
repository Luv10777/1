package com.wuyao.growth.creative.image;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity @Table(name="image_creations") @Getter @Setter
public class ImageCreation {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false) private Long tenantId;
 @Column(nullable=false,length=80) private String requestKey;
 @Column(nullable=false,length=64) private String requestHash;
 private Long parentId;
 @Column(length=100) private String title;
 @Column(length=16) private String variation;
 @Column(length=16) private String referenceHash;
 private Long createdBy;
 @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private ImageDtos.Create request;
 @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") private ImageDtos.Plan plan;
 @Column(nullable=false,length=24) private String status="QUEUED";
 @Column(nullable=false) private boolean concurrencyPermitHeld;
 @Column(columnDefinition="text") private String error;
 private Long taskId;
 @Column(nullable=false) private Instant createdAt=Instant.now();
}
