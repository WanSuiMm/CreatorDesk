package org.creatordesk.error;

import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.creatordesk.peer.PeerException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DeskException.class)
    ResponseEntity<?> desk(DeskException error) {
        return ResponseEntity.status(error.status()).body(Map.of("error", Map.of(
                "code", error.code(), "message", error.getMessage())));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                "code", "invalid_json", "message", "The request could not be read.")));
    }

    @ExceptionHandler(PeerException.class)
    ResponseEntity<?> peer(PeerException error) {
        int status = error.code().equals("timeout") ? 504 : 502;
        return ResponseEntity.status(status).body(Map.of("error", Map.of(
                "code", error.code(), "message", "An upstream service could not complete the request.")));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<?> route(Exception ignored) {
        return ResponseEntity.status(404).body(Map.of("error", Map.of(
                "code", "route_not_found", "message", "The requested route does not exist.")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ignored) {
        return ResponseEntity.status(HttpStatusCode.valueOf(500)).body(Map.of("error", Map.of(
                "code", "internal_error", "message", "The operation could not be completed.")));
    }
}
