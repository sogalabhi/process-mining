package com.abhi.processmining.service;

import com.abhi.processmining.dto.EventCsvRow;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
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

    private static final List<String> REQUIRED_COLUMNS = List.of("case_id", "activity", "timestamp");
    private static final int MAX_VALUE_LENGTH = 255;

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

        if (caseId.length() > MAX_VALUE_LENGTH) {
            throw new InvalidCsvException(
                    "Row " + rowNumber + ": case_id is longer than " + MAX_VALUE_LENGTH + " characters"
            );
        }

        if (activity.length() > MAX_VALUE_LENGTH) {
            throw new InvalidCsvException(
                    "Row " + rowNumber + ": activity is longer than " + MAX_VALUE_LENGTH + " characters"
            );
        }
    }

    private void validateHeader(List<String> headerNames) {
        List<String> missing = REQUIRED_COLUMNS.stream()
                .filter(column -> !headerNames.contains(column))
                .toList();

        if (!missing.isEmpty()) {
            throw new InvalidCsvException(
                    "Missing column(s) " + missing + ". Expected header: " + String.join(",", REQUIRED_COLUMNS)
                            + ", found: " + String.join(",", headerNames)
            );
        }
    }

    private void skipByteOrderMark(BufferedReader reader) throws IOException {
        reader.mark(1);
        if (reader.read() != '\uFEFF') {
            reader.reset();
        }
    }

    public List<EventCsvRow> parse(MultipartFile file) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new InvalidCsvException("File is empty");
        }

        List<EventCsvRow> rows = new ArrayList<>();

        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                file.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                )
        ) {
            skipByteOrderMark(reader);

            try (
                    CSVParser parser = CSVFormat.DEFAULT.builder()
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .setTrim(true)
                            .build()
                            .parse(reader)
            ) {
                validateHeader(parser.getHeaderNames());

                for (CSVRecord record : parser) {
                    long rowNumber = record.getRecordNumber();

                    if (!record.isConsistent()) {
                        throw new InvalidCsvException(
                                "Row " + rowNumber + ": expected " + parser.getHeaderNames().size()
                                        + " values, found " + record.size()
                        );
                    }

                    Instant timestamp;

                    try {
                        timestamp = Instant.parse(record.get("timestamp"));
                    } catch (DateTimeParseException e) {
                        throw new InvalidCsvException(
                                "Row " + rowNumber
                                        + ": invalid timestamp: "
                                        + record.get("timestamp")
                                        + " (expected ISO-8601 UTC, e.g. 2026-08-01T10:00:00Z)"
                        );
                    }
                    EventCsvRow row = new EventCsvRow(
                            record.get("case_id"),
                            record.get("activity"),
                            timestamp
                    );

                    validateRow(row, rowNumber);

                    rows.add(row);
                }
            } catch (UncheckedIOException | IllegalArgumentException | IllegalStateException e) {
                throw new InvalidCsvException("Malformed CSV: " + e.getMessage());
            }
        }

        if (rows.isEmpty()) {
            throw new InvalidCsvException("CSV has a header but no data rows");
        }

        return rows;
    }
}
