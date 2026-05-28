package io.valkeyry.config.error;

/** Thrown when the caller tries to write a record whose payload-hash already matches the current head. */
public class IdempotentDuplicateException extends RuntimeException {
    private final String payloadHash;
    public IdempotentDuplicateException(String recordKey, String payloadHash) {
        super("Idempotency Guard: record '" + recordKey + "' already has payload hash " + payloadHash);
        this.payloadHash = payloadHash;
    }
    public String payloadHash() { return payloadHash; }
}
