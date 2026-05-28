package io.valkeyry.config.repo;

import io.valkeyry.config.domain.AuditWebhookSubscription;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AuditWebhookSubscriptionRepository
        extends ReactiveCrudRepository<AuditWebhookSubscription, UUID> {

    @Query("""
        SELECT * FROM audit_webhook_subscription
         WHERE tenant_id = :tenantId
         ORDER BY created_at DESC
    """)
    Flux<AuditWebhookSubscription> findByTenant(String tenantId);

    @Query("""
        SELECT * FROM audit_webhook_subscription
         WHERE tenant_id = :tenantId
           AND enabled   = true
    """)
    Flux<AuditWebhookSubscription> findEnabledByTenant(String tenantId);

    /** All enabled subscriptions across every tenant — used by the publisher fan-out. */
    @Query("""
        SELECT * FROM audit_webhook_subscription
         WHERE enabled = true
    """)
    Flux<AuditWebhookSubscription> findAllEnabled();

    @Query("""
        SELECT * FROM audit_webhook_subscription
         WHERE tenant_id = :tenantId
           AND id        = :id
    """)
    Mono<AuditWebhookSubscription> findByTenantAndId(String tenantId, UUID id);

    @Query("""
        DELETE FROM audit_webhook_subscription
         WHERE tenant_id = :tenantId
           AND id        = :id
    """)
    Mono<Long> deleteByTenantAndId(String tenantId, UUID id);
}
