package io.valkeyry.loadtests;

import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Operator-Copilot workload. Each virtual user issues a short "list queues + peek DLQ"
 * conversation, so the Spring AI ChatClient + tool-calling path is exercised end-to-end.
 *
 * Tip: use a small Ollama model (qwen2.5:3b) to avoid CPU starvation when running this
 * locally alongside the full stack.
 */
public class CopilotChatSimulation extends Simulation {

    private static final String BASE_URL =
            System.getProperty("valkeyry.base-url", "http://localhost:8080");

    HttpProtocolBuilder httpProtocol = http.baseUrl(BASE_URL).acceptHeader("application/json");

    ScenarioBuilder chat = scenario("copilot chat")
            .exec(http("POST /copilot/chat #1")
                    .post("/api/v1/copilot/chat")
                    .header("Content-Type", "application/json")
                    .body(StringBody("""
                            {"sessionId":"load-${__threadNum}",\
                             "message":"List queues for acme-corp/payments-prod."}
                            """))
                    .check(status().is(200)))
            .pause(2)
            .exec(http("POST /copilot/chat #2")
                    .post("/api/v1/copilot/chat")
                    .header("Content-Type", "application/json")
                    .body(StringBody("""
                            {"sessionId":"load-${__threadNum}",\
                             "message":"Now peek the first 3 DLQ messages of orders."}
                            """))
                    .check(status().is(200)));

    {
        setUp(
                chat.injectOpen(
                        OpenInjectionStep.rampUsersPerSec(1).to(10).during(60),
                        OpenInjectionStep.constantUsersPerSec(10).during(120)
                )
        )
        .protocols(httpProtocol)
        .assertions(
                global().responseTime().percentile(95).lt(10_000), // local LLM is slow
                global().failedRequests().percent().lt(5.0)
        );
    }
}
