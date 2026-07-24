package com.sim.spriced.pahal.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;

@RestController
@RequestMapping("/api/database")
@CrossOrigin(origins = {"http://localhost:5500", "http://127.0.0.1:5500", "http://localhost:3000", "http://localhost:8080"})
public class DatabaseMetadataController {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMetadataController.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * GET /api/database/tables
     * Fetch all tables from the database with their column metadata
     */
    @GetMapping("/tables")
    public ResponseEntity<Map<String, Object>> getAllTables(
            @RequestParam(required = false, defaultValue = "") String search) {

        log.info("Fetching database tables, search='{}'", search);
        Map<String, Object> response = new LinkedHashMap<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();

            log.debug("Catalog: {}, Schema: {}", catalog, schema);

            List<Map<String, Object>> tables = new ArrayList<>();

            // Get all tables
            ResultSet tableRs = metaData.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"});

            while (tableRs.next()) {
                String tableName = tableRs.getString("TABLE_NAME");
                String tableType = tableRs.getString("TABLE_TYPE");
                String remarks = tableRs.getString("REMARKS");

                // Apply search filter if provided
                if (!search.isEmpty() && !tableName.toLowerCase().contains(search.toLowerCase())) {
                    continue;
                }

                Map<String, Object> tableInfo = new LinkedHashMap<>();
                tableInfo.put("name", tableName);
                tableInfo.put("type", tableType);
                tableInfo.put("remarks", remarks != null ? remarks : "");
                tableInfo.put("displayName", formatDisplayName(tableName));

                // Get primary keys
                List<String> primaryKeys = new ArrayList<>();
                ResultSet pkRs = metaData.getPrimaryKeys(catalog, schema, tableName);
                while (pkRs.next()) {
                    primaryKeys.add(pkRs.getString("COLUMN_NAME"));
                }
                pkRs.close();
                tableInfo.put("primaryKeys", primaryKeys);

                // Get columns for this table
                List<Map<String, Object>> columns = new ArrayList<>();
                ResultSet columnRs = metaData.getColumns(catalog, schema, tableName, "%");

                while (columnRs.next()) {
                    String columnName = columnRs.getString("COLUMN_NAME");
                    String dataType = columnRs.getString("TYPE_NAME");
                    int columnSize = columnRs.getInt("COLUMN_SIZE");
                    int decimalDigits = columnRs.getInt("DECIMAL_DIGITS");
                    String isNullable = columnRs.getString("IS_NULLABLE");
                    String columnDef = columnRs.getString("COLUMN_DEF");
                    String remarks_col = columnRs.getString("REMARKS");
                    int ordinalPosition = columnRs.getInt("ORDINAL_POSITION");

                    Map<String, Object> columnInfo = new LinkedHashMap<>();
                    columnInfo.put("name", columnName);
                    columnInfo.put("type", formatDataType(dataType, columnSize, decimalDigits));
                    columnInfo.put("rawType", dataType);
                    columnInfo.put("size", columnSize);
                    columnInfo.put("decimalDigits", decimalDigits);
                    columnInfo.put("nullable", "YES".equalsIgnoreCase(isNullable));
                    columnInfo.put("defaultValue", columnDef != null ? columnDef : "");
                    columnInfo.put("remarks", remarks_col != null ? remarks_col : "");
                    columnInfo.put("position", ordinalPosition);
                    columnInfo.put("isPrimaryKey", primaryKeys.contains(columnName));

                    columns.add(columnInfo);
                }
                columnRs.close();

                // Sort columns by ordinal position
                columns.sort(Comparator.comparingInt(c -> (int) c.get("position")));

                tableInfo.put("columns", columns);
                tableInfo.put("columnCount", columns.size());
                tableInfo.put("businessKeys", primaryKeys);

                tables.add(tableInfo);
            }
            tableRs.close();

            // Sort tables alphabetically
            tables.sort(Comparator.comparing(t -> (String) t.get("name")));

            response.put("success", true);
            response.put("tables", tables);
            response.put("count", tables.size());
            response.put("catalog", catalog);
            response.put("schema", schema);

            log.info("Found {} tables", tables.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to fetch database tables", e);
            response.put("success", false);
            response.put("message", "Failed to fetch tables: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * GET /api/database/tables/{tableName}
     * Fetch details for a specific table
     */
    @GetMapping("/tables/{tableName}")
    public ResponseEntity<Map<String, Object>> getTableDetails(@PathVariable String tableName) {
        log.info("Fetching details for table: {}", tableName);
        Map<String, Object> response = new LinkedHashMap<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();

            Map<String, Object> tableInfo = new LinkedHashMap<>();
            tableInfo.put("name", tableName);
            tableInfo.put("displayName", formatDisplayName(tableName));

            // Get primary keys
            List<String> primaryKeys = new ArrayList<>();
            ResultSet pkRs = metaData.getPrimaryKeys(catalog, schema, tableName);
            while (pkRs.next()) {
                primaryKeys.add(pkRs.getString("COLUMN_NAME"));
            }
            pkRs.close();
            tableInfo.put("primaryKeys", primaryKeys);
            tableInfo.put("businessKeys", primaryKeys);

            // Get columns
            List<Map<String, Object>> columns = new ArrayList<>();
            ResultSet columnRs = metaData.getColumns(catalog, schema, tableName, "%");

            while (columnRs.next()) {
                String columnName = columnRs.getString("COLUMN_NAME");
                String dataType = columnRs.getString("TYPE_NAME");
                int columnSize = columnRs.getInt("COLUMN_SIZE");
                int decimalDigits = columnRs.getInt("DECIMAL_DIGITS");
                String isNullable = columnRs.getString("IS_NULLABLE");
                int ordinalPosition = columnRs.getInt("ORDINAL_POSITION");

                Map<String, Object> columnInfo = new LinkedHashMap<>();
                columnInfo.put("name", columnName);
                columnInfo.put("type", formatDataType(dataType, columnSize, decimalDigits));
                columnInfo.put("rawType", dataType);
                columnInfo.put("size", columnSize);
                columnInfo.put("nullable", "YES".equalsIgnoreCase(isNullable));
                columnInfo.put("position", ordinalPosition);
                columnInfo.put("isPrimaryKey", primaryKeys.contains(columnName));

                columns.add(columnInfo);
            }
            columnRs.close();

            columns.sort(Comparator.comparingInt(c -> (int) c.get("position")));
            tableInfo.put("columns", columns);
            tableInfo.put("columnCount", columns.size());

            response.put("success", true);
            response.put("table", tableInfo);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to fetch table details for: {}", tableName, e);
            response.put("success", false);
            response.put("message", "Failed to fetch table: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * GET /api/database/search
     * Search tables and columns
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchDatabase(
            @RequestParam String query) {
        log.info("Searching database for: {}", query);
        Map<String, Object> response = new LinkedHashMap<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();

            List<Map<String, Object>> results = new ArrayList<>();
            String searchPattern = "%" + query.toLowerCase() + "%";

            ResultSet tableRs = metaData.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"});

            while (tableRs.next()) {
                String tableName = tableRs.getString("TABLE_NAME");
                if (!tableName.toLowerCase().contains(query.toLowerCase())) continue;

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("name", tableName);
                result.put("displayName", formatDisplayName(tableName));
                result.put("type", "TABLE");

                // Get column count quickly
                ResultSet colRs = metaData.getColumns(catalog, schema, tableName, "%");
                int colCount = 0;
                while (colRs.next()) colCount++;
                colRs.close();
                result.put("columnCount", colCount);

                results.add(result);
            }
            tableRs.close();

            response.put("success", true);
            response.put("results", results);
            response.put("count", results.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Search failed", e);
            response.put("success", false);
            response.put("message", "Search failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * GET /api/database/sample-data/{tableName}
     * Get sample data from a table (first 5 rows)
     */
    @GetMapping("/sample-data/{tableName}")
    public ResponseEntity<Map<String, Object>> getSampleData(@PathVariable String tableName) {
        log.info("Fetching sample data for: {}", tableName);
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            // Security: validate table name exists
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                ResultSet tableRs = metaData.getTables(connection.getCatalog(), connection.getSchema(), tableName, new String[]{"TABLE", "VIEW"});
                if (!tableRs.next()) {
                    response.put("success", false);
                    response.put("message", "Table not found: " + tableName);
                    return ResponseEntity.badRequest().body(response);
                }
                tableRs.close();
            }

            // Fetch sample data
            String sql = String.format("SELECT * FROM %s LIMIT 5", tableName);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

            response.put("success", true);
            response.put("tableName", tableName);
            response.put("rows", rows);
            response.put("count", rows.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to fetch sample data for: {}", tableName, e);
            response.put("success", false);
            response.put("message", "Failed to fetch data: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Format data type for display (e.g., "VARCHAR" + size → "string(50)")
     */
    private String formatDataType(String typeName, int columnSize, int decimalDigits) {
        String upper = typeName.toUpperCase();

        switch (upper) {
            case "VARCHAR":
            case "CHAR":
            case "NVARCHAR":
            case "NCHAR":
            case "TEXT":
            case "CLOB":
                return "string(" + columnSize + ")";
            case "INTEGER":
            case "INT":
            case "SMALLINT":
            case "TINYINT":
                return "integer";
            case "BIGINT":
                return "long";
            case "DECIMAL":
            case "NUMERIC":
            case "NUMBER":
                return decimalDigits > 0 ? "decimal(" + columnSize + "," + decimalDigits + ")" : "numeric(" + columnSize + ")";
            case "FLOAT":
            case "DOUBLE":
            case "REAL":
                return "decimal";
            case "DATE":
                return "date";
            case "TIMESTAMP":
            case "DATETIME":
            case "DATETIME2":
                return "datetime";
            case "TIME":
                return "time";
            case "BOOLEAN":
            case "BIT":
                return "boolean";
            case "BLOB":
            case "BINARY":
            case "VARBINARY":
                return "binary";
            case "JSON":
            case "JSONB":
                return "json";
            default:
                return upper.toLowerCase() + (columnSize > 0 ? "(" + columnSize + ")" : "");
        }
    }

    /**
     * Format table name for display (e.g., "customer_master" → "Customer Master")
     */
    private String formatDisplayName(String tableName) {
        if (tableName == null) return "";
        return Arrays.stream(tableName.split("_"))
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
                .reduce((a, b) -> a + " " + b)
                .orElse(tableName);
    }
}
