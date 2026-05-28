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

/** Logical broker asset (queue / topic / stream) bound to a tenant+project. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Table("queue_assets")
public class QueueAsset {
    @Id private UUID id;
    @Column("tenant_id")  private String tenantId;
    @Column("project_id") private String projectId;
    @Column("destination_name") private String destinationName;
    @Column("broker_type") private String brokerType;          // RABBITMQ|KAFKA|ACTIVEMQ
    @Column("processing_mode") private String processingMode;  // QUEUE|STREAMING
    @Column("provisioning_mode") private String provisioningMode; // CATALOG|LAZY_PROVISIONED
    @Column("ttl_seconds") private Integer ttlSeconds;
    @Column("created_at") private OffsetDateTime createdAt;

    /** Strict isolation naming convention. */
    public String physicalName() {
        return tenantId + "." + projectId + "." + destinationName;
    }
    public String dlqName() {
        return physicalName() + ".dlq";
    }
}
