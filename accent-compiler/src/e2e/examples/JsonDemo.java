

import io.lemadane.accent.json.Json;
import io.lemadane.accent.json.JsonOptions;
import io.lemadane.accent.json.JsonName;
import java.util.UUID;
import java.util.List;
import java.time.LocalDate;

public class JsonDemo {
    public record Address(
        String street,
        String city
    ) {}

    public record User(
        UUID id,
        String firstName,
        String lastName,
        LocalDate birthDate,
        Address address
    ) {
        public User(UUID id, String firstName, String lastName, LocalDate birthDate, Address address) {
            if (firstName == null || firstName.isBlank()) {
                throw new IllegalArgumentException("firstName cannot be blank");
            }
            this.id = id;
            this.firstName = firstName;
            this.lastName = lastName;
            this.birthDate = birthDate;
            this.address = address;
        }
    }

    public static void main(String[] args) {
        System.out.println("Starting accent.json.Json Demo...");

        // 1. Serialization (Stringify)
        User originalUser = new User(
            UUID.randomUUID(),
            "Lemuel",
            "Adane",
            LocalDate.of(2000, 1, 1),
            new Address("Rizal Street", "Quezon City")
        );

        String json = Json.stringify(originalUser);
        System.out.println("\n--- Serialized JSON ---");
        System.out.println(json);

        // 2. Deserialization (Parse)
        System.out.println("\n--- Parsed Record ---");
        User parsedUser = Json.parse(json, User.class);
        System.out.println(parsedUser);

        // 3. Array Parsing
        System.out.println("\n--- Array Parsing ---");
        String arrayJson = Json.stringify(List.of(originalUser, parsedUser));
        List<User> users = Json.parseList(arrayJson, User.class);
        System.out.printf("Parsed %d users from array.\n", users.size());

        // 4. Invalid Parsing (Canonical Constructor Validation)
        System.out.println("\n--- Constructor Validation ---");
        try {
            Json.parse("""
            {
                "id": "793acb5d-b426-43ef-bf49-26613d12d26d",
                "firstName": "",
                "lastName": "Adane",
                "birthDate": "2000-01-01"
            }
            """, User.class);
        } catch (Exception e) {
            System.out.println("Caught expected validation exception: " + e.getMessage());
            System.out.println("Cause: " + e.getCause().getMessage());
        }

        System.out.println("\nJsonDemo finished successfully.");
    }
}
