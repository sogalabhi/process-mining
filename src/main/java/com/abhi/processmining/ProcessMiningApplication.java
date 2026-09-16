package com.abhi.processmining;

import com.abhi.processmining.model.Event;
import com.abhi.processmining.model.ProcessCase;
import com.abhi.processmining.repository.ProcessCaseRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;

import java.time.Instant;

@org.springframework.boot.autoconfigure.SpringBootApplication
public class ProcessMiningApplication {

    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(
                ProcessMiningApplication.class, args
        );
    }

    @Bean
    CommandLineRunner testPersistence(ProcessCaseRepository repository) {
        return args -> {

            ProcessCase processCase = new ProcessCase("ORDER-001");

            processCase.addEvent(
                    new Event("Order Created", Instant.parse("2026-09-16T10:00:00Z"))
            );

            processCase.addEvent(
                    new Event("Payment Received", Instant.parse("2026-09-16T10:02:00Z"))
            );

            processCase.addEvent(
                    new Event("Packed", Instant.parse("2026-09-16T10:05:00Z"))
            );

            repository.save(processCase);

            System.out.println(
                    "Saved case: " + processCase.getCaseId()
            );
        };
    }
}