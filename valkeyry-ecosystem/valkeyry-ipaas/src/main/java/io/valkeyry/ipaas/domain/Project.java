package io.valkeyry.ipaas.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Table("projects")
public class Project {
    /** Alphanumeric slug. */
    @Id private String id;
    @Column("tenant_id") private String tenantId;
    private String name;
    @Column("retention_days") private Integer retentionDays;
    @Column("created_at") private OffsetDateTime createdAt;
}
