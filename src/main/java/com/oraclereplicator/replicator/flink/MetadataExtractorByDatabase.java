package com.oraclereplicator.replicator.flink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraclereplicator.replicator.properties.SourceDbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.types.Row;
import org.apache.flink.util.Collector;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@RequiredArgsConstructor
@Slf4j
public class MetadataExtractorByDatabase implements FlatMapFunction<SourceDbProperties, Row> {


    private static final int ROW_FIELD_COUNT = 4;
    private static final int IDX_SOURCE_NAME = 0;
    private static final int IDX_TABLE_NAME = 1;
    private static final int IDX_DATA_JSON = 2;
    private static final int IDX_TIMESTAMP = 3;

    private final List<String> tablesToExtract;
    private final int maxRetries;
    private final long retryDelayMs;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void flatMap(SourceDbProperties source, Collector<Row> collector) throws Exception {
        int attempt = 0;
        while (attempt < maxRetries) {
            try (Connection conn = DriverManager.getConnection(source.getUrl(), source.getUsername(), source.getPassword())) {
                for (String tableName : tablesToExtract) {
                    extractTableData(conn, source, tableName, collector);
                }
                return;
            } catch (SQLException e) {
                attempt++;
                log.warn("Attempt {}/{} failed to connect to {}: {}", attempt, maxRetries, source.getName(), e.getMessage());
                Thread.sleep(retryDelayMs);
            }
        }
        log.error("All connection attempts to {} failed", source.getName());
    }

    private void extractTableData(Connection conn, SourceDbProperties source, String tableName, Collector<Row> collector) {
        String sql = "SELECT * FROM " + tableName;
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            while (rs.next()) {
                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = meta.getColumnName(i);
                    Object value = rs.getObject(i);
                    value = convertJdbcValue(value);
                    rowMap.put(columnName, value);
                }

                Row flinkRow = new Row(ROW_FIELD_COUNT);
                flinkRow.setField(IDX_SOURCE_NAME, source.getName());
                flinkRow.setField(IDX_TABLE_NAME, tableName);
                flinkRow.setField(IDX_DATA_JSON, objectMapper.writeValueAsString(rowMap));
                flinkRow.setField(IDX_TIMESTAMP, Timestamp.valueOf(LocalDateTime.now()));

                collector.collect(flinkRow);
            }

        } catch (Exception e) {
            log.warn("Failed to extract data from {}: {}", tableName, e.getMessage(), e);
        }
    }

    private Object convertJdbcValue(Object value) {
        try {
            if (value instanceof Array array) {
                Object[] arr = (Object[]) array.getArray();
                return Arrays.asList(arr);
            } else if (value instanceof Clob clob) {
                return clob.getSubString(1, (int) clob.length());
            } else if (value instanceof Blob blob) {
                return "[BLOB " + blob.length() + " bytes]";
            } else if (value instanceof Struct struct) {
                return struct.toString();
            } else if (value != null && value.getClass().getName().startsWith("oracle.")) {
                return value.toString(); // Fallback для специфических Oracle-типов
            }
        } catch (SQLException e) {
            log.warn("Failed to convert JDBC value: {}", e.getMessage());
            return "[UNREADABLE]";
        }
        return value;
    }
}
