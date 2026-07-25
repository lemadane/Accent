package io.lemadane.jatot.runtime;

public final class JatotExecutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JatotExecutionException(Throwable cause) {
        super(cause.getMessage(), cause);
    }
}
