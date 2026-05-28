package io.valkeyry.ipaas.publish;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Cross-tenant publish endpoint. Caller's JWT is checked against each target's
 * workspace PROJECT_WRITE policy — denied targets are reported, not blocked.
 */
@Tag(name = "Multi-Tenant Publish",
     description = "Fan-out a single payload across multiple tenant+project workspaces in one call.")
@RestController
@RequestMapping("/api/v1/multi-publish")
@RequiredArgsConstructor
public class MultiTenantPublishController {

    private final MultiTenantPublishService service;

    @Operation(summary = "Publish a payload to a list of (tenantId, projectId, destination) targets.")
    @ApiResponse(responseCode = "200", description = "Per-target outcome stream.")
    @PostMapping
    public Flux<MultiTenantPublishService.PublishOutcome> publish(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody MultiPublishRequest body) {
        return service.publish(jwt, body.getTargets(), body.getPayload());
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class MultiPublishRequest {
        private List<MultiTenantPublishService.PublishTarget> targets;
        private String payload;     // raw JSON string (or any UTF-8 text payload)
    }
}
