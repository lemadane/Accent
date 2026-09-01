package io.lemadane.accent.json;

public record JsonOptions(
    JsonNamingPolicy namingPolicy,
    boolean ignoreUnknownProperties,
    boolean prettyPrint
) {
    public static final JsonOptions DEFAULT = new JsonOptions(JsonNamingPolicy.IDENTITY, true, false);
}
