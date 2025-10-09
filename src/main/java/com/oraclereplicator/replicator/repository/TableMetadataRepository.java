package com.oraclereplicator.replicator.repository;

import com.oraclereplicator.replicator.model.EntityId;
import com.oraclereplicator.replicator.model.TableMetadata;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TableMetadataRepository extends JpaRepository<TableMetadata, EntityId> {
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM oracle_metadata.table_metadata WHERE service_name = :service", nativeQuery = true)
    void deleteByServiceName(@Param("service") String service);
}
