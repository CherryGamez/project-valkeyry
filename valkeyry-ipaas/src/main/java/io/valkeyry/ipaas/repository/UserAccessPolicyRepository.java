package io.valkeyry.ipaas.repository;

import io.valkeyry.ipaas.domain.UserAccessPolicy;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface UserAccessPolicyRepository extends ReactiveCrudRepository<UserAccessPolicy, UUID> {

    Mono<UserAccessPolicy> findBySubjectAndTenantIdAndProjectIdAndPrivilege(
            String subject, String tenantId, String projectId, String privilege);
}
