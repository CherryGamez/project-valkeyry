package io.valkeyry.ipaas.storage;

import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import io.valkeyry.ipaas.domain.StorageConfiguration;
import io.valkeyry.ipaas.secret.ReactiveSecretManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.util.Map;

/**
 * Reads credentials from Vault on-the-fly and returns a freshly built
 * ReactiveStorageClient. No long-lived credential caching by design.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicStorageFactory {

    private final ReactiveSecretManager secretManager;

    public Mono<ReactiveStorageClient> build(StorageConfiguration cfg) {
        return secretManager.resolve(cfg.getVaultSecretPath())
                .map(secrets -> switch (cfg.getProviderType()) {
                    case "S3_COMPATIBLE" -> buildS3(cfg, secrets);
                    case "AZURE_BLOB"    -> buildAzure(cfg, secrets);
                    default -> throw new IllegalArgumentException("Unsupported provider: " + cfg.getProviderType());
                });
    }

    private ReactiveStorageClient buildS3(StorageConfiguration cfg, Map<String, Object> secrets) {
        String accessKey = String.valueOf(secrets.get("access_key"));
        String secretKey = String.valueOf(secrets.get("secret_key"));
        S3AsyncClient s3 = S3AsyncClient.builder()
                .region(Region.of(cfg.getRegion() == null ? "us-east-1" : cfg.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .endpointOverride(cfg.getEndpointUrl() == null ? null : URI.create(cfg.getEndpointUrl()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)   // required by Ceph/MinIO
                        .build())
                .build();
        return new S3StorageClient(s3, cfg.getBucketOrContainer());
    }

    private ReactiveStorageClient buildAzure(StorageConfiguration cfg, Map<String, Object> secrets) {
        String accountName = String.valueOf(secrets.get("account_name"));
        String accountKey  = String.valueOf(secrets.get("account_key"));
        String endpoint = cfg.getEndpointUrl() != null
                ? cfg.getEndpointUrl()
                : "https://" + accountName + ".blob.core.windows.net";
        BlobContainerAsyncClient container = new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .credential(new StorageSharedKeyCredential(accountName, accountKey))
                .buildAsyncClient()
                .getBlobContainerAsyncClient(cfg.getBucketOrContainer());
        return new AzureBlobStorageClient(container);
    }
}
