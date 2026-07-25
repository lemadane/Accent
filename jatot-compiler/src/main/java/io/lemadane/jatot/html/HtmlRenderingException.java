package io.lemadane.jatot.html;

public final class HtmlRenderingException extends RuntimeException {
    
    private static final long serialVersionUID = 1L;
    
    public HtmlRenderingException(String message, Throwable cause) {
        super(message, cause);
    }

    public HtmlRenderingException(String message) {
        super(message);
    }
}
