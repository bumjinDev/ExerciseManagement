package com.exercisemanagement.members.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)  // 422
public class ReservedUserIdException extends RuntimeException {
    public ReservedUserIdException(String message) {
        super(message);
    }
}
