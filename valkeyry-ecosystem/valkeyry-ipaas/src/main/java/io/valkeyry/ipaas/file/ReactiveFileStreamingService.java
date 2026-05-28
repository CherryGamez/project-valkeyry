package io.valkeyry.ipaas.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.valkeyry.ipaas.broker.BrokerClientFactory;
import io.valkeyry.ipaas.queue.QueueManagementService;
import io.valkeyry.ipaas.repository.StorageConfigurationRepository;
import io.valkeyry.ipaas.storage.DynamicStorageFactory;
import io.valkeyry.ipaas.storage.ReactiveStorageClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Implements the Claim Check Pattern over reactive WebFlux pipelines.
 *  - INGEST  : Flux<DataBuffer> -> storage; emits ClaimTicket onto target queue.
 *  - DELIVER : streams object bytes via WebClient chunked body.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReactiveFileStreamingService {

    private final StorageConfigurationRepository storageRepo;
    private final DynamicStorageFactory storageFactory;
    private final QueueManagementService queueService;
    private final BrokerClientFactory brokerFactory;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public Mono<ClaimTicket> ingestAndPublish(String tenantId, String projectId,
                                              String storageConfigName,
                                              String targetDestination,
                                              String objectKey,
                                              String contentType, long contentLength,
                                              Flux<DataBuffer> body) {
        return storageRepo.findByTenantIdAndProjectIdAndName(tenantId, projectId, storageConfigName)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Storage configuration not found: " + storageConfigName)))
                .flatMap(storageFactory::build)
                .flatMap(client -> client.upload(objectKey, body, contentLength, contentType)
                        .map(uri -> ClaimTicket.builder()
                                .storageConfigName(storageConfigName)
                                .providerType(client.getProviderType())
                                .bucketOrContainer(client.getBucketOrContainer())
                                .objectKey(objectKey)
                                .objectUri(uri)
                                .contentType(contentType)
                                .sizeBytes(contentLength)
                                .createdAt(OffsetDateTime.now())
                                .build()))
                .flatMap(ticket -> queueService.resolveOrLazyProvision(tenantId, projectId, targetDestination)
                        .flatMap(asset -> brokerFactory.get(asset.getBrokerType())
                                .publish(tenantId, projectId, targetDestination,
                                        toJson(ticket), Map.of("content-type", "application/json"))
                                .thenReturn(ticket)));
    }

    /** Streams the object back out to the destination webhook with chunked body, returns HTTP status. */
    public Mono<Integer> streamObjectOut(String tenantId, String projectId,
                                         String storageConfigName, String objectKey,
                                         String targetUrl, Map<String, String> headers) {
        return storageRepo.findByTenantIdAndProjectIdAndName(tenantId, projectId, storageConfigName)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Storage configuration not found: " + storageConfigName)))
                .flatMap(storageFactory::build)
                .flatMap(client -> {
                    Flux<DataBuffer> bytes = client.download(objectKey);
                    var spec = webClient.post().uri(targetUrl);
                    if (headers != null) headers.forEach(spec::header);
                    return spec.body(BodyInserters.fromDataBuffers(bytes))
                            .exchangeToMono(resp -> Mono.just(resp.statusCode().value()));
                });
    }

    private byte[] toJson(ClaimTicket ticket) {
        try { return objectMapper.writeValueAsBytes(ticket); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Visible for tests: bypass broker publish path. */
    public Mono<ClaimTicket> uploadOnly(String tenantId, String projectId, String storageConfigName,
                                        String objectKey, String contentType, long contentLength,
                                        Flux<DataBuffer> body) {
        return storageRepo.findByTenantIdAndProjectIdAndName(tenantId, projectId, storageConfigName)
                .flatMap(storageFactory::build)
                .flatMap(client -> client.upload(objectKey, body, contentLength, contentType)
                        .map(uri -> ClaimTicket.builder()
                                .storageConfigName(storageConfigName)
                                .providerType(client.getProviderType())
                                .bucketOrContainer(client.getBucketOrContainer())
                                .objectKey(objectKey).objectUri(uri).sizeBytes(contentLength)
                                .contentType(contentType).createdAt(OffsetDateTime.now()).build()));
    }

    public Mono<ReactiveStorageClient> resolveClient(String t, String p, String name) {
        return storageRepo.findByTenantIdAndProjectIdAndName(t, p, name).flatMap(storageFactory::build);
    }
}
