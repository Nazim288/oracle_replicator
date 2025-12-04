package com.gpb.replication.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gpb.replication.model.UserEntity;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);
}
