package com.abhi.processmining.repository;

import com.abhi.processmining.model.ProcessCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessCaseRepository
        extends JpaRepository<ProcessCase, Long> {
                Optional<ProcessCase> findByCaseId(String caseId);
}