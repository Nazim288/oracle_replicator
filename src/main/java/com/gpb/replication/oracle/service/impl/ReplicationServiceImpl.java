package com.gpb.replication.oracle.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gpb.replication.oracle.dto.SourceDbConnections;
import com.gpb.replication.oracle.log.SvoiCustomLogger;
import com.gpb.replication.oracle.log.SvoiSeverityEnum;
import com.gpb.replication.oracle.model.DatabaseMetadata;
import com.gpb.replication.oracle.model.EntityId;
import com.gpb.replication.oracle.model.SchemaMetadata;
import com.gpb.replication.oracle.model.TableMetadata;
import com.gpb.replication.oracle.properties.SqlTemplates;
import com.gpb.replication.oracle.repository.DatabaseMetadataRepository;
import com.gpb.replication.oracle.repository.SchemaMetadataRepository;
import com.gpb.replication.oracle.repository.TableMetadataRepository;
import com.gpb.replication.oracle.service.DbSourcesService;
import com.gpb.replication.oracle.service.ReplicationService;
import com.gpb.replication.oracle.service.VaultSecretService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.postgresql.util.PGobject;

import java.math.BigInteger;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReplicationServiceImpl implements ReplicationService {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int BATCH_SIZE = 1000;

    private static final String INSERT_SCHEMA_SQL = """
            INSERT INTO oracle_metadata.schema_metadata
                (
                    id,
                    parent_fqn,
                    fqn,
                    service_name,
                    db_name,
                    name,
                    created_at,
                    hash_data
                )
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_TABLE_SQL = """
            INSERT INTO oracle_metadata.table_metadata
                (
                    id,
                    parent_fqn,
                    fqn,
                    service_name,
                    db_name,
                    schema_name,
                    name,
                    description,
                    data,
                    hash_data,
                    created_at
                )
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate mainJdbcTemplate;

    private final DbSourcesService dbSourcesService;
    private final SvoiCustomLogger svoiCustomLogger;
    private final DatabaseMetadataRepository databaseRep;
    private final SchemaMetadataRepository schemaRep;
    private final TableMetadataRepository tableRep;
    private final SqlTemplates sqlTemplates;
    private final VaultSecretService vault;

    @Async
    public void startReplicationAsync(String serviceName) {
        startReplication(serviceName);
    }

    @Override
    public void startReplication(String serviceName) {
        SourceDbConnections source;

        if (vault.isVaultConnected() && vault.serviceSecretsExist(serviceName)) {
            source = vault.getServiceSecrets(serviceName);
        } else {
            source = dbSourcesService.getDbConnections()
                    .stream()
                    .filter(s -> s.getName().equals(serviceName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Не найден сервис: " + serviceName));
        }
        truncateTables(serviceName);

        try {
            svoiCustomLogger.logConnectToSource(
                    source.getHostFromUrl(),
                    source.getPortFromUrl(),
                    source.getDbType(),
                    source.getUsername()
            );

            int totalTables = 0;
            int totalSchemas = 0;
            int totalDatabases = 0;
            for (String url : source.getUrl()) {
                
                try (Connection conn = DriverManager.getConnection(url, source.getUsername(), source.getPassword())) {
                    conn.setReadOnly(true);

                    List<String> databases = databaseReplicationOracle(conn, serviceName);
                    totalDatabases += databases.size();
                    for (String dbName : databases) {
                        List<String> schemas = schemaReplicationOracle(conn, serviceName, dbName);
                        totalSchemas += schemas.size();
                        for (String schema : schemas) {
                            int cnt = tableReplicationOracle(conn, serviceName, dbName, schema);
                            totalTables += cnt;
                        }
                    }
                }
            }

            log.info("Репликация Oracle завершена: {}. Получено {} БД, {} схем, {} таблиц", 
                    serviceName, totalDatabases, totalSchemas, totalTables);

            svoiCustomLogger.sendInternal(
                    "replicationJob",
                    "Replication Finished",
                    String.format("Replication Oracle source [%s] finished", serviceName),
                    SvoiSeverityEnum.ONE
            );

        } catch (Exception e) {
            svoiCustomLogger.logDbConnectionError(
                    source.getHostFromUrl(),
                    source.getPortFromUrl(),
                    source.getDbType(),
                    source.getUsername(),
                    e
            );
            log.error("Ошибка при подключении к источнику {}", source.getName(), e);
            throw new RuntimeException("Ошибка при подключении к источнику: " + source.getName(), e);
        }
    }

    private void truncateTables(String serviceName) {
        svoiCustomLogger.sendInternal(
                "replicationDataReset",
                "replication Data Reset",
                "serviceName=" + serviceName,
                SvoiSeverityEnum.ONE
        );

        databaseRep.deleteByServiceName(serviceName);
        schemaRep.deleteByServiceName(serviceName);
        tableRep.deleteByServiceName(serviceName);
        log.info("Truncated metadata tables for service={}", serviceName);
    }

    /**
     *Получаем список DB (уровень database_metadata)
     */
    private List<String> databaseReplicationOracle(Connection conn, String serviceName) throws SQLException {
        List<String> databases = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        try (
                PreparedStatement stmt = conn.prepareStatement(sqlTemplates.getDatabaseSql());
                ResultSet rs = stmt.executeQuery()
        ) {
            List<DatabaseMetadata> entities = new ArrayList<>();

            while (rs.next()) {
                String dbName = rs.getString("db_name");
                long oid = rs.getLong("oid");
                String fqn = serviceName + "." + dbName;

                DatabaseMetadata db = new DatabaseMetadata();
                db.setId(new EntityId(oid, serviceName));
                db.setFqn(fqn);
                db.setName(dbName);
                db.setServiceName(serviceName);
                db.setCreatedAt(now);
                db.setHashData(DigestUtils.md5Hex(fqn));

                databases.add(dbName);
                entities.add(db);
            }

            databaseRep.saveAll(entities);
            log.info("Реплицировано {} DB Oracle для {}", databases.size(), serviceName);
        } catch (SQLException e) {
            log.error("Ошибка при подключении и получении DB из Oracle для {}: {}", serviceName, e.getMessage(), e);
            throw e;
        }

        return databases;
    }

    /**
     *Получаем схемы внутри DB
     */
    private List<String> schemaReplicationOracle(
            Connection conn,
            String serviceName,
            String dbName
    ) throws SQLException {

        List<String> schemas = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        try (PreparedStatement stmt = conn.prepareStatement(sqlTemplates.getSchemaSql())) {
            stmt.setFetchSize(BATCH_SIZE);

            try (ResultSet rs = stmt.executeQuery()) {
                List<SchemaMetadata> batch = new ArrayList<>(BATCH_SIZE);
                int total = 0;

                while (rs.next()) {
                    SchemaMetadata schema = mapSchema(rs, serviceName, dbName, now);

                    schemas.add(schema.getName());
                    batch.add(schema);

                    if (batch.size() >= BATCH_SIZE) {
                        insertSchemasBatch(batch);
                        total += batch.size();
                        batch.clear();
                    }
                }

                if (!batch.isEmpty()) {
                    insertSchemasBatch(batch);
                    total += batch.size();
                    batch.clear();
                }

                log.info("Реплицировано {} схем Oracle для DB {}", total, dbName);
            }

        } catch (SQLException e) {
            log.error(
                    "Ошибка при подключении и получении схем Oracle для {}: {}",
                    serviceName + ',' + dbName,
                    e.getMessage(),
                    e
            );
            throw e;
        }

        return schemas;
    }

    /**
     *Получаем таблицы внутри схемы DB
     */
    private int tableReplicationOracle(
            Connection conn,
            String serviceName,
            String dbName,
            String schemaName
    ) throws SQLException {

        LocalDateTime now = LocalDateTime.now();
        List<TableMetadata> batch = new ArrayList<>(BATCH_SIZE);
        int total = 0;

        try (PreparedStatement stmt = conn.prepareStatement(sqlTemplates.getTableSql())) {
            stmt.setFetchSize(BATCH_SIZE);

            stmt.setString(1, schemaName);
            stmt.setString(2, schemaName);
            stmt.setString(3, schemaName);

            try (ResultSet rs = stmt.executeQuery()) {
                
                while (rs.next()) {
                    TableMetadata table = mapTable(rs, serviceName, dbName, now);
                    batch.add(table);

                    if (batch.size() >= BATCH_SIZE) {
                        insertTablesBatch(batch);
                        total += batch.size();
                        batch.clear();
                    }
                }
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Ошибка обработки JSON для схемы " + schemaName, e);
        }

        if (!batch.isEmpty()) {
            insertTablesBatch(batch);
            total += batch.size();
            batch.clear();
        }

        return total;
    }

    private SchemaMetadata mapSchema(
            ResultSet rs,
            String serviceName,
            String dbName,
            LocalDateTime now
    ) throws SQLException {

        String schemaName = rs.getString("schema_name");
        long oid = rs.getLong("oid");

        String fqn = serviceName + "." + dbName + "." + schemaName;
        String parentFqn = serviceName + "." + dbName;

        SchemaMetadata schema = new SchemaMetadata();
        schema.setId(new EntityId(oid, parentFqn));
        schema.setFqn(fqn);
        schema.setServiceName(serviceName);
        schema.setDbName(dbName);
        schema.setName(schemaName);
        schema.setCreatedAt(now);
        schema.setHashData(DigestUtils.md5Hex(fqn));

        return schema;
    }

    private TableMetadata mapTable(
            ResultSet rs,
            String serviceName,
            String dbName,
            LocalDateTime now
    ) throws SQLException, JsonProcessingException {

        String schema = rs.getString("SCHEMA_NAME");
        String tableName = rs.getString("TABLE_NAME");
        String tableType = rs.getString("TABLE_TYPE");
        String viewDefinition = rs.getString("VIEW_DEFINITION");

        String fqn = String.join(".", serviceName, dbName, schema, tableName);
        String parentFqn = serviceName + "." + dbName + "." + schema;

        long oid = new BigInteger(DigestUtils.md5Hex(fqn).substring(0, 8), 16).longValue();

        String jsonColumns = rs.getString("COLUMNS_JSON");
        String jsonConstraints = rs.getString("TABLE_CONSTRAINTS_JSON");

        ObjectNode data = objectMapper.createObjectNode();
        data.put("tableType", tableType);
        data.put("viewDefinition", viewDefinition);
        data.set("columns", objectMapper.readTree(jsonColumns != null ? jsonColumns : "[]"));
        data.set("tableConstraints", objectMapper.readTree(jsonConstraints != null ? jsonConstraints : "[]"));

        TableMetadata table = new TableMetadata();
        table.setId(new EntityId(oid, parentFqn));
        table.setFqn(fqn);
        table.setDbName(dbName);
        table.setSchemaName(schema);
        table.setName(tableName);
        table.setServiceName(serviceName);
        table.setDescription(rs.getString("DESCRIPTION"));
        table.setCreatedAt(now);
        table.setData(data);
        table.setHashData(DigestUtils.md5Hex(fqn + jsonColumns + jsonConstraints));

        return table;
    }

    private void insertSchemasBatch(List<SchemaMetadata> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        mainJdbcTemplate.batchUpdate(INSERT_SCHEMA_SQL, batch, batch.size(), (ps, schema) -> {
            ps.setLong(1, schema.getId().getId());
            ps.setString(2, schema.getId().getParentFqn());
            ps.setString(3, schema.getFqn());
            ps.setString(4, schema.getServiceName());
            ps.setString(5, schema.getDbName());
            ps.setString(6, schema.getName());
            ps.setTimestamp(7, Timestamp.valueOf(schema.getCreatedAt()));
            ps.setString(8, schema.getHashData());
        });
    }

    private void insertTablesBatch(List<TableMetadata> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        mainJdbcTemplate.batchUpdate(INSERT_TABLE_SQL, batch, batch.size(), (ps, table) -> {
            ps.setLong(1, table.getId().getId());
            ps.setString(2, table.getId().getParentFqn());
            ps.setString(3, table.getFqn());
            ps.setString(4, table.getServiceName());
            ps.setString(5, table.getDbName());
            ps.setString(6, table.getSchemaName());
            ps.setString(7, table.getName());
            ps.setString(8, table.getDescription());
            ps.setObject(9, toJsonb(table.getData()));
            ps.setString(10, table.getHashData());
            ps.setTimestamp(11, Timestamp.valueOf(table.getCreatedAt()));
        });
    }

    private PGobject toJsonb(JsonNode jsonNode) throws SQLException {
        PGobject jsonObject = new PGobject();
        jsonObject.setType("jsonb");
        jsonObject.setValue(jsonNode == null ? "{}" : jsonNode.toString());
        return jsonObject;
    }
}
