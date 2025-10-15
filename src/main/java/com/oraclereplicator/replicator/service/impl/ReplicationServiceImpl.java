package com.oraclereplicator.replicator.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oraclereplicator.replicator.dto.SourceDbConnections;
import com.oraclereplicator.replicator.log.SvoiCustomLogger;
import com.oraclereplicator.replicator.log.SvoiSeverityEnum;
import com.oraclereplicator.replicator.model.DatabaseMetadata;
import com.oraclereplicator.replicator.model.EntityId;
import com.oraclereplicator.replicator.model.SchemaMetadata;
import com.oraclereplicator.replicator.model.TableMetadata;
import com.oraclereplicator.replicator.properties.SqlTemplates;
import com.oraclereplicator.replicator.repository.DatabaseMetadataRepository;
import com.oraclereplicator.replicator.repository.SchemaMetadataRepository;
import com.oraclereplicator.replicator.repository.TableMetadataRepository;
import com.oraclereplicator.replicator.service.DbSourcesService;
import com.oraclereplicator.replicator.service.ReplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReplicationServiceImpl implements ReplicationService {

    private final DbSourcesService dbSourcesService;
    private final SvoiCustomLogger svoiCustomLogger;
    private final DatabaseMetadataRepository databaseRep;
    private final SchemaMetadataRepository schemaRep;
    private final TableMetadataRepository tableRep;
    private final SqlTemplates sqlTemplates;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Async
    public void startReplicationAsync(String serviceName) {
        startReplication(serviceName);
    }

    @Override
    public void startReplication(String serviceName) {
        String jobId = UUID.randomUUID().toString();
        long startTime = System.nanoTime();
        log.info("Начало репликации Oracle для {} (job_id={})", serviceName, jobId);

        truncateTables(serviceName);

        SourceDbConnections source = dbSourcesService.getDbConnections()
                .stream()
                .filter(s -> s.getName().equals(serviceName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Не найден сервис: " + serviceName));

        int totalSchemas = 0;
        int totalTables = 0;

        try {
            svoiCustomLogger.logConnectToSource(
                    source.getHostFromUrl(),
                    source.getHostFromUrl(),
                    source.getPortFromUrl(),
                    source.getDbType()
            );

            List<String> databases = databaseReplicationOracle(source);

            for (String dbName : databases) {
                List<String> schemas = schemaReplicationOracle(source, dbName);
                totalSchemas += schemas.size();

                for (String schema : schemas) {
                    totalTables += tableReplicationOracle(source, dbName, schema);
                }
            }

            double durationSec = (System.nanoTime() - startTime) / 1_000_000_000.0;
            String summary = String.format(
                    "Replicated Oracle source [%s]: databases=%d, schemas=%d, tables=%d, duration=%.2fs",
                    serviceName, databases.size(), totalSchemas, totalTables, durationSec
            );

            log.info("Репликация Oracle завершена: {}", summary);

            svoiCustomLogger.send(
                    "replicationJob",
                    "Replication Finished",
                    summary,
                    SvoiSeverityEnum.ONE
            );

        } catch (SQLException e) {
            svoiCustomLogger.logAuthError(
                    source.getHostFromUrl(),
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
        databaseRep.deleteByServiceName(serviceName);
        schemaRep.deleteByServiceName(serviceName);
        tableRep.deleteByServiceName(serviceName);
        log.info("Метаданные очищены для {}", serviceName);
    }

    /**
     *Получаем список DB (уровень database_metadata)
     */
    private List<String> databaseReplicationOracle(SourceDbConnections source) throws SQLException {
        List<String> databases = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        try (Connection conn = DriverManager.getConnection(source.getUrl(), source.getUsername(), source.getPassword());
             PreparedStatement stmt = conn.prepareStatement(sqlTemplates.getDatabaseSql());
             ResultSet rs = stmt.executeQuery()) {

            List<DatabaseMetadata> entities = new ArrayList<>();

            while (rs.next()) {
                String dbName = rs.getString("db_name");
                long oid = rs.getLong("oid");
                String fqn = source.getServiceName() + "." + dbName;

                DatabaseMetadata db = new DatabaseMetadata();
                db.setId(new EntityId(oid, source.getServiceName()));
                db.setFqn(fqn);
                db.setName(dbName);
                db.setServiceName(source.getServiceName());
                db.setCreatedAt(now);
                db.setHashData(DigestUtils.md5Hex(fqn));

                databases.add(dbName);
                entities.add(db);
            }

            databaseRep.saveAll(entities);
            log.info("Реплицировано {} DB Oracle для {}", databases.size(), source.getServiceName());

        } catch (SQLException e) {
            log.error("Ошибка при подключении и получении DB из Oracle для {}: {}", source.getName(), e.getMessage(), e);
            throw e;
        }

        return databases;
    }

    /**
     *Получаем схемы внутри DB
     */
    private List<String> schemaReplicationOracle(SourceDbConnections source, String dbName) throws SQLException {
        List<String> schemas = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        try (Connection conn = DriverManager.getConnection(source.getUrl(), source.getUsername(), source.getPassword());
             PreparedStatement stmt = conn.prepareStatement(sqlTemplates.getSchemaSql());
             ResultSet rs = stmt.executeQuery()) {

            List<SchemaMetadata> entities = new ArrayList<>();

            while (rs.next()) {
                String schemaName = rs.getString("schema_name");
                long oid = rs.getLong("oid");
                String fqn = source.getServiceName() + "." + dbName + "." + schemaName;
                String parentFqn = source.getServiceName() + "." + dbName;

                SchemaMetadata schema = new SchemaMetadata();
                schema.setId(new EntityId(oid, parentFqn));
                schema.setFqn(fqn);
                schema.setServiceName(source.getServiceName());
                schema.setDbName(dbName);
                schema.setName(schemaName);
                schema.setCreatedAt(now);
                schema.setHashData(DigestUtils.md5Hex(fqn));

                schemas.add(schemaName);
                entities.add(schema);
            }

            schemaRep.saveAll(entities);
            log.info("Реплицировано {} схем Oracle для DB {}", schemas.size(), dbName);

        } catch (SQLException e) {
            log.error("Ошибка при подключении и получении схем Oracle для {}: {}", dbName, e.getMessage(), e);
            throw e;
        }

        return schemas;
    }

    /**
     *Получаем таблицы внутри схемы DB
     */
    private int tableReplicationOracle(SourceDbConnections source, String dbName, String schemaName) throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        List<TableMetadata> entities = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(source.getUrl(), source.getUsername(), source.getPassword());
             PreparedStatement stmt = conn.prepareStatement(sqlTemplates.getTableSql())) {

            stmt.setString(1, schemaName);
            stmt.setString(2, schemaName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    try {
                        String schema = rs.getString("SCHEMA_NAME");
                        String tableName = rs.getString("TABLE_NAME");
                        String tableType = rs.getString("TABLE_TYPE");
                        String viewDefinition = rs.getString("VIEW_DEFINITION");

                        String fqn = String.join(".", source.getServiceName(), dbName, schema, tableName);
                        String parentFqn = source.getServiceName() + "." + dbName + "." + schema;

                        long oid = new BigInteger(DigestUtils.md5Hex(fqn).substring(0, 8), 16).longValue();

                        TableMetadata table = new TableMetadata();
                        table.setId(new EntityId(oid, parentFqn));
                        table.setFqn(fqn);
                        table.setDbName(dbName);
                        table.setSchemaName(schema);
                        table.setName(tableName);
                        table.setServiceName(source.getServiceName());
                        table.setDescription(rs.getString("DESCRIPTION"));
                        table.setCreatedAt(now);

                        String jsonColumns = rs.getString("COLUMNS_JSON");
                        String jsonConstraints = rs.getString("TABLE_CONSTRAINTS_JSON");

                        ObjectNode data = objectMapper.createObjectNode();
                        data.put("tableType", tableType);
                        data.put("viewDefinition", viewDefinition);
                        data.set("columns", objectMapper.readTree(jsonColumns != null ? jsonColumns : "[]"));
                        data.set("tableConstraints", objectMapper.readTree(jsonConstraints != null ? jsonConstraints : "[]"));

                        table.setData(data);
                        table.setHashData(DigestUtils.md5Hex(fqn + jsonColumns + jsonConstraints));
                        entities.add(table);

                    } catch (JsonProcessingException e) {
                        log.error("Ошибка при обработке таблицы Oracle {}: {}", rs.getString("TABLE_NAME"), e.getMessage());
                        throw e;
                    }
                }
            }

            tableRep.saveAll(entities);
            log.info("Реплицировано {} таблиц Oracle для схемы {} в DB {}", entities.size(), schemaName, dbName);

        } catch (JsonProcessingException e) {
            log.error("Ошибка обработки JSON при получении таблиц Oracle для схемы {}: {}", schemaName, e.getMessage(), e);
        } catch (SQLException e) {
            log.error("Ошибка при подключении и получении таблиц Oracle для схемы {}: {}", schemaName, e.getMessage(), e);
            throw e;
        }

        return entities.size();
    }
}
