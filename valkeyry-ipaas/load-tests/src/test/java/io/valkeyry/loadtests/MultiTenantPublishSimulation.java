package io.valkeyry.loadtests;

import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Multi-tenant publish fan-out simulation: every request hits POST /multi-publish
 * with 3 targets across two tenants, exercising the per-target RBAC + lazy provisioning
 * path. Assertions a bit looser than the single-tenant test because each call publishes 3x.
 */
public class MultiTenantPublishSimulation extends Simulation {

    private static final String BASE_URL =
            System.getProperty("valkeyry.base-url", "http://localhost:8080");

    HttpProtocolBuilder httpProtocol = http.baseUrl(BASE_URL).acceptHeader("application/json");

    private static final String BODY = "{"
            + "\"targets\":["
            + "{\"tenantId\":\"acme-corp\",\"projectId\":\"payments-prod\",\"destination\":\"events.fanout\"},"
            + "{\"tenantId\":\"acme-corp\",\"projectId\":\"reporting-prod\",\"destination\":\"events.fanout\"},"
            + "{\"tenantId\":\"globex-eu\",\"projectId\":\"billing-dev\",\"destination\":\"events.fanout\"}"
            + "],\"payload\":\"{\\\"k\\\":\\\"v\\\"}\"}";

    ScenarioBuilder multi = scenario("multi-publish fan-out")
            .exec(http("POST /multi-publish")
                    .post("/api/v1/multi-publish")
                    .header("Content-Type", "application/json")
                    .body(StringBody(BODY))
                    .check(status().in(200, 207)));

    {
        setUp(
                multi.injectOpen(
                        OpenInjectionStep.rampUsersPerSec(5).to(150).during(120),
                        OpenInjectionStep.constantUsersPerSec(150).during(180)
                )
        )
        .protocols(httpProtocol)
        .assertions(
                global().responseTime().percentile(95).lt(400),
                global().failedRequests().percent().lt(2.0)
        );
    }
}
