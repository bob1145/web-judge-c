import com.example.demo.dto.SandboxTaskEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JacksonEventMapperProbe {
    public static void main(String[] arguments) throws Exception {
        Path events = Path.of(arguments[0]);
        if (!Files.isRegularFile(events) || Files.size(events) > 16384) {
            throw new IllegalArgumentException("bounded real event evidence required");
        }
        String line = Files.readAllLines(events).stream()
                .filter(value -> value.contains("\"type\":\"RUN_FINISHED\""))
                .findFirst().orElseThrow();
        ObjectMapper timeOnly = new ObjectMapper().registerModule(new JavaTimeModule());
        boolean rejected = false;
        try {
            timeOnly.readValue(line, SandboxTaskEvent.class);
        } catch (com.fasterxml.jackson.databind.exc.InvalidDefinitionException expected) {
            rejected = expected.getMessage().contains("TestCaseResult")
                    && expected.getMessage().contains("no Creators");
            System.out.println("timeOnlyInvalidDefinition=" + rejected);
        }
        if (!rejected) {
            throw new IllegalStateException("expected constructor mapping defect did not reproduce");
        }
        SandboxTaskEvent decoded = JsonMapper.builder().findAndAddModules().build()
                .readValue(line, SandboxTaskEvent.class);
        if (decoded.result() == null || decoded.result().getCaseNumber() != 1
                || !"AC".equals(decoded.result().getStatus())) {
            throw new IllegalStateException("module-aware mapper did not preserve result");
        }
        System.out.println("moduleAwareDecodedCase=1;status=AC;productionAcceptance=false");
    }
}
