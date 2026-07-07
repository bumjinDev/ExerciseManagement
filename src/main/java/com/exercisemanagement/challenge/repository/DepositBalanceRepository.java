package com.exercisemanagement.challenge.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.exercisemanagement.challenge.entity.DepositBalance;

public interface DepositBalanceRepository extends JpaRepository<DepositBalance, String> {
}
