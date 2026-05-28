package io.valkeyry.loadtests;

import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Hammers the Claim Check file-streaming endpoint with 1 MB binary uploads,
 * verifying back-pressure and streaming-write throughput.
 */
public class FileStreamingSimulation extends Simulation {

    private static final String BASE_URL =
            System.getProperty("valkeyry.base-url", "http://localhost:8080");

    HttpProtocolBuilder httpProtocol = http.baseUrl(BASE_URL);

    // 1 MB of random bytes generated once per Gatling JVM — fine for a load test.
    private static final byte[] PAYLOAD;
    static {
        PAYLOAD = new byte[1 << 20]; // 1 MiB
        new java.util.Random(42L).nextBytes(PAYLOAD);
    }

    ScenarioBuilder upload = scenario("streaming upload (1 MB)")
            .exec(http("POST /files/upload")
                    .post("/api/v1/acme-corp/payments-prod/files/upload/blob-${__counter}")
                    .header("Content-Type", "application/octet-stream")
                    .body(ByteArrayBody(PAYLOAD))
                    .check(status().in(200, 201)));

    {
        setUp(
                upload.injectOpen(
                        OpenInjectionStep.rampUsersPerSec(2).to(50).during(60),
                        OpenInjectionStep.constantUsersPerSec(50).during(120)
                )
        )
        .protocols(httpProtocol)
        .assertions(
                global().responseTime().percentile(95).lt(1500),
                global().failedRequests().percent().lt(2.0)
        );
    }
}
