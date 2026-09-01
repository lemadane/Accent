package accent.json;

public class JsonMappingException extends JsonException {
    private static final long serialVersionUID = 1L;
    public JsonMappingException(String message) {
        super(message);
    }
    public JsonMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
