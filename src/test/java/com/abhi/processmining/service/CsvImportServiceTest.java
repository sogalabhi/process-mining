package com.abhi.processmining.service;

import com.abhi.processmining.dto.EventCsvRow;
import com.abhi.processmining.exception.InvalidCsvException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvImportServiceTest {

    private final CsvImportService service = new CsvImportService(null);

    @Test
    void parsesValidRows() throws IOException {
        List<EventCsvRow> rows = service.parse(csv("case_id,activity,timestamp\nC-1,A,2026-08-01T10:00:00Z\nC-1,B,2026-08-01T11:00:00Z\n"));

        assertThat(rows).containsExactly(
                new EventCsvRow("C-1", "A", Instant.parse("2026-08-01T10:00:00Z")),
                new EventCsvRow("C-1", "B", Instant.parse("2026-08-01T11:00:00Z"))
        );
    }

    @Test
    void acceptsByteOrderMarkAndSurroundingSpaces() throws IOException {
        List<EventCsvRow> rows = service.parse(csv("﻿case_id, activity ,timestamp\n C-1 , A ,2026-08-01T10:00:00Z\n"));

        assertThat(rows).containsExactly(new EventCsvRow("C-1", "A", Instant.parse("2026-08-01T10:00:00Z")));
    }

    @Test
    void rejectsEmptyFile() {
        assertInvalid("", "File is empty");
    }

    @Test
    void rejectsHeaderWithoutRows() {
        assertInvalid("case_id,activity,timestamp\n", "no data rows");
    }

    @Test
    void rejectsMissingColumn() {
        assertInvalid("case_id,activity\nC-1,A\n", "Missing column(s) [timestamp]");
    }

    @Test
    void rejectsWrongHeaders() {
        assertInvalid("foo,bar,baz\n1,2,3\n", "Missing column(s) [case_id, activity, timestamp]");
    }

    @Test
    void rejectsRowWithTooFewValues() {
        assertInvalid("case_id,activity,timestamp\nC-1,A\n", "Row 1: expected 3 values, found 2");
    }

    @Test
    void rejectsRowWithTooManyValues() {
        assertInvalid("case_id,activity,timestamp\nC-1,A,2026-08-01T10:00:00Z,extra\n", "Row 1: expected 3 values, found 4");
    }

    @Test
    void rejectsInvalidTimestamp() {
        assertInvalid("case_id,activity,timestamp\nC-1,A,yesterday\n", "Row 1: invalid timestamp: yesterday");
    }

    @Test
    void rejectsTimestampWithoutZone() {
        assertInvalid("case_id,activity,timestamp\nC-1,A,2026-08-01T10:00:00\n", "invalid timestamp");
    }

    @Test
    void rejectsBlankCaseIdAndActivity() {
        assertInvalid("case_id,activity,timestamp\n ,A,2026-08-01T10:00:00Z\n", "Row 1: case_id is required");
        assertInvalid("case_id,activity,timestamp\nC-1,,2026-08-01T10:00:00Z\n", "Row 1: activity is required");
    }

    @Test
    void rejectsValuesLongerThanTheColumn() {
        String longValue = "x".repeat(256);
        assertInvalid("case_id,activity,timestamp\n" + longValue + ",A,2026-08-01T10:00:00Z\n", "case_id is longer than 255");
        assertInvalid("case_id,activity,timestamp\nC-1," + longValue + ",2026-08-01T10:00:00Z\n", "activity is longer than 255");
    }

    @Test
    void rejectsUnclosedQuote() {
        assertInvalid("case_id,activity,timestamp\nC-1,\"A,2026-08-01T10:00:00Z\n", "Malformed CSV");
    }

    private void assertInvalid(String content, String message) {
        assertThatThrownBy(() -> service.parse(csv(content)))
                .isInstanceOf(InvalidCsvException.class)
                .hasMessageContaining(message);
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "events.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
