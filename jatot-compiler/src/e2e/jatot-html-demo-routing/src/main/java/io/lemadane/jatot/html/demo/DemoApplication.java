package io.lemadane.jatot.html.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import io.lemadane.jatot.html.Component;
import io.lemadane.jatot.html.Html;

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
}
