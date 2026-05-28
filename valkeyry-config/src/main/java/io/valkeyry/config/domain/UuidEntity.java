package io.valkeyry.config.domain;

import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Marker for our manually-keyed (UUID) entities. R2DBC's
 * {@link org.springframework.data.r2dbc.repository.support.SimpleR2dbcRepository#save save()}
 * relies on the {@link Persistable#isNew()} signal to decide between {@code INSERT} and
 * {@code UPDATE}; without this, manually-assigned UUIDs would always trigger {@code UPDATE}
 * (and fail with <em>"Row with Id [...] does not exist"</em>).
 *
 * <p>Each implementer keeps a {@code @Transient} {@code boolean isNew = true} field, defaulting
 * to {@code true}. The single bean {@link
 * io.valkeyry.config.config.UuidEntityIsNewCallback} flips it to {@code false} after each
 * {@link Table @Table}-mapped read, so subsequent {@code save()} calls on the same instance
 * correctly route to {@code UPDATE}. New instances built in services still report {@code isNew()}
 * = {@code true} on first {@code save()}.</p>
 */
public interface UuidEntity extends Persistable<java.util.UUID> {

    /**
     * Marks this instance as already-persisted. Called by the post-load callback. Services that
     * legitimately want to UPDATE a freshly-built object would set this to {@code false} before
     * calling {@code save()} — none do today.
     */
    void markPersisted();
}
