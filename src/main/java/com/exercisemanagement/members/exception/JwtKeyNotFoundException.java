package com.exercisemanagement.members.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class JwtKeyNotFoundException extends RuntimeException {
    public JwtKeyNotFoundException(String message) {
        super(message);
    }
}
