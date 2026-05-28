package io.valkeyry.ipaas.storage;

import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Polymorphic reactive object-storage abstraction. */
public interface ReactiveStorageClient {

    /** Streams content asynchronously to backend; returns the stored object URI / key. */
    Mono<String> upload(String objectKey, Flux<DataBuffer> content, long contentLength, String contentType);

    /** Returns a non-blocking stream of bytes for the given object. */
    Flux<DataBuffer> download(String objectKey);

    String getBucketOrContainer();

    String getProviderType();
}
