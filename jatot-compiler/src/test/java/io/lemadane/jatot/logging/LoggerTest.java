package io.lemadane.jatot.logging;
import org.junit.jupiter.api.Test;

import io.lemadane.jatot.logging.LogManager;
import io.lemadane.jatot.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class LoggerTest {
    @Test
    public void testLogger() {
        Logger logger = LogManager.getLogger(LoggerTest.class);
        logger.info("Test message");
        assertNotNull(logger);
    }
}
