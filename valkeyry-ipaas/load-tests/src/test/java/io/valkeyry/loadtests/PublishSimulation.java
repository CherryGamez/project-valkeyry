package io.valkeyry.loadtests;

import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Ramps up RPS to a single-tenant publish endpoint and validates SLOs:
 * <ul>
 *   <li>p(95) latency &lt; 200&nbsp;ms</li>
 *   <li>error rate &lt; 1%</li>
 * </ul>
 *
 * Override base URL with -Dvalkeyry.base-url=http://...:8080 .
 */
public class PublishSimulation extends Simulation {

    private static final String BASE_URL =
            System.getProperty("valkeyry.base-url", "http://localhost:8080");

    HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .userAgentHeader("valkeyry-gatling/1.0")
            .acceptHeader("application/json");

    ScenarioBuilder publish = scenario("single-tenant publish")
            .exec(http("POST ingress")
                    .post("/api/v1/acme-corp/payments-prod/ingress/orders")
                    .header("Content-Type", "application/json")
                    .body(StringBody(session -> "{\"orderId\":" + session.userId()
                            + ",\"total\":" + (100 + (Long.parseLong(session.userId()) % 900)) + "}"))
                    .check(status().is(200)));

    {
        setUp(
                publish.injectOpen(
                        OpenInjectionStep.rampUsersPerSec(10).to(500).during(120),
                        OpenInjectionStep.constantUsersPerSec(500).during(180)
                )
        )
        .protocols(httpProtocol)
        .assertions(
                global().responseTime().percentile(95).lt(200),
                global().failedRequests().percent().lt(1.0)
        );
    }
}
