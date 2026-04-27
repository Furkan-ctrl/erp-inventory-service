package com.erp.inventory_service.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(InventoryException.class)
    public ResponseEntity<ErrorResponse> handleInventoryException(InventoryException ex) {
        log.warn("Inventory error [{}]: {}", ex.getStatus(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(
                ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(ex.getStatus().value())
                        .error(ex.getStatus().getReasonPhrase())
                        .message(ex.getMessage())
                        .build()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(
                ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(400)
                        .error("Validation failed")
                        .message("One or more fields have invalid values")
                        .fieldErrors(fieldErrors)
                        .build()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unexpected error: ", ex);
        return ResponseEntity.internalServerError().body(
                ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(500)
                        .error("Internal Server Error")
                        .message("An unexpected error occurred")
                        .build()
        );
    }
}