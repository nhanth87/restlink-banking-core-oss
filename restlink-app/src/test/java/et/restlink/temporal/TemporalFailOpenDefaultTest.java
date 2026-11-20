package et.restlink.temporal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalFailOpenDefaultTest {

    @Test
    void productionDefaultIsFailClosed() throws Exception {
        Path props = Path.of("src/main/resources/application.properties");
        if (!Files.isRegularFile(props)) {
            props = Path.of("restlink-app/src/main/resources/application.properties");
        }
        String text = Files.readString(props);
        assertTrue(text.contains("restlink.temporal.fail-open-on-startup=${RESTLINK_TEMPORAL_FAIL_OPEN:false}"),
                "P1 initial deploy must fail closed when Temporal is down at boot");
    }
}
