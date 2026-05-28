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
@Table("message_logs")
public class MessageLog {
    @Id private UUID id;
    @Column("tenant_id")  private String tenantId;
    @Column("project_id") private String projectId;
    private String destination;
    @Column("trace_id") private String traceId;
    private String status;
    private String payload;
    @Column("created_at") private OffsetDateTime createdAt;
}
