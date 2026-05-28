package io.valkeyry.ipaas.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Table("user_access_policies")
public class UserAccessPolicy {
    @Id private UUID id;
    private String subject;                          // JWT 'sub'
    @Column("tenant_id")  private String tenantId;
    @Column("project_id") private String projectId;
    private String privilege;                        // PROJECT_READ | PROJECT_WRITE
}
