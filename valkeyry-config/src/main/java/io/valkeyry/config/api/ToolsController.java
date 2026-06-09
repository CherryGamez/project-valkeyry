package io.valkeyry.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.camunda.bpm.model.dmn.Dmn;
import org.camunda.bpm.model.dmn.DmnModelInstance;
import org.camunda.bpm.model.dmn.instance.Decision;
import org.camunda.bpm.model.dmn.instance.DecisionTable;
import org.camunda.bpm.model.dmn.instance.Definitions;
import org.camunda.bpm.model.dmn.instance.Input;
import org.camunda.bpm.model.dmn.instance.InputEntry;
import org.camunda.bpm.model.dmn.instance.InputExpression;
import org.camunda.bpm.model.dmn.instance.Output;
import org.camunda.bpm.model.dmn.instance.OutputEntry;
import org.camunda.bpm.model.dmn.instance.Rule;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side file-to-JSON converter — the "Tools" tab in the HTMX UI.
 *
 * <p>The returned shape is always:</p>
 * <pre>{@code
 * {
 *   "source":   "xlsx" | "csv" | "dmn",
 *   "fileName": "<original filename>",
 *   "tables": [
 *     { "tableName": "...", "columns": ["..."], "rows": [ { col: val, ... } ] }
 *   ]
 * }
 * }</pre>
 *
 * <p>Each {@code tables[].rows[*]} object is ready to be POSTed as the {@code data} field of an
 * {@code IngestRecordRequest}; the {@code recordKey} still has to be chosen by the caller
 * (typically the first column, but the UI lets the user pick).</p>
 */
@RestController
@RequestMapping("/api/v1/tools")
@Tag(name = "Tools", description = "File → JSON converters (XLSX, CSV, DMN) for table imports.")
public class ToolsController {

    private static final Logger log = LoggerFactory.getLogger(ToolsController.class);

    private final ObjectMapper mapper;

    public ToolsController(ObjectMapper mapper) { this.mapper = mapper; }

    @PostMapping(path = "/convert/xlsx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Convert an Excel workbook (.xlsx) into JSON")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Returns one entry in {@code tables[]} per sheet.")})
    public Mono<JsonNode> convertXlsx(@RequestPart("file") FilePart file) {
        return readAllBytes(file).flatMap(bytes -> Mono.fromCallable(() -> xlsxToJson(file.filename(), bytes)));
    }

    @PostMapping(path = "/convert/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Convert a CSV file into JSON")
    public Mono<JsonNode> convertCsv(@RequestPart("file") FilePart file) {
        return readAllBytes(file).flatMap(bytes -> Mono.fromCallable(() -> csvToJson(file.filename(), bytes)));
    }

    @PostMapping(path = "/convert/dmn", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Convert a Camunda DMN file into JSON (decision tables)",
               description = "Each DMN decision table becomes one entry in tables[]; rule rows become entries.")
    public Mono<JsonNode> convertDmn(@RequestPart("file") FilePart file) {
        return readAllBytes(file).flatMap(bytes -> Mono.fromCallable(() -> dmnToJson(file.filename(), bytes)));
    }

    private Mono<byte[]> readAllBytes(FilePart file) {
        return DataBufferUtils.join(file.content())
                .map(buf -> {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                    return bytes;
                });
    }

    // ----------------- XLSX -----------------

    private JsonNode xlsxToJson(String filename, byte[] bytes) {
        ObjectNode root = mapper.createObjectNode();
        root.put("source", "xlsx");
        root.put("fileName", filename);
        ArrayNode tables = root.putArray("tables");
        DataFormatter formatter = new DataFormatter();
        String currentSheet = null;
        int currentRowNumber = -1;
        try (InputStream in = new ByteArrayInputStream(bytes);
             Workbook wb = new XSSFWorkbook(in)) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                currentSheet = sheet.getSheetName();
                if (sheet.getPhysicalNumberOfRows() == 0) continue;
                ObjectNode table = tables.addObject();
                table.put("tableName", sheet.getSheetName());

                Row headerRow = sheet.getRow(sheet.getFirstRowNum());
                List<String> columns = new ArrayList<>();
                ArrayNode cols = table.putArray("columns");
                if (headerRow != null) {
                    for (Cell c : headerRow) {
                        String name = formatter.formatCellValue(c).trim();
                        if (name.isEmpty()) name = "col_" + (c.getColumnIndex() + 1);
                        columns.add(name);
                        cols.add(name);
                    }
                }
                ArrayNode rows = table.putArray("rows");
                for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                    currentRowNumber = r + 1;       // 1-based for the user
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    ObjectNode rowJson = rows.addObject();
                    boolean nonEmpty = false;
                    for (int c = 0; c < columns.size(); c++) {
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        if (cell == null) { rowJson.putNull(columns.get(c)); continue; }
                        try {
                            switch (cell.getCellType()) {
                                case NUMERIC -> {
                                    double d = cell.getNumericCellValue();
                                    if (d == Math.floor(d) && !Double.isInfinite(d)) {
                                        rowJson.put(columns.get(c), (long) d);
                                    } else {
                                        rowJson.put(columns.get(c), d);
                                    }
                                    nonEmpty = true;
                                }
                                case BOOLEAN -> { rowJson.put(columns.get(c), cell.getBooleanCellValue()); nonEmpty = true; }
                                case BLANK   -> rowJson.putNull(columns.get(c));
                                default -> {
                                    String v = formatter.formatCellValue(cell);
                                    rowJson.put(columns.get(c), v);
                                    if (!v.isEmpty()) nonEmpty = true;
                                }
                            }
                        } catch (RuntimeException cellEx) {
                            // Surface cell-level errors with the exact cell reference (sheet!A1)
                            String addr = cell.getAddress().formatAsString();
                            log.warn("XLSX parse error at {}!{} (column='{}'): {}",
                                    sheet.getSheetName(), addr, columns.get(c), cellEx.getMessage());
                            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                                    "Failed to parse XLSX cell " + sheet.getSheetName() + "!" + addr
                                            + " (column '" + columns.get(c) + "'): " + cellEx.getMessage(),
                                    cellEx);
                        }
                    }
                    if (!nonEmpty) rows.remove(rows.size() - 1);
                }
                currentRowNumber = -1;
            }
        } catch (IOException e) {
            log.warn("Failed to read XLSX '{}': {}", filename, e.getMessage());
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Failed to read XLSX '" + filename + "': " + e.getMessage(), e);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            String loc = currentSheet != null
                    ? " (last sheet='" + currentSheet
                        + (currentRowNumber > 0 ? "', row=" + currentRowNumber : "'")
                        + ")"
                    : "";
            log.warn("XLSX parse error in '{}' {}: {}", filename, loc, e.getMessage(), e);
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Failed to parse XLSX '" + filename + "'" + loc + ": " + e.getMessage(), e);
        }
        return root;
    }

    // ----------------- CSV -----------------

    private JsonNode csvToJson(String filename, byte[] bytes) {
        ObjectNode root = mapper.createObjectNode();
        root.put("source", "csv");
        root.put("fileName", filename);
        ArrayNode tables = root.putArray("tables");
        ObjectNode table = tables.addObject();
        table.put("tableName", stripExtension(filename));
        ArrayNode cols = table.putArray("columns");
        ArrayNode rows = table.putArray("rows");

        int rowNumber = 1;          // header is line 1
        try (CSVReader reader = new CSVReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String[] header = reader.readNext();
            if (header == null) return root;
            List<String> columns = new ArrayList<>();
            for (int i = 0; i < header.length; i++) {
                String h = header[i] == null ? "" : header[i].trim();
                if (h.isEmpty()) h = "col_" + (i + 1);
                columns.add(h);
                cols.add(h);
            }
            String[] line;
            while ((line = reader.readNext()) != null) {
                rowNumber++;
                ObjectNode row = rows.addObject();
                for (int i = 0; i < columns.size(); i++) {
                    String v = i < line.length ? line[i] : null;
                    if (v == null || v.isEmpty()) row.putNull(columns.get(i));
                    else row.put(columns.get(i), v);
                }
            }
        } catch (CsvValidationException e) {
            log.warn("CSV validation error in '{}' at line {}: {}", filename, rowNumber, e.getMessage());
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Failed to parse CSV '" + filename + "' at line " + rowNumber
                            + " (a quoted field is malformed or the line has an unexpected number of columns): "
                            + e.getMessage(), e);
        } catch (IOException e) {
            log.warn("Failed to read CSV '{}' at line {}: {}", filename, rowNumber, e.getMessage());
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Failed to read CSV '" + filename + "' at line " + rowNumber + ": " + e.getMessage(), e);
        }
        return root;
    }

    // ----------------- DMN -----------------

    private JsonNode dmnToJson(String filename, byte[] bytes) {
        ObjectNode root = mapper.createObjectNode();
        root.put("source", "dmn");
        root.put("fileName", filename);
        ArrayNode tables = root.putArray("tables");
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            DmnModelInstance model = Dmn.readModelFromStream(in);
            Definitions defs = model.getDefinitions();
            Collection<Decision> decisions = defs.getChildElementsByType(Decision.class);
            for (Decision decision : decisions) {
                Collection<DecisionTable> dts = decision.getChildElementsByType(DecisionTable.class);
                for (DecisionTable dt : dts) {
                    ObjectNode tbl = tables.addObject();
                    String name = decision.getName() != null ? decision.getName() : decision.getId();
                    tbl.put("tableName", name);
                    tbl.put("decisionId", decision.getId());
                    tbl.put("hitPolicy", dt.getHitPolicy() == null ? "UNIQUE" : dt.getHitPolicy().name());

                    Collection<Input> inputs = dt.getInputs();
                    Collection<Output> outputs = dt.getOutputs();
                    ArrayNode cols = tbl.putArray("columns");
                    List<String> inputNames = new ArrayList<>();
                    List<String> outputNames = new ArrayList<>();
                    for (Input in1 : inputs) {
                        InputExpression expr = in1.getInputExpression();
                        String label = in1.getLabel() != null ? in1.getLabel()
                                : (expr != null && expr.getTextContent() != null ? expr.getTextContent() : in1.getId());
                        inputNames.add(label);
                        cols.add("in:" + label);
                    }
                    for (Output o : outputs) {
                        String label = o.getLabel() != null ? o.getLabel()
                                : (o.getName() != null ? o.getName() : o.getId());
                        outputNames.add(label);
                        cols.add("out:" + label);
                    }

                    ArrayNode rows = tbl.putArray("rows");
                    for (Rule rule : dt.getRules()) {
                        ObjectNode row = rows.addObject();
                        row.put("_ruleId", rule.getId());
                        List<InputEntry> ies = new ArrayList<>(rule.getInputEntries());
                        for (int i = 0; i < ies.size() && i < inputNames.size(); i++) {
                            row.put("in:" + inputNames.get(i), ies.get(i).getTextContent());
                        }
                        List<OutputEntry> oes = new ArrayList<>(rule.getOutputEntries());
                        for (int i = 0; i < oes.size() && i < outputNames.size(); i++) {
                            row.put("out:" + outputNames.get(i), oes.get(i).getTextContent());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Failed to read DMN: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Failed to parse DMN: " + e.getMessage(), e);
        }
        return root;
    }

    private static String stripExtension(String filename) {
        if (filename == null) return "table";
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String base = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> ensureMap(Map<String, Object> existing) {
        return existing == null ? new LinkedHashMap<>() : existing;
    }
}
