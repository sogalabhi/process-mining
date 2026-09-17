package com.abhi.processmining.service;

import com.abhi.processmining.dto.EventCsvRow;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.abhi.processmining.exception.InvalidCsvException;
import java.time.format.DateTimeParseException;

import java.util.Map;
import java.util.stream.Collectors;

import com.abhi.processmining.model.Event;
import com.abhi.processmining.model.ProcessCase;
import com.abhi.processmining.repository.ProcessCaseRepository;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CsvImportService {

    private final ProcessCaseRepository processCaseRepository;

    public CsvImportService(ProcessCaseRepository processCaseRepository) {
        this.processCaseRepository = processCaseRepository;
    }
    
    @Transactional
    public void importRows(List<EventCsvRow> rows) {
        Map<String, List<EventCsvRow>> rowsByCase = rows.stream().collect(Collectors.groupingBy(EventCsvRow::caseId));

        for (Map.Entry<String, List<EventCsvRow>> entry : rowsByCase.entrySet()) {
            String caseId = entry.getKey();
            List<EventCsvRow> caseRows = entry.getValue();

            if (processCaseRepository.findByCaseId(caseId).isPresent()) {
                throw new InvalidCsvException(
                        "Case already exists: " + caseId
                );
            }
            ProcessCase processCase = new ProcessCase(caseId);

            for (EventCsvRow row : caseRows) {
                Event event = new Event(
                        row.activity(),
                        row.timestamp()
                );

                processCase.addEvent(event);
            }

            processCaseRepository.save(processCase);

            System.out.println("Case: " + caseId);

            for (EventCsvRow row : caseRows) {
                System.out.println(
                        "  " + row.activity()
                                + " @ " + row.timestamp()
                );
            }
        }
    }
    private void validateRow(EventCsvRow row, long rowNumber) {
        String caseId = row.caseId();
        String activity = row.activity();

        if (caseId == null || caseId.isBlank()) {
            throw new InvalidCsvException(
                    "Row " + rowNumber + ": case_id is required"
            );
        }

        if (activity == null || activity.isBlank()) {
            throw new InvalidCsvException(
                    "Row " + rowNumber + ": activity is required"
            );
        }

    }

    public List<EventCsvRow> parse(MultipartFile file) throws IOException {

        List<EventCsvRow> rows = new ArrayList<>();

        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                file.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                );

                CSVParser parser = CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .build()
                        .parse(reader)
        ) {

            for (CSVRecord record : parser) {
                Instant timestamp;

                try {
                    timestamp = Instant.parse(record.get("timestamp"));
                } catch (DateTimeParseException e) {
                    throw new InvalidCsvException(
                            "Row " + record.getRecordNumber()
                                    + ": invalid timestamp: "
                                    + record.get("timestamp")
                    );
                }
                EventCsvRow row = new EventCsvRow(
                        record.get("case_id"),
                        record.get("activity"),
                        timestamp
                );

                validateRow(row, record.getRecordNumber());

                rows.add(row);
            }
        }

        return rows;
    }
}