package io.valkeyry.config.repo;

import io.valkeyry.config.domain.admin.AppUser;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AppUserRepository extends ReactiveCrudRepository<AppUser, UUID> {

    @Query("SELECT * FROM app_user WHERE username = :username")
    Mono<AppUser> findByUsername(String username);

    @Query("SELECT * FROM app_user ORDER BY username ASC")
    Flux<AppUser> listAll();
}
