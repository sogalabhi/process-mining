package com.abhi.processmining.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCsvException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(
            InvalidCsvException ex
    ) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "Invalid CSV",
                        "message", ex.getMessage()
                ));
    }
}