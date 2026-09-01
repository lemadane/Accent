package accent.html.demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = DemoApplication.class)
@AutoConfigureMockMvc
class HtmlEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testShowUserPage() throws Exception {
        mockMvc.perform(get("/users/Mel"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<!DOCTYPE html>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>Accent App</title>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<div class=\"app-layout\">")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<div class=\"user-portal\">")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<h2>User Portal Dashboard</h2>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<article class=\"user-card\">")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<h2>Mel</h2>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<p>mel@example.com</p>")));
    }

    @Test
    void testShowFragment() throws Exception {
        mockMvc.perform(get("/fragment"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string("<div>Hello from raw Html interface!</div>"));
    }

    @Test
    void testShowRootPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<!DOCTYPE html>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>Accent App</title>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<div class=\"app-layout\">")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<h1>Welcome to Accent Routing!</h1>")));
    }

    @Test
    void testDatabaseOrmMapping() throws Exception {
        List<accent.html.demo.User> list = (List<accent.html.demo.User>) accent.sql.Sql.execute(
            "SELECT name, email FROM users WHERE name = ?",
            List.of("Zack"),
            accent.html.demo.User.class
        );
        assertEquals(1, list.size());
        assertEquals("Zack", list.get(0).name());
        assertEquals("zack@example.com", list.get(0).email());

        record SimpleUser(String nameVal, String emailVal) {}
        List<SimpleUser> list2 = (List<SimpleUser>) accent.sql.Sql.execute(
            "SELECT name, email FROM users WHERE name = ?",
            List.of("Mel"),
            SimpleUser.class
        );
        assertEquals(1, list2.size());
        assertEquals("Mel", list2.get(0).nameVal());
        assertEquals("mel@example.com", list2.get(0).emailVal());

        record Contact(String email) {}
        record NestedUser(String name, Contact contact) {}
        List<NestedUser> list3 = (List<NestedUser>) accent.sql.Sql.execute(
            "SELECT name, email FROM users WHERE name = ?",
            List.of("Mel"),
            NestedUser.class
        );
        assertEquals(1, list3.size());
        assertEquals("Mel", list3.get(0).name());
        assertEquals("mel@example.com", list3.get(0).contact().email());
    }
}
