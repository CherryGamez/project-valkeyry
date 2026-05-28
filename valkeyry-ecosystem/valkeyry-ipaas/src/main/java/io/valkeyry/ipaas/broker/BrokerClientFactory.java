package io.valkeyry.ipaas.broker;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Resolves broker clients by string type at runtime. */
@Component
@RequiredArgsConstructor
public class BrokerClientFactory {

    private final List<ReactiveBrokerClient> brokers;
    private Map<String, ReactiveBrokerClient> indexCache;

    private Map<String, ReactiveBrokerClient> index() {
        if (indexCache == null) {
            indexCache = brokers.stream()
                    .collect(Collectors.toMap(b -> b.type().toUpperCase(), b -> b));
        }
        return indexCache;
    }

    public ReactiveBrokerClient get(String type) {
        ReactiveBrokerClient b = index().get(type == null ? "" : type.toUpperCase());
        if (b == null) {
            throw new IllegalArgumentException("Unknown broker type: " + type
                    + "; available=" + index().keySet());
        }
        return b;
    }
}
