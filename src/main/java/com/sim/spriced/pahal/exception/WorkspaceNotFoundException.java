package com.sim.spriced.pahal.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class WorkspaceNotFoundException extends RuntimeException {

    public WorkspaceNotFoundException(String id) {
        super("Workspace not found with id: " + id);
    }

    public WorkspaceNotFoundException(String field, String value) {
        super("Workspace not found with " + field + ": " + value);
    }
}