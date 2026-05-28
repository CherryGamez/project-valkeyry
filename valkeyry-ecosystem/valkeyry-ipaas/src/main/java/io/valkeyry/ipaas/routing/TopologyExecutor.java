package io.valkeyry.ipaas.routing;

import io.valkeyry.ipaas.broker.BrokerClientFactory;
import io.valkeyry.ipaas.broker.ReactiveBrokerClient;
import io.valkeyry.ipaas.domain.TopologyConfig;
import io.valkeyry.ipaas.repository.TopologyConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes parsed topology manifests:
 *   ONE_TO_MANY  -> Flux.flatMap fan-out to N destinations
 *   MANY_TO_ONE  -> Flux.merge consolidation into one destination
 *   MANY_TO_MANY -> merge + conditional metadata multiplex
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopologyExecutor {

    private final TopologyConfigRepository topologyRepo;
    private final DeclarativeTopologyParser parser;
    private final BrokerClientFactory brokerFactory;

    private final Map<String, Disposable> activeTopologies = new ConcurrentHashMap<>();

    public Mono<Void> deploy(String tenantId, String projectId, TopologyConfig cfg) {
        DeclarativeTopologyParser.TopologyManifest manifest = parser.parse(cfg.getManifestYaml());
        ReactiveBrokerClient broker = brokerFactory.get(manifest.getBrokerType());
        String key = tenantId + ":" + projectId + ":" + cfg.getTopologyName();

        Disposable previous = activeTopologies.remove(key);
        if (previous != null) previous.dispose();

        Disposable d = switch (manifest.getTopologyType()) {
            case "ONE_TO_MANY"  -> deployFanOut(broker, tenantId, projectId, manifest);
            case "MANY_TO_ONE"  -> deployConsolidate(broker, tenantId, projectId, manifest);
            case "MANY_TO_MANY" -> deployMatrix(broker, tenantId, projectId, manifest);
            default -> throw new IllegalArgumentException("Unknown topology type: " + manifest.getTopologyType());
        };
        activeTopologies.put(key, d);
        return Mono.empty();
    }

    public Mono<Void> undeploy(String tenantId, String projectId, String topologyName) {
        Disposable d = activeTopologies.remove(tenantId + ":" + projectId + ":" + topologyName);
        if (d != null) d.dispose();
        return Mono.empty();
    }

    public Flux<TopologyConfig> list(String tenantId, String projectId) {
        return topologyRepo.findAllByTenantIdAndProjectId(tenantId, projectId);
    }

    // ---- 1 -> N ----
    private Disposable deployFanOut(ReactiveBrokerClient broker, String t, String p,
                                    DeclarativeTopologyParser.TopologyManifest m) {
        String source = m.getSources().get(0);
        return broker.subscribe(t, p, source, m.getProcessingMode(), msg ->
                Flux.fromIterable(m.getTargets())
                        .flatMap(tgt -> broker.publish(t, p, tgt.getDestination(),
                                msg.getPayload(),
                                Map.of("x-fanout-from", source)))
                        .then(Mono.just(true))
                        .onErrorResume(ex -> {
                            log.warn("Fan-out leg failed, DLQ: {}", ex.toString());
                            return broker.publishDlq(t, p, source, msg.getPayload(), msg.getHeaders())
                                    .thenReturn(true);
                        }));
    }

    // ---- N -> 1 ----
    private Disposable deployConsolidate(ReactiveBrokerClient broker, String t, String p,
                                         DeclarativeTopologyParser.TopologyManifest m) {
        String dest = m.getTargets().get(0).getDestination();
        // Merge subscriptions
        return Flux.fromIterable(m.getSources())
                .flatMap(src -> Flux.<ReactiveBrokerClient.IncomingMessage>create(sink -> {
                    Disposable d = broker.subscribe(t, p, src, m.getProcessingMode(),
                            msg -> { sink.next(msg); return Mono.just(true); });
                    sink.onCancel(d::dispose);
                }))
                .flatMap(msg -> broker.publish(t, p, dest, msg.getPayload(),
                        new HashMap<>(msg.getHeaders())))
                .subscribe();
    }

    // ---- N -> N ----
    private Disposable deployMatrix(ReactiveBrokerClient broker, String t, String p,
                                    DeclarativeTopologyParser.TopologyManifest m) {
        return Flux.fromIterable(m.getSources())
                .flatMap(src -> Flux.<ReactiveBrokerClient.IncomingMessage>create(sink -> {
                    Disposable d = broker.subscribe(t, p, src, m.getProcessingMode(),
                            msg -> { sink.next(msg); return Mono.just(true); });
                    sink.onCancel(d::dispose);
                }))
                .flatMap(msg -> Flux.fromIterable(m.getTargets())
                        .filter(tgt -> matches(tgt, msg))
                        .flatMap(tgt -> broker.publish(t, p, tgt.getDestination(),
                                msg.getPayload(), msg.getHeaders())))
                .subscribe();
    }

    private boolean matches(DeclarativeTopologyParser.TargetSpec tgt,
                            ReactiveBrokerClient.IncomingMessage msg) {
        if (tgt.getMatchHeaders() != null && !tgt.getMatchHeaders().isEmpty()) {
            for (Map.Entry<String, String> e : tgt.getMatchHeaders().entrySet()) {
                if (!e.getValue().equals(msg.getHeaders().get(e.getKey()))) return false;
            }
            return true;
        }
        if (tgt.getMatchJsonPath() != null && tgt.getMatchValue() != null) {
            try {
                String body = new String(msg.getPayload(), StandardCharsets.UTF_8);
                Object v = com.jayway.jsonpath.JsonPath.read(body, tgt.getMatchJsonPath());
                return tgt.getMatchValue().equals(String.valueOf(v));
            } catch (Exception ex) { return false; }
        }
        return true;
    }
}
