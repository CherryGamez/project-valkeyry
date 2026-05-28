package io.valkeyry.ipaas.file;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** Lightweight JSON envelope substituted for binary payloads ("Claim Check"). */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ClaimTicket {
    private String claimTicket = "v1";
    private String storageConfigName;
    private String providerType;       // S3_COMPATIBLE | AZURE_BLOB
    private String bucketOrContainer;
    private String objectKey;
    private String objectUri;
    private long sizeBytes;
    private String contentType;
    private OffsetDateTime createdAt;
}
