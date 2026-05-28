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
@Table("storage_configurations")
public class StorageConfiguration {
    @Id private UUID id;
    @Column("tenant_id")  private String tenantId;
    @Column("project_id") private String projectId;
    private String name;
    @Column("provider_type") private String providerType;          // S3_COMPATIBLE | AZURE_BLOB
    @Column("bucket_or_container") private String bucketOrContainer;
    @Column("endpoint_url") private String endpointUrl;
    private String region;
    /** Path inside HashiCorp Vault (e.g. secret/data/<tenant>/<project>/storage). */
    @Column("vault_secret_path") private String vaultSecretPath;
    @Column("created_at") private OffsetDateTime createdAt;
}
