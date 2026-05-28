package io.valkeyry.ipaas.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Parses YAML routing manifests into typed Topology descriptors:
 *   - ONE_TO_MANY   : single source fan-out to many destinations
 *   - MANY_TO_ONE   : merge many sources into one destination
 *   - MANY_TO_MANY  : merged sources -> conditional metadata-based multiplex
 */
@Slf4j
@Component
public class DeclarativeTopologyParser {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public TopologyManifest parse(String yamlString) {
        try {
            return YAML.readValue(yamlString, TopologyManifest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid topology manifest YAML: " + e.getMessage(), e);
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TopologyManifest {
        private String topologyName;
        private String topologyType;       // ONE_TO_MANY | MANY_TO_ONE | MANY_TO_MANY
        private String processingMode;     // QUEUE | STREAMING
        private String brokerType;         // RABBITMQ | KAFKA | ACTIVEMQ
        private List<String> sources;
        private List<TargetSpec> targets;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TargetSpec {
        private String destination;        // logical broker destination OR webhook URL (depends on type)
        private String webhookUrl;
        /** Optional conditional routing match for MANY_TO_MANY (e.g. headers/jsonpath). */
        private Map<String, String> matchHeaders;
        private String matchJsonPath;
        private String matchValue;
    }
}
