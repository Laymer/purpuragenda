package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.domain.DomainFailure;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestValueException;

@RestControllerAdvice
public class ProblemHandler {
    @ExceptionHandler(DomainFailure.class) public ResponseEntity<Map<String,Object>> business(DomainFailure failure){return problem(failure.code());}
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,MissingRequestValueException.class,IllegalArgumentException.class})
    public ResponseEntity<Map<String,Object>> validation(Exception failure){return problem("VALIDATION_FAILED");}
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,Object>> unexpected(Exception failure){return problem("INTERNAL_ERROR");}
    public static ResponseEntity<Map<String,Object>> problem(String code){
        int status=switch(code){case "UNAUTHENTICATED","AUTHENTICATION_FAILED"->401;case "FORBIDDEN","CSRF_REQUIRED"->403;case "NOT_FOUND"->404;case "PRECONDITION_REQUIRED"->428;case "STALE_VERSION"->412;case "RATE_LIMITED"->429;case "RETRY_LATER","MAINTENANCE"->503;case "INTERNAL_ERROR"->500;case "VALIDATION_FAILED","INVALID_DATE_RANGE","INVALID_EMAIL","INVALID_PHONE","INVALID_ZONE","INVALID_AMOUNT","INVALID_CURRENCY","INVALID_MEETING","INVALID_TOKEN","INVALID_PURPOSE","INVALID_PRECISION"->400;default->409;};
        var body=Map.<String,Object>of("type","about:blank","title",status>=500?"The request could not be completed":"Please review the request","status",status,"code",code,"correlationId",UUID.randomUUID().toString(),"retryable",status==503,"refreshAvailability",Set.of("SLOT_UNAVAILABLE","DAILY_CAPACITY_REACHED","SERVICE_CHANGED","POLICY_CHANGED").contains(code));
        var builder=ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).cacheControl(CacheControl.noStore());
        if(status==429||status==503)builder.header("Retry-After","60");return builder.body(body);
    }
}
