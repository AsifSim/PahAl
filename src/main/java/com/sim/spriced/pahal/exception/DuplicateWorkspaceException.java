package com.sim.spriced.pahal.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateWorkspaceException extends RuntimeException {

    public DuplicateWorkspaceException(String name) {
        super("Workspace with name '" + name + "' already exists");
    }
}