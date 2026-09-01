package io.lemadane.accent.runtime;

public final class AccentExecutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AccentExecutionException(Throwable cause) {
        super(cause.getMessage(), cause);
    }
}
