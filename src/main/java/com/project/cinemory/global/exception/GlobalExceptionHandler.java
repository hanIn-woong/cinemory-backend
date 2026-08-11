package com.project.cinemory.global.exception;

import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * {@link ResponseEntityExceptionHandler}를 상속한다(5-6-C ①). MVC가 표준으로 던지는 예외들
 * ({@code MethodArgumentNotValidException}, {@code HttpMessageNotReadableException},
 * {@code MissingServletRequestParameterException}, {@code TypeMismatchException}(하위 클래스인
 * {@code MethodArgumentTypeMismatchException} 포함), {@code HttpRequestMethodNotSupportedException},
 * {@code NoResourceFoundException}, {@code HttpMediaTypeNotSupportedException},
 * {@code HttpMediaTypeNotAcceptableException})은 부모가 이미 한 곳({@code handleException})에서
 * 가로채므로, 별도 {@code @ExceptionHandler}를 추가하는 대신 해당 protected 오버라이드로 응답
 * 바디만 {@link ErrorResponse}로 바꿔치기한다. 애플리케이션 고유 예외
 * ({@code BusinessException} 등)만 {@code @ExceptionHandler}로 남긴다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.from(errorCode));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    /**
     * 유니크 제약 위반 등 DB 무결성 위반.
     * 서비스에서 사전 중복 체크를 하더라도 동시 요청 경합 시에는 여기까지 도달할 수 있다.
     * (JPA IDENTITY 전략에서는 save() 시점에 INSERT가 즉시 실행되고 트랜잭션이
     *  rollback-only로 마킹되므로, 서비스 내부에서 catch해 정상 종료시킬 수 없다)
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        ErrorCode errorCode = ErrorCode.DUPLICATE_REQUEST;
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.from(errorCode));
    }

    /**
     * {@code @Valid} 검증 실패. 필드별 위반 목록을 담아 반환한다 —
     * 단일 message로 뭉치면 클라이언트 폼에서 어느 입력이 틀렸는지 다시 파싱해야 한다(5-0-C).
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        ErrorResponse body = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, fieldErrors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** 요청 바디 JSON 파싱 실패. 바디에 담긴 enum 값이 올바르지 않은 경우도 여기로 온다. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, ErrorResponse.from(ErrorCode.MALFORMED_REQUEST_BODY), headers, status, request);
    }

    /**
     * 필수 {@code @RequestParam} 누락(예: {@code rankType} 없이 {@code GET /api/box-office} 호출).
     * {@code @Valid} 실패와 같은 성격(요청에서 필수값이 빠짐)이라 동일하게 {@code INVALID_INPUT_VALUE} +
     * 필드명을 담은 {@code errors[]}로 응답한다.
     */
    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var fieldErrors = List.of(new ErrorResponse.FieldError(ex.getParameterName(), "필수 파라미터입니다."));
        ErrorResponse body = ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, fieldErrors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * 경로/쿼리 변수 타입 변환 실패. {@code MethodArgumentTypeMismatchException}이
     * {@code TypeMismatchException}의 하위 클래스라 여기로 함께 들어온다.
     * 쿼리 파라미터에 담긴 enum 값이 올바르지 않은 경우도 여기로 온다.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, ErrorResponse.from(ErrorCode.INVALID_TYPE_VALUE), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, ErrorResponse.from(ErrorCode.METHOD_NOT_ALLOWED), headers, status, request);
    }

    /** Boot 3.2+ 이후의 이름. {@code NoHandlerFoundException}이 아니다(5-0-C). */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, ErrorResponse.from(ErrorCode.ENDPOINT_NOT_FOUND), headers, status, request);
    }

    /** {@code Content-Type} 누락·불일치 (5-6-C ①). */
    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, ErrorResponse.from(ErrorCode.UNSUPPORTED_MEDIA_TYPE), headers, status, request);
    }

    /** {@code Accept} 헤더 불일치 (5-6-C ①). */
    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, ErrorResponse.from(ErrorCode.NOT_ACCEPTABLE), headers, status, request);
    }
}
