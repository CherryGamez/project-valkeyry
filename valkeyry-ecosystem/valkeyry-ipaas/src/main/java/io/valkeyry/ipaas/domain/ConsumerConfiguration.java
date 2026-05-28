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
@Table("consumer_configurations")
public class ConsumerConfiguration {
    @Id private UUID id;
    @Column("tenant_id")  private String tenantId;
    @Column("project_id") private String projectId;
    @Column("consumer_name") private String consumerName;
    @Column("source_destination") private String sourceDestination;
    @Column("target_webhook_url") private String targetWebhookUrl;
    /** JSON map: {"targetField": "$.json.path", ...} */
    @Column("field_mappings_json") private String fieldMappingsJson;
    private Boolean enabled;
    @Column("created_at") private OffsetDateTime createdAt;
}
