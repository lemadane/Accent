package io.lemadane.jatot.json;

public class JsonParseException extends JsonException {
    private static final long serialVersionUID = 1L;
    public JsonParseException(String message) {
        super(message);
    }
    public JsonParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
