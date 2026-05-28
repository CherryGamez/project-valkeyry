# Valkeyry Gatling Load Tests

Stand-alone Maven module that exercises a running Valkeyry instance with realistic
workloads. Reports are HTML and land in `target/gatling/`.

## Quick start

```powershell
# 1. Make sure Valkeyry is running and reachable on :8080
# 2. From repo root:
mvn -f load-tests/pom.xml clean test gatling:test                      # run ALL simulations
mvn -f load-tests/pom.xml gatling:test `
    -Dgatling.simulationClass=io.valkeyry.loadtests.PublishSimulation  # run ONE
mvn -f load-tests/pom.xml gatling:test -Divaas.base-url=http://10.0.0.5:8080
```

## Simulations

| Class                             | What it does                                       | Pass/fail SLOs                |
|-----------------------------------|----------------------------------------------------|-------------------------------|
| `PublishSimulation`               | 10→500 RPS ramp on single-tenant ingress publish  | p(95) &lt; 200&nbsp;ms, err &lt; 1% |
| `MultiTenantPublishSimulation`    | 5→150 RPS, 3-target fan-out per request           | p(95) &lt; 400&nbsp;ms, err &lt; 2% |
| `FileStreamingSimulation`         | 1 MiB body uploads, ramp 2→50 RPS                  | p(95) &lt; 1500&nbsp;ms, err &lt; 2% |
| `CopilotChatSimulation`           | Chat workload against Spring AI ChatClient        | p(95) &lt; 10 s, err &lt; 5%       |

## Tuning

* `-Dvalkeyry.base-url` — point at any environment (default `http://localhost:8080`).
* For Copilot tests, prefer the smaller `qwen2.5:3b` model in dev:
  ```bash
  docker compose exec ollama ollama pull qwen2.5:3b
  export COPILOT_MODEL=qwen2.5:3b
  ```
* On Windows PowerShell, use back-ticks (`` ` ``) for line continuations.

## Reading reports

Open `target/gatling/<simulation>-<timestamp>/index.html`. The "Assertions" section
shows the pass/fail summary; the "Stats" section breaks down p95/p99 per request.
