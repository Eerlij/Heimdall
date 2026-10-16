package io.github.eerlij.heimdall.shared.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 {@link ProblemDetail} response.
 *
 * <p>Spring MVC exceptions (404, 405, validation, ...) are handled by {@link ResponseEntityExceptionHandler}. Anything
 * else becomes a generic 500 whose body never exposes exception types, messages or stack traces; the details go to the
 * log instead. Modules add handlers for their own exceptions next to this one.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/**
	 * Handles any exception not mapped elsewhere.
	 *
	 * @param ex the unexpected exception
	 * @return a generic 500 problem without internal details
	 */
	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
	}

}
