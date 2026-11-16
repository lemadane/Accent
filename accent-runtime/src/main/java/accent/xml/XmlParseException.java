package accent.xml;

/** Invalid or unsupported XML input. */
public final class XmlParseException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    public XmlParseException(String message) { super(message); }
    public XmlParseException(String message, Throwable cause) { super(message, cause); }
}
