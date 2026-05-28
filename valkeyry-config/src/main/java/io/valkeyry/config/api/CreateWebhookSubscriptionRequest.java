package io.valkeyry.config.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for creating an audit webhook subscription.
 *
 * <p>{@code url} must be an absolute http(s) URI. {@code secret} is optional — when present it
 * overrides the global HMAC secret for this target only. {@code description} is a free-form
 * label rendered in the Admin GUI list.</p>
 */
public record CreateWebhookSubscriptionRequest(
        @NotBlank
        @Pattern(regexp = "^https?://.+", message = "url must start with http:// or https://")
        @Size(max = 2048)
        String url,

        @Size(max = 512)
        String description,

        @Size(max = 512)
        String secret,

        Boolean enabled
) {}
