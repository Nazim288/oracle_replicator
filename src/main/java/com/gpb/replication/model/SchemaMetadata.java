package com.gpb.replication.model;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "schema_metadata", schema = "oracle_metadata")
@EntityListeners(AuditingEntityListener.class)
public class SchemaMetadata {
    @EmbeddedId
    private EntityId id;

    @Column(name = "fqn")
    private String fqn;

    @Column(name = "db_name")
    private String dbName;

    @Column(name = "name")
    private String name;

    @Column(name = "service_name")
    private String serviceName;

    @Column(name = "hash_data")
    private String hashData;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}

