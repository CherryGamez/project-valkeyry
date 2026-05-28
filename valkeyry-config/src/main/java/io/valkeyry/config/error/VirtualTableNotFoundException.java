package io.valkeyry.config.error;

public class VirtualTableNotFoundException extends RuntimeException {
    public VirtualTableNotFoundException(String tenantId, String tableName) {
        super("Virtual table not found: " + tenantId + "/" + tableName);
    }
}
