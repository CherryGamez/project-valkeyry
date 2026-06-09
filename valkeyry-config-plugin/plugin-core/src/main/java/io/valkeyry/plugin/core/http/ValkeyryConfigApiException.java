package io.valkeyry.plugin.core.http;

import java.util.List;

/**
 * Structured exception thrown by {@link ValkeyryConfigClient} when the server returns a
 * non-2xx response that wasn't a clean 207 / 422 (those are partial-failures and are
 * returned via {@link ValkeyryConfigClient.BatchResult#failures()} instead).
 *
 * <p>Carries:</p>
 * <ul>
 *   <li>{@code statusCode} — HTTP status code from the server</li>
 *   <li>{@code problemType} — RFC-7807 {@code type} URI when the server returned a
 *       ProblemDetail body, otherwise {@code null}</li>
 *   <li>{@code detail} — human-readable detail (server's {@code detail} field, falling back
 *       to the response body)</li>
 *   <li>{@code traceId} — when the server included one (5xx fallback), this is the anchor
 *       the user copies into a server-log grep</li>
 *   <li>{@code rawBody} — full response body, for the rare case the server returned
 *       something we couldn't parse</li>
 * </ul>
 */
public class ValkeyryConfigApiException extends RuntimeException {

    private final int statusCode;
    private final String problemType;
    private final String detail;
    private final String traceId;
    private final String rawBody;
    private final List<BatchEntryError> failures;

    public ValkeyryConfigApiException(int statusCode, String problemType, String detail,
                                      String traceId, String rawBody, List<BatchEntryError> failures) {
        super(buildMessage(statusCode, problemType, detail, traceId, failures));
        this.statusCode = statusCode;
        this.problemType = problemType;
        this.detail = detail;
        this.traceId = traceId;
        this.rawBody = rawBody;
        this.failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public int statusCode()                 { return statusCode; }
    public String problemType()             { return problemType; }
    public String detail()                  { return detail; }
    public String traceId()                 { return traceId; }
    public String rawBody()                 { return rawBody; }
    public List<BatchEntryError> failures() { return failures; }

    private static String buildMessage(int statusCode, String type, String detail,
                                       String traceId, List<BatchEntryError> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP ").append(statusCode);
        if (type != null && !type.isBlank()) sb.append(" [").append(type).append("]");
        if (detail != null && !detail.isBlank()) sb.append(" — ").append(detail);
        if (traceId != null && !traceId.isBlank()) {
            sb.append(" (server traceId=").append(traceId).append(" — grep the server log for it)");
        }
        if (failures != null && !failures.isEmpty()) {
            sb.append("\n  Failing rows (").append(failures.size()).append("):");
            for (BatchEntryError e : failures) {
                sb.append("\n    - ").append(e.formatOneLine().replace("\n", "\n      "));
            }
        }
        return sb.toString();
    }
}
