package com.exercisemanagement.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.exercisemanagement.auth.entity.AuthenticationEntity;

public interface UserEntityRepository extends JpaRepository<AuthenticationEntity, String> {

    Optional<AuthenticationEntity> findByUsername(String username);

    Optional<AuthenticationEntity> findByUserid(String userid);
}
