package com.pulsegate.exception;

public class RouteNotFoundException extends RuntimeException {
    public RouteNotFoundException(String id) {
        super("Route not found: " + id);
    }
}
