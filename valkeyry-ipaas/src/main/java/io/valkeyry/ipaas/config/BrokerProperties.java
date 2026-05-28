package io.valkeyry.ipaas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bind for {@code valkeyry.brokers.*}. */
@ConfigurationProperties(prefix = "valkeyry.brokers")
public class BrokerProperties {

    /** YAML key {@code valkeyry.brokers.default-broker} (kebab-case). */
    private String defaultBroker = "kafka";
    private Kafka kafka = new Kafka();
    private Rabbit rabbit = new Rabbit();
    private ActiveMq activemq = new ActiveMq();

    public String getDefaultBroker() { return defaultBroker; }
    public void setDefaultBroker(String defaultBroker) { this.defaultBroker = defaultBroker; }
    public Kafka getKafka() { return kafka; }
    public void setKafka(Kafka kafka) { this.kafka = kafka; }
    public Rabbit getRabbit() { return rabbit; }
    public void setRabbit(Rabbit rabbit) { this.rabbit = rabbit; }
    public ActiveMq getActivemq() { return activemq; }
    public void setActivemq(ActiveMq activemq) { this.activemq = activemq; }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String clientId = "valkeyry-ipaas";
        public String getBootstrapServers() { return bootstrapServers; }
        public void setBootstrapServers(String v) { this.bootstrapServers = v; }
        public String getClientId() { return clientId; }
        public void setClientId(String v) { this.clientId = v; }
    }
    public static class Rabbit {
        private String uri = "amqp://guest:guest@localhost:5672/";
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
    }
    public static class ActiveMq {
        private String brokerUrl = "tcp://localhost:61616";
        private String user = "admin";
        private String password = "admin";
        public String getBrokerUrl() { return brokerUrl; }
        public void setBrokerUrl(String v) { this.brokerUrl = v; }
        public String getUser() { return user; }
        public void setUser(String v) { this.user = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
    }
}
