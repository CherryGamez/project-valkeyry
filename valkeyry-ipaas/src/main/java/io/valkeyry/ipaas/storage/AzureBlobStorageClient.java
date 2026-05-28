package io.valkeyry.ipaas.storage;

import com.azure.storage.blob.BlobAsyncClient;
import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.models.ParallelTransferOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;

@Slf4j
public class AzureBlobStorageClient implements ReactiveStorageClient {

    private final BlobContainerAsyncClient container;

    public AzureBlobStorageClient(BlobContainerAsyncClient container) {
        this.container = container;
    }

    @Override
    public Mono<String> upload(String objectKey, Flux<DataBuffer> content, long contentLength, String contentType) {
        BlobAsyncClient blob = container.getBlobAsyncClient(objectKey);
        Flux<ByteBuffer> bytes = content.map(db -> {
            ByteBuffer bb = ByteBuffer.allocate(db.readableByteCount());
            db.toByteBuffer(bb);
            bb.flip();
            return bb;
        });
        return blob.upload(bytes, new ParallelTransferOptions(), true)
                .map(r -> "azure://" + container.getBlobContainerName() + "/" + objectKey);
    }

    @Override
    public Flux<DataBuffer> download(String objectKey) {
        BlobAsyncClient blob = container.getBlobAsyncClient(objectKey);
        DefaultDataBufferFactory factory = DefaultDataBufferFactory.sharedInstance;
        return blob.downloadStream().map(factory::wrap);
    }

    @Override public String getBucketOrContainer() { return container.getBlobContainerName(); }
    @Override public String getProviderType() { return "AZURE_BLOB"; }
}
