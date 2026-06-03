package io.valkeyry.config.service.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-tests for the SQL emission rules of {@link PredicateCompiler}. We never run the SQL —
 * we just assert the produced fragment looks the way the database expects, and that no user
 * input ever leaks into the SQL string (everything goes through named params).
 */
class PredicateCompilerTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static PredicateCompiler.Condition c(String f, String op, Object v) {
        return new PredicateCompiler.Condition(f, op, M.valueToTree(v), null, "AND");
    }
    private static PredicateCompiler.Condition c(String f, String op, Object v, Object v2, String conn) {
        return new PredicateCompiler.Condition(f, op, M.valueToTree(v), M.valueToTree(v2), conn);
    }

    @Test
    void emptyListYieldsTrue() {
        PredicateCompiler.Compiled out = PredicateCompiler.compile(List.of());
        assertEquals("TRUE", out.sql());
        assertTrue(out.params().isEmpty());
    }

    @Test
    void singleEqualsOnRecordKeyUsesPrimaryKeyColumn() {
        PredicateCompiler.Compiled out = PredicateCompiler.compile(
                List.of(c("recordKey", "equals", "alice@acme.io")));
        assertEquals("(record_key = :p0)", out.sql());
        assertEquals("alice@acme.io", out.params().get("p0"));
    }

    @Test
    void singleEqualsOnDataColumnUsesJsonbAccessor() {
        PredicateCompiler.Compiled out = PredicateCompiler.compile(
                List.of(c("data.email", "equals", "alice@acme.io")));
        assertEquals("(data->>'email' = :p0)", out.sql());
    }

    @Test
    void nestedDottedPathFlatensToJsonbChain() {
        PredicateCompiler.Compiled out = PredicateCompiler.compile(
                List.of(c("data.profile.address.city", "equals", "Vienna")));
        assertEquals("(data->'profile'->'address'->>'city' = :p0)", out.sql());
    }

    @Test
    void containsBecomesIlikeWithBothWildcards() {
        PredicateCompiler.Compiled out = PredicateCompiler.compile(
                List.of(c("data.email", "contains", "acme")));
        assertEquals("(data->>'email' ILIKE :p0)", out.sql());
        assertEquals("%acme%", out.params().get("p0"));
    }

    @Test
    void startsWithAndEndsWithGetTheRightWildcards() {
        PredicateCompiler.Compiled out1 = PredicateCompiler.compile(List.of(c("data.email", "startsWith", "alice")));
        assertEquals("alice%", out1.params().get("p0"));
        PredicateCompiler.Compiled out2 = PredicateCompiler.compile(List.of(c("data.email", "endsWith", "@acme.io")));
        assertEquals("%@acme.io", out2.params().get("p0"));
    }

    @Test
    void containsEscapesLikeWildcardsInUserInput() {
        // A user typing literal "50%" should match "50%", not "50<anything>"
        PredicateCompiler.Compiled out = PredicateCompiler.compile(List.of(c("data.note", "contains", "50%")));
        assertEquals("%50\\%%", out.params().get("p0"));
    }

    @Test
    void inAcceptsArrayAndCsvFallback() {
        PredicateCompiler.Compiled fromArr = PredicateCompiler.compile(
                List.of(c("data.role", "in", List.of("admin", "writer"))));
        assertEquals("(data->>'role' IN (:p0, :p1))", fromArr.sql());
        assertEquals("admin",  fromArr.params().get("p0"));
        assertEquals("writer", fromArr.params().get("p1"));

        PredicateCompiler.Compiled fromCsv = PredicateCompiler.compile(
                List.of(c("data.role", "in", "admin, writer ,reader")));
        assertEquals("(data->>'role' IN (:p0, :p1, :p2))", fromCsv.sql());
        assertEquals("reader", fromCsv.params().get("p2"));
    }

    @Test
    void numericComparisonsCastJsonbTextToNumeric() {
        PredicateCompiler.Compiled out = PredicateCompiler.compile(
                List.of(c("data.score", "gte", 80)));
        assertEquals("((data->>'score')::numeric >= :p0)", out.sql());
        assertEquals(80.0, out.params().get("p0"));
    }

    @Test
    void betweenEmitsTwoBoundsAndTwoParams() {
        PredicateCompiler.Compiled out = PredicateCompiler.compile(
                List.of(c("data.score", "between", 10, 100, "AND")));
        assertEquals("((data->>'score')::numeric BETWEEN :p0 AND :p1)", out.sql());
        assertEquals(10.0,  out.params().get("p0"));
        assertEquals(100.0, out.params().get("p1"));
    }

    @Test
    void dateComparisonsCastToTimestamptz() {
        PredicateCompiler.Compiled out = PredicateCompiler.compile(
                List.of(c("data.createdAt", "after", "2026-01-01T00:00:00Z")));
        assertEquals("((data->>'createdAt')::timestamptz > :p0::timestamptz)", out.sql());
    }

    @Test
    void isNullChecksBothSqlNullAndJsonNullLiteral() {
        PredicateCompiler.Compiled out = PredicateCompiler.compile(
                List.of(c("data.deletedAt", "isNull", null)));
        assertEquals("((data->'deletedAt' IS NULL OR jsonb_typeof(data->'deletedAt') = 'null'))",
                out.sql());
    }

    @Test
    void isNotNullCheckIsTheStrictNegation() {
        PredicateCompiler.Compiled out = PredicateCompiler.compile(
                List.of(c("data.deletedAt", "isNotNull", null)));
        assertEquals("((data->'deletedAt' IS NOT NULL AND jsonb_typeof(data->'deletedAt') <> 'null'))",
                out.sql());
    }

    @Test
    void multipleConditionsAreJoinedByTheirIndividualConnectors() {
        PredicateCompiler.Compiled out = PredicateCompiler.compile(List.of(
                c("data.email", "endsWith", "@acme.io"),
                c("data.role",  "in",       List.of("admin", "writer"), null, "AND"),
                c("data.score", "gte",      80, null, "OR")));
        assertTrue(out.sql().contains(" AND "), "second connector missing: " + out.sql());
        assertTrue(out.sql().contains(" OR "),  "third connector missing: "  + out.sql());
        assertEquals(4, out.params().size(), "expect 4 placeholders (1 + 2 in + 1 gte)");
    }

    @Test
    void invalidConnectorIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                PredicateCompiler.compile(List.of(
                        c("data.a", "equals", 1),
                        c("data.b", "equals", 2, null, "XOR"))));
        assertTrue(ex.getMessage().toLowerCase().contains("connector"));
    }

    @Test
    void illegalFieldNameIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                PredicateCompiler.compile(List.of(c("data.email; DROP TABLE", "equals", "x"))));
        assertTrue(ex.getMessage().contains("Illegal field name"));
    }

    @Test
    void unsupportedOpIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                PredicateCompiler.compile(List.of(c("data.email", "soundsLike", "x"))));
        assertTrue(ex.getMessage().contains("Unsupported op"));
    }

    @Test
    void emptyFieldIsSilentlyDroppedAsTrue() {
        PredicateCompiler.Compiled out = PredicateCompiler.compile(
                List.of(c("",      "equals", "x"),
                        c("data.a", "equals", 1, null, "AND")));
        assertEquals("TRUE AND (data->>'a' = :p0)", out.sql());
    }

    @Test
    void noUserStringIsConcatenatedIntoSql() {
        // Even with a value containing weird characters, the SQL only references :p0
        PredicateCompiler.Compiled out = PredicateCompiler.compile(
                List.of(c("data.a", "equals", "'; DROP TABLE virtual_table_entry; --")));
        assertFalse(out.sql().contains("DROP"), "user input leaked into SQL: " + out.sql());
        assertEquals("'; DROP TABLE virtual_table_entry; --", out.params().get("p0"));
    }
}
