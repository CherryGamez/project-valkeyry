package io.valkeyry.ipaas.claimcheck;

import io.valkeyry.ipaas.config.ClaimCheckProperties;
import io.valkeyry.ipaas.domain.ClaimCheckRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

/** S3 / MinIO claim-check implementation (AWS SDK v2 async). */
public class S3ClaimCheckStore implements ClaimCheckStore {

    private static final Logger log = LoggerFactory.getLogger(S3ClaimCheckStore.class);

    private final S3AsyncClient client;
    private final String bucket;

    public S3ClaimCheckStore(ClaimCheckProperties props) {
        ClaimCheckProperties.S3 s3 = props.getS3();
        this.bucket = s3.getBucket();
        S3AsyncClientBuilder b = S3AsyncClient.builder()
                .region(Region.of(s3.getRegion()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(s3.isPathStyle()).build());
        if (!s3.getEndpoint().isBlank()) {
            b.endpointOverride(URI.create(s3.getEndpoint()));
        }
        if (!s3.getAccessKey().isBlank()) {
            b.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())));
        }
        this.client = b.build();
    }

    @Override
    public String name() { return "s3"; }

    @Override
    public Mono<ClaimCheckRef> put(String tenantId, byte[] payload, String contentType) {
        String key = "%s/%s/%s".formatted(tenantId, Instant.now().toString().substring(0, 10), UUID.randomUUID());
        String sha = sha256(payload);
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType(contentType == null ? "application/octet-stream" : contentType)
                .build();
        return Mono.fromFuture(client.putObject(req, AsyncRequestBody.fromBytes(payload)))
                .doOnSuccess(r -> log.debug("claim-check put s3://{}/{} size={}", bucket, key, payload.length))
                .thenReturn(new ClaimCheckRef("s3", bucket, key, payload.length, sha));
    }

    @Override
    public Mono<byte[]> get(ClaimCheckRef ref) {
        GetObjectRequest req = GetObjectRequest.builder().bucket(ref.bucket()).key(ref.key()).build();
        return Mono.fromFuture(client.getObject(req, AsyncResponseTransformer.toBytes()))
                .map(b -> b.asByteArray());
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte v : digest) sb.append(String.format("%02x", v));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private static String utf8(String s) { return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8); }
}
