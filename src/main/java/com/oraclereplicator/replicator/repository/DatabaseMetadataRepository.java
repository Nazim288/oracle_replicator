package com.oraclereplicator.replicator.repository;

import com.oraclereplicator.replicator.model.DatabaseMetadata;
import com.oraclereplicator.replicator.model.EntityId;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DatabaseMetadataRepository extends JpaRepository<DatabaseMetadata, EntityId> {
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM oracle_metadata.database_metadata WHERE service_name = :service", nativeQuery = true)
    void deleteByServiceName(@Param("service") String service);
}
