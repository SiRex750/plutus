package com.plutus.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.plutus.ingest.csv.CsvImportException;

/**
 * Maps domain exceptions to RFC 7807 problem details. Framework errors (missing multipart part,
 * upload too large, ...) are handled by Spring's built-in problem-details handler
 * ({@code spring.mvc.problemdetails.enabled}).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(CsvImportException.class)
    public ProblemDetail csvRejected(CsvImportException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("CSV file rejected");
        problem.setProperty("reason", e.reason().name());
        return problem;
    }
}
