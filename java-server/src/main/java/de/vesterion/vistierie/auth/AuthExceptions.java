package de.vesterion.vistierie.auth;

public final class AuthExceptions {
    public static class Unauthorized extends RuntimeException {
        public Unauthorized(String msg) { super(msg); }
    }
    public static class Forbidden extends RuntimeException {
        public Forbidden(String msg) { super(msg); }
    }
    private AuthExceptions() {}
}
