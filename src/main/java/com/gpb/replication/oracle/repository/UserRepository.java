package com.gpb.replication.oracle.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gpb.replication.oracle.model.UserEntity;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);
}
