package com.gpb.replication.oracle.repository;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gpb.replication.oracle.model.EntityId;
import com.gpb.replication.oracle.model.TableMetadata;

public interface TableMetadataRepository extends JpaRepository<TableMetadata, EntityId> {
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM oracle_metadata.table_metadata WHERE service_name = :service", nativeQuery = true)
    void deleteByServiceName(@Param("service") String service);
}
