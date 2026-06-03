package io.valkeyry.config.repo;

import io.valkeyry.config.domain.admin.AdminTenant;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface AdminTenantRepository extends ReactiveCrudRepository<AdminTenant, String> {

    @Query("SELECT * FROM admin_tenant ORDER BY id ASC")
    Flux<AdminTenant> listAll();
}
