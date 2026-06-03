package io.valkeyry.config.service.query;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compiles a flat list of PL/SQL-style conditions into a single parameterised
 * Postgres {@code WHERE} fragment that runs against the {@code virtual_table_entry.data}
 * JSONB column.
 *
 * <p>Each condition carries its own connector (AND / OR) which is used to join it to the
 * <em>previous</em> condition; the first condition's connector is ignored. This matches the
 * "per-row connector, no parenthesised nesting" shape the UI exposes — Excel-style filters
 * but with mixed operators.</p>
 *
 * <h2>Supported ops</h2>
 * <ul>
 *   <li>String / generic:
 *       {@code equals}, {@code notEquals}, {@code contains}, {@code notContains},
 *       {@code startsWith}, {@code endsWith}, {@code regex}, {@code in}, {@code notIn},
 *       {@code isNull}, {@code isNotNull}</li>
 *   <li>Numeric: {@code gt}, {@code gte}, {@code lt}, {@code lte}, {@code between}</li>
 *   <li>Date / timestamp:
 *       {@code before}, {@code after}, {@code onDate}, {@code betweenDates}
 *       (values must parse as ISO-8601 — Postgres' {@code ::timestamptz} does the rest)</li>
 * </ul>
 *
 * <h2>SQL projection rules</h2>
 * <ul>
 *   <li>{@code recordKey} maps to the {@code record_key} column directly.</li>
 *   <li>{@code data.&lt;col&gt;} (and the legacy bare {@code &lt;col&gt;}) projects
 *       to {@code data->>'col'} for text/regex ops, {@code (data->>'col')::numeric} for
 *       comparisons, and {@code (data->>'col')::timestamptz} for date ops.</li>
 *   <li>{@code isNull} treats both SQL NULL <em>and</em> the JSON {@code null} literal —
 *       e.g. {@code data->'col' IS NULL OR jsonb_typeof(data->'col') = 'null'}.</li>
 * </ul>
 *
 * <p>All literal values are inserted as {@code :p0}, {@code :p1}, … placeholders;
 * nothing is concatenated into the SQL string. Field names are validated against
 * {@link #FIELD_PATTERN} so a malicious column reference can't escape into SQL.</p>
 */
public final class PredicateCompiler {

    /** Output: SQL fragment ready to follow {@code AND (...)} plus the named-parameter map. */
    public record Compiled(String sql, Map<String, Object> params) {}

    /** Single condition row from the API. */
    public record Condition(String field, String op, JsonNode value, JsonNode value2, String connector) {}

    /** field accepts: {@code recordKey} or {@code [data.]<path>} where {@code <path>} matches this. */
    private static final java.util.regex.Pattern FIELD_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");

    private PredicateCompiler() {}

    public static Compiled compile(List<Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return new Compiled("TRUE", Map.of());
        }
        StringBuilder sql = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        int paramIdx = 0;

        for (int i = 0; i < conditions.size(); i++) {
            Condition c = conditions.get(i);
            if (c == null || c.op() == null) {
                throw new IllegalArgumentException("Condition[" + i + "] is missing 'op'");
            }
            String op = c.op().trim();

            // 1) Connector — first row has none.
            if (i > 0) {
                String conn = c.connector() == null ? "AND" : c.connector().trim().toUpperCase(Locale.ROOT);
                if (!"AND".equals(conn) && !"OR".equals(conn)) {
                    throw new IllegalArgumentException("connector must be AND or OR (was '" + conn + "')");
                }
                sql.append(' ').append(conn).append(' ');
            }

            // 2) Resolve the SQL projection of the field (recordKey vs data->>'col' …).
            String fieldRaw = c.field() == null ? "" : c.field().trim();
            // No-field row is treated as TRUE so an empty/skipped row doesn't break the query.
            if (fieldRaw.isEmpty()) { sql.append("TRUE"); continue; }

            String column = fieldRaw.startsWith("data.") ? fieldRaw.substring(5) : fieldRaw;
            String fieldExpr;
            String fieldRawExpr; // for IS NULL on the underlying jsonb element
            if ("recordKey".equals(fieldRaw)) {
                fieldExpr = "record_key";
                fieldRawExpr = "record_key";
            } else {
                if (!FIELD_PATTERN.matcher(column).matches()) {
                    throw new IllegalArgumentException("Illegal field name: '" + fieldRaw + "'");
                }
                // Dotted paths become nested JSONB accessors: data->'a'->'b'->>'c'
                fieldExpr    = jsonAccess(column, /*asText=*/true);
                fieldRawExpr = jsonAccess(column, /*asText=*/false);
            }

            // 3) Emit the per-op SQL + named params.
            sql.append('(');
            switch (op) {
                case "equals" -> {
                    sql.append(fieldExpr).append(" = :p").append(paramIdx);
                    params.put("p" + paramIdx++, textOf(c.value()));
                }
                case "notEquals" -> {
                    sql.append(fieldExpr).append(" <> :p").append(paramIdx);
                    params.put("p" + paramIdx++, textOf(c.value()));
                }
                case "contains" -> {
                    sql.append(fieldExpr).append(" ILIKE :p").append(paramIdx);
                    params.put("p" + paramIdx++, "%" + escapeLike(textOf(c.value())) + "%");
                }
                case "notContains" -> {
                    sql.append(fieldExpr).append(" NOT ILIKE :p").append(paramIdx);
                    params.put("p" + paramIdx++, "%" + escapeLike(textOf(c.value())) + "%");
                }
                case "startsWith" -> {
                    sql.append(fieldExpr).append(" ILIKE :p").append(paramIdx);
                    params.put("p" + paramIdx++, escapeLike(textOf(c.value())) + "%");
                }
                case "endsWith" -> {
                    sql.append(fieldExpr).append(" ILIKE :p").append(paramIdx);
                    params.put("p" + paramIdx++, "%" + escapeLike(textOf(c.value())));
                }
                case "regex" -> {
                    sql.append(fieldExpr).append(" ~* :p").append(paramIdx);
                    params.put("p" + paramIdx++, textOf(c.value()));
                }
                case "in", "notIn" -> {
                    List<String> list = listOf(c.value());
                    if (list.isEmpty()) { sql.append("FALSE"); break; }
                    sql.append(fieldExpr).append("notIn".equals(op) ? " NOT IN (" : " IN (");
                    for (int j = 0; j < list.size(); j++) {
                        if (j > 0) sql.append(", ");
                        sql.append(":p").append(paramIdx);
                        params.put("p" + paramIdx++, list.get(j));
                    }
                    sql.append(')');
                }
                case "gt", "gte", "lt", "lte" -> {
                    String sym = switch (op) { case "gt" -> ">"; case "gte" -> ">="; case "lt" -> "<"; default -> "<="; };
                    sql.append('(').append(fieldExpr).append(")::numeric ").append(sym).append(" :p").append(paramIdx);
                    params.put("p" + paramIdx++, numberOf(c.value()));
                }
                case "between" -> {
                    sql.append('(').append(fieldExpr).append(")::numeric BETWEEN :p")
                       .append(paramIdx).append(" AND :p").append(paramIdx + 1);
                    params.put("p" + paramIdx++, numberOf(c.value()));
                    params.put("p" + paramIdx++, numberOf(c.value2()));
                }
                case "before", "after", "onDate" -> {
                    String sym = "before".equals(op) ? "<" : "after".equals(op) ? ">" : "::date =";
                    sql.append('(').append(fieldExpr).append(")::timestamptz ").append(sym).append(" :p").append(paramIdx).append("::timestamptz");
                    params.put("p" + paramIdx++, textOf(c.value()));
                }
                case "betweenDates" -> {
                    sql.append('(').append(fieldExpr).append(")::timestamptz BETWEEN :p").append(paramIdx)
                       .append("::timestamptz AND :p").append(paramIdx + 1).append("::timestamptz");
                    params.put("p" + paramIdx++, textOf(c.value()));
                    params.put("p" + paramIdx++, textOf(c.value2()));
                }
                case "isNull" -> {
                    if ("record_key".equals(fieldExpr)) {
                        sql.append(fieldExpr).append(" IS NULL");
                    } else {
                        sql.append('(').append(fieldRawExpr).append(" IS NULL OR jsonb_typeof(")
                           .append(fieldRawExpr).append(") = 'null')");
                    }
                }
                case "isNotNull" -> {
                    if ("record_key".equals(fieldExpr)) {
                        sql.append(fieldExpr).append(" IS NOT NULL");
                    } else {
                        sql.append('(').append(fieldRawExpr).append(" IS NOT NULL AND jsonb_typeof(")
                           .append(fieldRawExpr).append(") <> 'null')");
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported op: '" + op + "'");
            }
            sql.append(')');
        }
        return new Compiled(sql.toString(), params);
    }

    // ---------------- helpers ----------------

    /** Build {@code data->'a'->'b'->>'c'} (or {@code ->} if you want the raw JSON node). */
    private static String jsonAccess(String dottedPath, boolean asText) {
        String[] parts = dottedPath.split("\\.");
        StringBuilder b = new StringBuilder("data");
        for (int i = 0; i < parts.length; i++) {
            boolean last = i == parts.length - 1;
            b.append(asText && last ? "->>" : "->").append('\'').append(parts[i].replace("'", "''")).append('\'');
        }
        return b.toString();
    }

    private static String textOf(JsonNode v) {
        if (v == null || v.isNull()) return null;
        return v.isTextual() ? v.asText() : v.toString().replaceAll("^\"|\"$", "");
    }

    private static Double numberOf(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.asDouble();
        try { return Double.parseDouble(v.asText()); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected a numeric value, got: " + v);
        }
    }

    private static List<String> listOf(JsonNode v) {
        List<String> out = new ArrayList<>();
        if (v == null || v.isNull()) return out;
        if (v.isArray()) {
            for (Iterator<JsonNode> it = v.elements(); it.hasNext(); ) {
                JsonNode el = it.next();
                if (!el.isNull()) out.add(el.isTextual() ? el.asText() : el.toString().replaceAll("^\"|\"$", ""));
            }
        } else {
            // CSV fallback: "a, b, c" → ["a","b","c"]
            for (String s : v.asText().split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    /** Defang LIKE wildcards in user input so {@code contains "50%"} matches literally. */
    private static String escapeLike(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
