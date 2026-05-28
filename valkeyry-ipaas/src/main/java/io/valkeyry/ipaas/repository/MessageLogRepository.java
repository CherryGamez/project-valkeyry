package io.valkeyry.ipaas.repository;

import io.valkeyry.ipaas.domain.MessageLog;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface MessageLogRepository extends ReactiveCrudRepository<MessageLog, UUID> {

    @Modifying
    @Query("DELETE FROM message_logs WHERE tenant_id = :t AND project_id = :p AND created_at < :cutoff")
    Mono<Integer> deleteOlderThan(String t, String p, OffsetDateTime cutoff);
}
