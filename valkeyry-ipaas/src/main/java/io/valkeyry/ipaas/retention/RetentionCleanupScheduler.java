package io.valkeyry.ipaas.retention;

import io.valkeyry.ipaas.config.IpaasProperties;
import io.valkeyry.ipaas.repository.MessageLogRepository;
import io.valkeyry.ipaas.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Reactive R2DBC purge job. Cron is configurable; default runs at midnight UTC.
 * Per-project retention_days overrides the platform-level default.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionCleanupScheduler {

    private final ProjectRepository projectRepo;
    private final MessageLogRepository messageLogRepo;
    private final IpaasProperties props;

    @Scheduled(cron = "${ipaas.retention.cron:0 0 0 * * *}", zone = "UTC")
    public void runPurge() {
        log.info("Retention purge job triggered.");
        projectRepo.findAll()
                .flatMap(project -> {
                    int days = project.getRetentionDays() == null
                            ? props.getRetention().getDefaultDays() : project.getRetentionDays();
                    OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
                    return messageLogRepo.deleteOlderThan(project.getTenantId(), project.getId(), cutoff)
                            .doOnNext(deleted -> log.info(
                                    "Purged {} message_logs (>{} days) tenant={} project={}",
                                    deleted, days, project.getTenantId(), project.getId()));
                })
                .subscribe(
                    n -> {},
                    e -> log.warn("Retention purge error: {}", e.toString()));
    }
}
