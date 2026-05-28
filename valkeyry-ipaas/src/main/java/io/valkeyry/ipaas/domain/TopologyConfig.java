package io.valkeyry.ipaas.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Table("topology_configs")
public class TopologyConfig {
    @Id private UUID id;
    @Column("tenant_id")  private String tenantId;
    @Column("project_id") private String projectId;
    @Column("topology_name") private String topologyName;
    @Column("topology_type") private String topologyType;       // ONE_TO_MANY|MANY_TO_ONE|MANY_TO_MANY
    @Column("manifest_yaml") private String manifestYaml;
    private Boolean enabled;
    @Column("created_at") private OffsetDateTime createdAt;
}
