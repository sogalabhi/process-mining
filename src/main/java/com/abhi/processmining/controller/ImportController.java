package com.abhi.processmining.controller;

import com.abhi.processmining.dto.EventCsvRow;
import com.abhi.processmining.service.CsvImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final CsvImportService csvImportService;

    public ImportController(CsvImportService csvImportService) {
        this.csvImportService = csvImportService;
    }

    @PostMapping
    public ResponseEntity<String> importCsv(
            @RequestParam("file") MultipartFile file
    ) throws IOException {

        List<EventCsvRow> rows = csvImportService.parse(file);

        return ResponseEntity.ok(
                "Parsed " + rows.size() + " rows"
        );
    }
}