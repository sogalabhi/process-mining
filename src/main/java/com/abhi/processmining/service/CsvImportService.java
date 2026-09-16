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

@Service
public class CsvImportService {

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

                EventCsvRow row = new EventCsvRow(
                        record.get("case_id"),
                        record.get("activity"),
                        Instant.parse(record.get("timestamp"))
                );

                rows.add(row);
            }
        }

        return rows;
    }
}