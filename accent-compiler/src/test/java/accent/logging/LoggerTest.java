package accent.logging;
import org.junit.jupiter.api.Test;

import accent.logging.LogManager;
import accent.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class LoggerTest {
    @Test
    public void testLogger() {
        Logger logger = LogManager.getLogger(LoggerTest.class);
        logger.info("Test message");
        assertNotNull(logger);
    }
}
