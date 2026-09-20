package com.abhi.processmining.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldProblem(String field, String message) {}

    @ExceptionHandler(InvalidCsvException.class)
    public ProblemDetail handleInvalidCsv(InvalidCsvException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid CSV", ex.getMessage(), request);
    }

    @ExceptionHandler(MultipartException.class)
    public ProblemDetail handleMultipart(MultipartException ex, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid upload",
                "Send the CSV as multipart/form-data with a part named 'file'",
                request
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return problem(
                HttpStatus.CONFLICT,
                "Conflict",
                "The request conflicts with data that already exists",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "Something went wrong on our side. The error has been logged.",
                request
        );
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        List<FieldProblem> problems = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            problems.add(new FieldProblem(error.getField(), error.getDefaultMessage()));
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> problems.add(new FieldProblem(error.getObjectName(), error.getDefaultMessage())));

        return validationProblem(ex, problems, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        List<FieldProblem> problems = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            RequestParam requestParam = result.getMethodParameter().getParameterAnnotation(RequestParam.class);
            String name = requestParam != null && !requestParam.name().isEmpty()
                    ? requestParam.name()
                    : result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                problems.add(new FieldProblem(name, error.getDefaultMessage()));
            }
        });

        return validationProblem(ex, problems, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        if (body instanceof ProblemDetail problem && problem.getInstance() == null
                && request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationProblem(
            Exception ex,
            List<FieldProblem> problems,
            HttpHeaders headers,
            WebRequest request
    ) {
        String detail = problems.stream()
                .map(problem -> problem.field() + ": " + problem.message())
                .collect(Collectors.joining("; "));

        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        body.setTitle("Validation failed");
        body.setProperty("errors", problems);

        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
