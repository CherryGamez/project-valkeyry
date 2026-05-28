package io.valkeyry.ipaas.security;

/** Validation constraints for user-facing tenant/project identifier slugs. */
public final class WorkspaceId {
    /** Allowed chars: ASCII alphanumeric, dash, underscore. 1..200 chars. */
    public static final String PATTERN = "^[A-Za-z0-9_-]{1,200}$";
    public static final int MAX_LENGTH = 200;
    public static final String MESSAGE  =
            "must be 1-200 chars, alphanumeric with '-' or '_' (^[A-Za-z0-9_-]{1,200}$)";
    private WorkspaceId() {}
}
