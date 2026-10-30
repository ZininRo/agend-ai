package com.example.agend_ai.api.error;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(AppException.class)
  public ResponseEntity<ApiError> handleApp(AppException ex) {
    return respond(
        ex.getCode(), HttpStatusCode.valueOf(ex.getCode().httpStatus()), ex.getRetryAfterSec());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    ConstraintViolationException.class,
    HttpMediaTypeNotSupportedException.class
  })
  public ResponseEntity<ApiError> handleBadRequest(Exception ex) {
    log.debug("Bad request: {}", ex.getClass().getSimpleName());
    return respond(ErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST, null);
  }

  @ExceptionHandler(MissingServletRequestPartException.class)
  public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex) {
    ErrorCode code =
        "audio".equals(ex.getRequestPartName())
            ? ErrorCode.AUDIO_MISSING
            : ErrorCode.REQUEST_INVALID;
    return respond(code, HttpStatusCode.valueOf(code.httpStatus()), null);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex) {
    return respond(ErrorCode.AUDIO_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, null);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException ex) {
    return respond(ErrorCode.REQUEST_INVALID, HttpStatus.NOT_FOUND, null);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiError> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException ex) {
    return respond(ErrorCode.REQUEST_INVALID, HttpStatus.METHOD_NOT_ALLOWED, null);
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<ApiError> handleStorage(Exception ex) {
    Throwable root = ex;
    while (root.getCause() != null) root = root.getCause();
    log.error("Storage failure: {} (причина: {})", ex.getClass().getSimpleName(), root.getClass().getName());
    return respond(ErrorCode.STORAGE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, null);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleOther(Exception ex) {
    // Exception messages and stack traces can contain request payloads or decrypted values.
    log.error("Unhandled exception type: {}", ex.getClass().getName());
    return respond(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, null);
  }

  private ResponseEntity<ApiError> respond(
      ErrorCode code, HttpStatusCode status, Integer retryAfterSec) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
    if (retryAfterSec != null) {
      builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSec));
    }
    return builder.body(ApiError.of(code, retryAfterSec, MDC.get(RequestIdFilter.MDC_KEY)));
  }
}