package com.abhi.processmining.repository;

import com.abhi.processmining.model.ProcessCase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessCaseRepository
        extends JpaRepository<ProcessCase, Long> {
}