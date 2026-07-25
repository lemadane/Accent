package io.lemadane.jatot.json;

import org.junit.jupiter.api.Test;

import io.lemadane.jatot.json.Json;
import io.lemadane.jatot.json.JsonIgnore;
import io.lemadane.jatot.json.JsonMappingException;
import io.lemadane.jatot.json.JsonName;
import io.lemadane.jatot.json.JsonNamingPolicy;
import io.lemadane.jatot.json.JsonOptions;

import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class JsonTest {

    public enum Status { ACTIVE, INACTIVE }

    public record User(
        @JsonName("user_id") UUID id,
        String name,
        int age,
        Status status,
        List<String> tags,
        Map<String, Integer> scores,
        @JsonIgnore String secret,
        Optional<String> bio,
        Address address,
        Instant createdAt
    ) {}

    public record Address(String city, String zip) {}

    @Test
    void testValid() {
        assertTrue(Json.isValid("{\"a\": 1}"));
        assertFalse(Json.isValid("{\"a\": 1"));
    }

    @Test
    void testParseAndStringify() {
        String json = """
        {
            "user_id": "123e4567-e89b-12d3-a456-426614174000",
            "name": "Alice",
            "age": 30,
            "status": "ACTIVE",
            "tags": ["admin", "user"],
            "scores": {"math": 100, "science": 90},
            "secret": "should_be_ignored",
            "bio": "Hello World",
            "address": {"city": "Wonderland", "zip": "12345"},
            "createdAt": "2023-10-01T12:00:00Z"
        }
        """;

        User user = Json.parse(json, User.class);
        assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), user.id());
        assertEquals("Alice", user.name());
        assertEquals(30, user.age());
        assertEquals(Status.ACTIVE, user.status());
        assertEquals(List.of("admin", "user"), user.tags());
        assertEquals(Map.of("math", 100, "science", 90), user.scores());
        assertNull(user.secret());
        assertEquals(Optional.of("Hello World"), user.bio());
        assertEquals("Wonderland", user.address().city());
        assertEquals(Instant.parse("2023-10-01T12:00:00Z"), user.createdAt());

        String stringified = Json.stringify(user);
        assertTrue(stringified.contains("\"user_id\":\"123e4567-e89b-12d3-a456-426614174000\""));
        assertTrue(stringified.contains("\"name\":\"Alice\""));
        assertFalse(stringified.contains("secret"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testRejectClass() {
        class NotARecord {
            public String name;
        }
        assertThrows(JsonMappingException.class, () -> Json.parse("{}", (Class) NotARecord.class));
    }

    public record SnakeTest(String myName, int myAge) {}

    @Test
    void testNamingPolicy() {
        Json json = new Json(new JsonOptions(JsonNamingPolicy.SNAKE_CASE, true, false));
        SnakeTest obj = json.parseInternal("{\"my_name\":\"Bob\", \"my_age\":25}", SnakeTest.class);
        assertEquals("Bob", obj.myName());
        assertEquals(25, obj.myAge());
        
        String s = json.stringifyInternal(obj);
        assertTrue(s.contains("\"my_name\":\"Bob\""));
        assertTrue(s.contains("\"my_age\":25"));
    }
}
