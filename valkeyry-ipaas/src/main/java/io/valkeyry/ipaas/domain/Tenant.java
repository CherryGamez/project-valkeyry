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
@Table("tenants")
public class Tenant {
    /** Alphanumeric slug, 1-200 chars; matches {@link io.valkeyry.ipaas.security.WorkspaceId#PATTERN}. */
    @Id private String id;
    private String name;
    @Column("created_at") private OffsetDateTime createdAt;
}
