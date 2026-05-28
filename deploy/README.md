# Deploy

Two ways to deploy the ecosystem:

## 1) Plain Kubernetes manifests (no templating)

```bash
kubectl apply -f deploy/k8s/valkeyry-config.yaml
kubectl apply -f deploy/k8s/valkeyry-ipaas.yaml
```

Both manifests assume the namespace `valkeyry` (created by the config manifest), and
point at the in-cluster service names `postgres`, `valkey`, `openldap`, `kafka-bootstrap`,
`rabbitmq`, and `activemq`. **Edit secrets before applying** — defaults are placeholders.

## 2) Helm charts (recommended)

```bash
helm install vk-config deploy/helm/valkeyry-config \
    --namespace valkeyry --create-namespace \
    --set-string postgres.password=$(openssl rand -base64 32) \
    --set-string ldap.managerPass=$(openssl rand -base64 32)

helm install vk-core deploy/helm/valkeyry-ipaas \
    --namespace valkeyry \
    --set-string claimcheck.s3.accessKey=$AWS_ACCESS_KEY_ID \
    --set-string claimcheck.s3.secretKey=$AWS_SECRET_ACCESS_KEY
```

For production, manage secrets through external-secrets, sealed-secrets, or the CSI
secret-store driver — never commit real values to Git.

## Building images

```bash
docker build -f valkeyry-config/Dockerfile -t ghcr.io/your-org/valkeyry-config:1.0.0-SNAPSHOT .
docker build -f valkeyry-ipaas/Dockerfile   -t ghcr.io/your-org/valkeyry-ipaas:1.0.0-SNAPSHOT   .
docker push    ghcr.io/your-org/valkeyry-config:1.0.0-SNAPSHOT
docker push    ghcr.io/your-org/valkeyry-ipaas:1.0.0-SNAPSHOT
```

The Dockerfiles use a two-stage Maven build → distroless-style Temurin JRE Alpine runtime
under a non-root user, with a read-only root filesystem and dropped capabilities.

## Companion services

These manifests *consume* but do not provision the upstream services. Either bring your own
or use the operators below:

| Service | Operator / chart |
|---------|------------------|
| Postgres | [zalando/postgres-operator](https://github.com/zalando/postgres-operator) or [crunchy-data/postgres-operator](https://github.com/CrunchyData/postgres-operator) |
| Kafka    | [strimzi/strimzi-kafka-operator](https://strimzi.io) |
| RabbitMQ | [rabbitmq/cluster-operator](https://www.rabbitmq.com/kubernetes/operator) |
| ActiveMQ Classic | [apache/activemq-artemis-operator](https://activemq.apache.org/components/artemis/) (or Helm) |
| Valkey   | upstream `valkey/valkey` image as a Statefulset, or Bitnami chart |
| OpenLDAP | `bitnami/openldap` Helm chart |
| MinIO    | `minio/operator` |
| OIDC     | Keycloak Operator, or any external provider (Auth0, Okta, ...) |
