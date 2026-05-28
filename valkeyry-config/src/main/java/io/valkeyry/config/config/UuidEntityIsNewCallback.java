package io.valkeyry.config.config;

import io.valkeyry.config.domain.UuidEntity;
import org.springframework.data.r2dbc.mapping.event.AfterConvertCallback;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Single, generic post-load callback that flips {@link UuidEntity#markPersisted()} on every
 * {@code @Table}-mapped entity that implements {@link UuidEntity}. Registered automatically as a
 * {@code @Component}; Spring Data picks it up via the {@link AfterConvertCallback} ServiceLoader-
 * style mechanism.
 *
 * <p>This is the missing piece that makes {@code repo.save(entity)} on manually-keyed UUID rows
 * route to {@code INSERT} on first save (because {@code isNew()} = {@code true} by default) and
 * to {@code UPDATE} after a round-trip from the DB.</p>
 */
@Component
public class UuidEntityIsNewCallback implements AfterConvertCallback<UuidEntity> {

    @Override
    public org.reactivestreams.Publisher<UuidEntity> onAfterConvert(UuidEntity entity, SqlIdentifier table) {
        entity.markPersisted();
        return Mono.just(entity);
    }
}
