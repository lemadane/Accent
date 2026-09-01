package io.lemadane.accent.html.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import io.lemadane.accent.html.Component;
import io.lemadane.accent.html.Html;

@SpringBootApplication
@Controller
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @GetMapping("/fragment")
    public Html showFragment() {
        return writer -> {
            writer.literal("<div>Hello from raw Html interface!</div>");
        };
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.boot.CommandLineRunner initDb(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return args -> {
            jdbc.execute("CREATE TABLE IF NOT EXISTS users (name VARCHAR(255) PRIMARY KEY, email VARCHAR(255))");
            jdbc.execute("MERGE INTO users KEY(name) VALUES ('Mel', 'mel@example.com')");
            jdbc.execute("MERGE INTO users KEY(name) VALUES ('Zack', 'zack@example.com')");
        };
    }
}
