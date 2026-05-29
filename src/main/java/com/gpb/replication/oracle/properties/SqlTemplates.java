package com.gpb.replication.oracle.properties;

import org.springframework.stereotype.Component;


@Component("sqlTemplates")
public class SqlTemplates {

    /**
     * уровень "базы данных" (PDB name)
     * Oracle не имеет отдельного списка баз, поэтому используем текущее имя PDB.
     */
    private final String databaseSql = """
                SELECT 
                    d.dbid AS oid,
                    d.name AS db_name
                FROM v$database d
            """;
    /**
     * Только пользовательские схемы (oracle_maintained = 'N')
     */
    private final String schemaSql = """
            SELECT 
                user_id AS "oid", 
                username AS "schema_name"
            FROM all_users
            WHERE (oracle_maintained = 'N' OR oracle_maintained IS NULL)
              AND username NOT LIKE 'OPS$%%'
            ORDER BY username
            """;

    /**
     * Таблицы и вьюхи для конкретной схемы
     */
    private final String tableSql = """
            SELECT
                o.object_id AS OID,
                t.owner AS SCHEMA_NAME,
                t.table_name AS TABLE_NAME,
                'REGULAR' AS TABLE_TYPE,
                NULL AS VIEW_DEFINITION,
                NULL AS DESCRIPTION,
                col_data.COLUMNS_JSON,
                cons_data.TABLE_CONSTRAINTS_JSON
            FROM dba_tables t
            JOIN dba_objects o
                ON o.owner = t.owner
            AND o.object_name = t.table_name
            AND o.object_type = 'TABLE'

            OUTER APPLY (
                SELECT JSON_ARRAYAGG(
                    JSON_OBJECT(
                        'name' VALUE c.column_name,
                        'dataType' VALUE c.data_type,
                        'dataTypeDisplay' VALUE
                            CASE
                                WHEN c.data_type IN ('VARCHAR2','CHAR','NVARCHAR2','NCHAR')
                                    THEN c.data_type || '(' || c.data_length || ')'
                                WHEN c.data_type = 'NUMBER'
                                    THEN c.data_type ||
                                        CASE
                                            WHEN c.data_precision IS NOT NULL THEN
                                                '(' || c.data_precision ||
                                                CASE WHEN c.data_scale IS NOT NULL THEN ',' || c.data_scale ELSE '' END ||
                                                ')'
                                            ELSE ''
                                        END
                                ELSE c.data_type
                            END,
                        'dataLength' VALUE c.data_length,
                        'constraint' VALUE CASE c.nullable WHEN 'N' THEN 'NOT_NULL' ELSE 'NULLABLE' END,
                        'ordinalPosition' VALUE c.column_id
                    )
                    RETURNING CLOB
                ) AS COLUMNS_JSON
                FROM dba_tab_columns c
                WHERE c.owner = t.owner
                AND c.table_name = t.table_name
            ) col_data

            OUTER APPLY (
                SELECT JSON_ARRAYAGG(
                    JSON_OBJECT(
                        'constraintType' VALUE CASE ac.constraint_type
                            WHEN 'P' THEN 'PRIMARY_KEY'
                            WHEN 'R' THEN 'FOREIGN_KEY'
                            WHEN 'U' THEN 'UNIQUE'
                            ELSE NULL
                        END,
                        'columns' VALUE (
                            SELECT JSON_ARRAYAGG(acc.column_name RETURNING CLOB)
                            FROM dba_cons_columns acc
                            WHERE acc.owner = ac.owner
                            AND acc.constraint_name = ac.constraint_name
                            AND acc.table_name = ac.table_name
                        ) FORMAT JSON
                    )
                    RETURNING CLOB
                ) AS TABLE_CONSTRAINTS_JSON
                FROM dba_constraints ac
                WHERE ac.owner = t.owner
                AND ac.table_name = t.table_name
                AND ac.constraint_type IN ('P','R','U')
            ) cons_data

            WHERE t.owner = ?

            UNION ALL

            SELECT
                o.object_id AS OID,
                v.owner AS SCHEMA_NAME,
                v.view_name AS TABLE_NAME,
                'VIEW' AS TABLE_TYPE,
                v.text AS VIEW_DEFINITION,
                NULL AS DESCRIPTION,
                col_data.COLUMNS_JSON,
                NULL AS TABLE_CONSTRAINTS_JSON
            FROM dba_views v
            JOIN dba_objects o
                ON o.owner = v.owner
            AND o.object_name = v.view_name
            AND o.object_type = 'VIEW'

            OUTER APPLY (
                SELECT JSON_ARRAYAGG(
                    JSON_OBJECT(
                        'name' VALUE c.column_name,
                        'dataType' VALUE c.data_type,
                        'dataTypeDisplay' VALUE c.data_type,
                        'dataLength' VALUE c.data_length,
                        'constraint' VALUE CASE c.nullable WHEN 'N' THEN 'NOT_NULL' ELSE 'NULLABLE' END,
                        'ordinalPosition' VALUE c.column_id
                    )
                    RETURNING CLOB
                ) AS COLUMNS_JSON
                FROM dba_tab_columns c
                WHERE c.owner = v.owner
                AND c.table_name = v.view_name
            ) col_data

            WHERE v.owner = ?

            UNION ALL

            SELECT
                o.object_id AS OID,
                v.owner AS SCHEMA_NAME,
                v.mview_name AS TABLE_NAME,
                'MATERIALIZED VIEW' AS TABLE_TYPE,
                v.query AS VIEW_DEFINITION,
                NULL AS DESCRIPTION,
                col_data.COLUMNS_JSON,
                NULL AS TABLE_CONSTRAINTS_JSON
            FROM dba_mviews v
            JOIN dba_objects o
                ON o.owner = v.owner
            AND o.object_name = v.mview_name
            AND o.object_type = 'MATERIALIZED VIEW'

            OUTER APPLY (
                SELECT JSON_ARRAYAGG(
                    JSON_OBJECT(
                        'name' VALUE c.column_name,
                        'dataType' VALUE c.data_type,
                        'dataTypeDisplay' VALUE c.data_type,
                        'dataLength' VALUE c.data_length,
                        'constraint' VALUE CASE c.nullable WHEN 'N' THEN 'NOT_NULL' ELSE 'NULLABLE' END,
                        'ordinalPosition' VALUE c.column_id
                    )
                    RETURNING CLOB
                ) AS COLUMNS_JSON
                FROM dba_tab_columns c
                WHERE c.owner = v.owner
                AND c.table_name = v.mview_name
            ) col_data

            WHERE v.owner = ?
            """;


    public String getDatabaseSql() {
        return databaseSql;
    }

    public String getSchemaSql() {
        return schemaSql;
    }

    public String getTableSql() {
        return tableSql;
    }
}