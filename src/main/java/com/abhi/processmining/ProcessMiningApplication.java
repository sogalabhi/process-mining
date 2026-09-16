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
}