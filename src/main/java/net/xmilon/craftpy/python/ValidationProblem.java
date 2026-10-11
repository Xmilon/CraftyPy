package net.xmilon.craftpy.python;

/**
 * A diagnostics entry reported by the Python worker (syntax/validation errors).
 * Kept in a common package so both the server pipeline and the client
 * Problems panel can use it without client/server coupling.
 */
public record ValidationProblem(String file, int line, int column, String severity, String message) {

    public boolean isError() {
        return "error".equalsIgnoreCase(severity);
    }

    public static ValidationProblem asInfo(String message) {
        return new ValidationProblem("", 0, 0, "info", message);
    }

    public static ValidationProblem asError(String message) {
        return new ValidationProblem("", 0, 0, "error", message);
    }
}