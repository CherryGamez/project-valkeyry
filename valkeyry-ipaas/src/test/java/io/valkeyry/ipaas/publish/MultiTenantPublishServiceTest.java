package io.valkeyry.ipaas.publish;

import io.valkeyry.ipaas.broker.BrokerClientFactory;
import io.valkeyry.ipaas.broker.ReactiveBrokerClient;
import io.valkeyry.ipaas.config.IpaasProperties;
import io.valkeyry.ipaas.domain.QueueAsset;
import io.valkeyry.ipaas.domain.UserAccessPolicy;
import io.valkeyry.ipaas.queue.QueueManagementService;
import io.valkeyry.ipaas.repository.UserAccessPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiTenantPublishServiceTest {

    @Mock QueueManagementService queueService;
    @Mock BrokerClientFactory brokerFactory;
    @Mock UserAccessPolicyRepository policyRepo;
    @Mock ReactiveBrokerClient broker;

    IpaasProperties props;
    MultiTenantPublishService service;

    private final String tA = "acme-corp";
    private final String pA = "payments-prod";
    private final String tB = "globex-eu";
    private final String pB = "billing-dev";

    @BeforeEach
    void setUp() {
        props = new IpaasProperties();
        service = new MultiTenantPublishService(queueService, brokerFactory, policyRepo, props);

        when(brokerFactory.get(anyString())).thenReturn(broker);
        when(broker.publish(any(), any(), anyString(), any(), anyMap())).thenReturn(Mono.empty());
        QueueAsset asset = QueueAsset.builder()
                .id(UUID.randomUUID()).tenantId(tA).projectId(pA)
                .destinationName("d").brokerType("RABBITMQ")
                .processingMode("QUEUE").provisioningMode("CATALOG").build();
        when(queueService.resolveOrLazyProvision(any(), any(), anyString())).thenReturn(Mono.just(asset));
    }

    private Jwt jwt(String subject) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), Map.of("sub", subject));
    }

    @Test
    void allowAnonymous_publishesToAllTargetsWithoutRbac() {
        props.getSecurity().setAllowAnonymous(true);
        var targets = List.of(
                new MultiTenantPublishService.PublishTarget(tA, pA, "d1"),
                new MultiTenantPublishService.PublishTarget(tB, pB, "d2"));

        StepVerifier.create(service.publish(null, targets, "{\"x\":1}").collectList())
                .assertNext(list -> {
                    org.assertj.core.api.Assertions.assertThat(list).hasSize(2)
                            .allMatch(o -> "ACCEPTED".equals(o.getStatus()));
                })
                .verifyComplete();
        verifyNoInteractions(policyRepo);
        verify(broker, times(2)).publish(any(), any(), anyString(), any(), anyMap());
    }

    @Test
    void rbacAllowed_publishesAcceptedOutcome() {
        when(policyRepo.findBySubjectAndTenantIdAndProjectIdAndPrivilege(
                eq("alice"), eq(tA), eq(pA), eq("PROJECT_WRITE")))
                .thenReturn(Mono.just(new UserAccessPolicy()));
        var targets = List.of(new MultiTenantPublishService.PublishTarget(tA, pA, "d1"));

        StepVerifier.create(service.publish(jwt("alice"), targets, "{}").collectList())
                .assertNext(list -> {
                    org.assertj.core.api.Assertions.assertThat(list).hasSize(1);
                    org.assertj.core.api.Assertions.assertThat(list.get(0).getStatus()).isEqualTo("ACCEPTED");
                })
                .verifyComplete();
    }

    @Test
    void rbacMissing_returnsDeniedOutcomeAndSkipsPublish() {
        when(policyRepo.findBySubjectAndTenantIdAndProjectIdAndPrivilege(
                eq("bob"), any(), any(), eq("PROJECT_WRITE")))
                .thenReturn(Mono.empty());
        var targets = List.of(new MultiTenantPublishService.PublishTarget(tA, pA, "d1"));

        StepVerifier.create(service.publish(jwt("bob"), targets, "{}").collectList())
                .assertNext(list -> {
                    org.assertj.core.api.Assertions.assertThat(list).hasSize(1);
                    org.assertj.core.api.Assertions.assertThat(list.get(0).getStatus()).isEqualTo("DENIED");
                })
                .verifyComplete();
        verify(broker, never()).publish(any(), any(), anyString(), any(), anyMap());
    }

    @Test
    void partialAuthorization_acceptsAllowedAndDeniesUnauthorized() {
        when(policyRepo.findBySubjectAndTenantIdAndProjectIdAndPrivilege(
                eq("carol"), eq(tA), eq(pA), eq("PROJECT_WRITE")))
                .thenReturn(Mono.just(new UserAccessPolicy()));
        when(policyRepo.findBySubjectAndTenantIdAndProjectIdAndPrivilege(
                eq("carol"), eq(tB), eq(pB), eq("PROJECT_WRITE")))
                .thenReturn(Mono.empty());

        var targets = List.of(
                new MultiTenantPublishService.PublishTarget(tA, pA, "d1"),
                new MultiTenantPublishService.PublishTarget(tB, pB, "d2"));

        StepVerifier.create(service.publish(jwt("carol"), targets, "{}").collectList())
                .assertNext(list -> {
                    org.assertj.core.api.Assertions.assertThat(list).hasSize(2);
                    long accepted = list.stream().filter(o -> "ACCEPTED".equals(o.getStatus())).count();
                    long denied = list.stream().filter(o -> "DENIED".equals(o.getStatus())).count();
                    org.assertj.core.api.Assertions.assertThat(accepted).isEqualTo(1);
                    org.assertj.core.api.Assertions.assertThat(denied).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void brokerErrorIsCapturedAsErrorOutcome() {
        props.getSecurity().setAllowAnonymous(true);
        when(broker.publish(any(), any(), anyString(), any(), anyMap()))
                .thenReturn(Mono.error(new RuntimeException("broker unreachable")));

        var targets = List.of(new MultiTenantPublishService.PublishTarget(tA, pA, "d1"));
        StepVerifier.create(service.publish(null, targets, "{}").collectList())
                .assertNext(list -> {
                    org.assertj.core.api.Assertions.assertThat(list).hasSize(1);
                    org.assertj.core.api.Assertions.assertThat(list.get(0).getStatus()).isEqualTo("ERROR");
                    org.assertj.core.api.Assertions.assertThat(list.get(0).getError()).contains("broker unreachable");
                })
                .verifyComplete();
    }

    @Test
    void noJwtAndAnonymousOff_returnsDeniedForAllTargets() {
        var targets = List.of(new MultiTenantPublishService.PublishTarget(tA, pA, "d1"));
        StepVerifier.create(service.publish(null, targets, "{}").collectList())
                .assertNext(list -> {
                    org.assertj.core.api.Assertions.assertThat(list).hasSize(1);
                    org.assertj.core.api.Assertions.assertThat(list.get(0).getStatus()).isEqualTo("DENIED");
                })
                .verifyComplete();
    }
}
