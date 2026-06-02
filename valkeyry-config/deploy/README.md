# Cloud-native deployment for `valkeyry-config`

Two ready-to-go paths:

| Folder                | Use it when…                                                 |
|-----------------------|--------------------------------------------------------------|
| [`k8s/`](k8s/)        | You apply plain manifests with `kubectl apply -f …`          |
| [`helm/valkeyry-config/`](helm/valkeyry-config/) | You manage releases with Helm 3 (`helm install`) |

Both flavours share the same opinions:

- **12-factor config** — every URL, port, TLS path and credential comes
  from env vars / Secrets / ConfigMaps. Zero values are baked into the
  image.
- **TLS-first** — toggle `VALKEYRY_SSL_ENABLED=true`, mount a Kubernetes
  `tls` Secret at `/etc/valkeyry/tls`, set `VALKEYRY_SSL_CERT_PEM` and
  `VALKEYRY_SSL_KEY_PEM`. cert-manager users get auto-rotation for free.
- **K8s-native probes** — liveness uses `/actuator/health/liveness`,
  readiness uses `/actuator/health/readiness` (separate Spring Actuator
  groups, enabled in `application.yml`).
- **Graceful shutdown** — `server.shutdown=graceful` honours the
  `terminationGracePeriodSeconds`; the pod drains in-flight reactive
  requests before SIGKILL.
- **Resource hygiene** — `securityContext` runs as non-root, drops all
  Linux capabilities, sets `readOnlyRootFilesystem: true` with an
  emptyDir for `/tmp`.
- **Observability** — Prometheus scrape annotations + `prometheus`
  Actuator endpoint exposed on the management port.
- **HA defaults** — `replicas: 2`, `topologySpreadConstraints` across
  zones, `PodDisruptionBudget{minAvailable:1}`, and an `HPA` scaling
  2 → 10 on CPU > 60 %.

## Quick start (Helm, with cert-manager)

```bash
helm upgrade --install valkeyry deploy/helm/valkeyry-config \
  --namespace valkeyry --create-namespace \
  --set image.repository=ghcr.io/your-org/valkeyry-config \
  --set image.tag=v1.0.0 \
  --set postgres.url='r2dbc:postgresql://pg-cluster:5432/valkeyry_config' \
  --set tls.enabled=true \
  --set tls.certManager.enabled=true \
  --set ingress.host=valkeyry-config.acme.io
```

## Quick start (plain manifests)

```bash
# Provide DB creds + TLS cert via Secrets in your cluster, then:
kubectl apply -f deploy/k8s/
kubectl -n valkeyry rollout status deploy/valkeyry-config
```
