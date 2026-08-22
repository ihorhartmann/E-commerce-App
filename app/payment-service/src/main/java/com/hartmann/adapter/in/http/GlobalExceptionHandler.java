package com.hartmann.adapter.in.http;

import com.hartmann.adapter.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.application.exception.ApplicationException;
import com.hartmann.application.port.out.repository.exception.ObjectNotFoundException;
import com.hartmann.domain.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ObjectNotFoundException.class)
    public ProblemDetail handlePaymentNotFound(ObjectNotFoundException ex) {
        var detail = ex.getMessage() == null ? "Object not found" : ex.getMessage();
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
    }

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomainException(DomainException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(PaymentGatewayUnavailableException.class)
    public ProblemDetail handlePaymentGatewayUnavailable(PaymentGatewayUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(ApplicationException.class)
    public ProblemDetail handleApplicationException(ApplicationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        LOG.error(ex.getMessage(), ex);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error"
        );
    }
}
