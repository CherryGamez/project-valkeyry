package io.valkeyry.ipaas.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.ByteBuffer;

@Slf4j
public class S3StorageClient implements ReactiveStorageClient {

    private final S3AsyncClient s3;
    private final String bucket;

    public S3StorageClient(S3AsyncClient s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public Mono<String> upload(String objectKey, Flux<DataBuffer> content, long contentLength, String contentType) {
        Flux<ByteBuffer> bytes = content.map(db -> {
            ByteBuffer buf = ByteBuffer.allocate(db.readableByteCount());
            db.toByteBuffer(buf);
            buf.flip();
            return buf;
        });
        AsyncRequestBody body = AsyncRequestBody.fromPublisher(bytes);
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket).key(objectKey)
                .contentType(contentType == null ? "application/octet-stream" : contentType)
                .build();
        return Mono.fromFuture(s3.putObject(req, body))
                .map(r -> "s3://" + bucket + "/" + objectKey);
    }

    @Override
    public Flux<DataBuffer> download(String objectKey) {
        GetObjectRequest req = GetObjectRequest.builder().bucket(bucket).key(objectKey).build();
        DefaultDataBufferFactory factory = DefaultDataBufferFactory.sharedInstance;
        return Mono.fromFuture(s3.getObject(req, AsyncResponseTransformer.toPublisher()))
                .flatMapMany(pub -> Flux.from(pub)
                        .map(bb -> factory.wrap(bb)));
    }

    @Override public String getBucketOrContainer() { return bucket; }
    @Override public String getProviderType() { return "S3_COMPATIBLE"; }
}
