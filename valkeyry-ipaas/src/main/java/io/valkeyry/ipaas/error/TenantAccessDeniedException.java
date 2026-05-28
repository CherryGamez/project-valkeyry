package io.valkeyry.ipaas.error;

public class TenantAccessDeniedException extends RuntimeException {
    public TenantAccessDeniedException(String tenantId, String subject) {
        super("Subject '" + subject + "' is not entitled to tenant '" + tenantId + "'");
    }
}
