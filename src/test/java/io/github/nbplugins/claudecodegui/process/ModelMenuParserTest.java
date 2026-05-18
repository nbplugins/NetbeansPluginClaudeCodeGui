package io.github.nbplugins.claudecodegui.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixture-based parameterized tests for {@link ModelMenuParser}.
 *
 * <p>Each test case is defined by a pair of resource files under
 * {@code io/github/nbplugins/claudecodegui/process/model-menu-parser/}:
 * <ul>
 *   <li>{@code <case>.src.txt} — the rendered terminal screen lines</li>
 *   <li>{@code <case>.expected.json} — expected result; absent = expect empty models</li>
 * </ul>
 */
class ModelMenuParserTest {

    private static final String RESOURCE_DIR =
            "io/github/nbplugins/claudecodegui/process/model-menu-parser";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<Object[]> testCases() throws Exception {
        URL dirUrl = ModelMenuParserTest.class.getClassLoader().getResource(RESOURCE_DIR);
        assertNotNull(dirUrl, "Resource directory not found: " + RESOURCE_DIR);
        File dir = new File(dirUrl.toURI());
        List<Object[]> cases = new ArrayList<>();
        File[] srcFiles = dir.listFiles((d, name) -> name.endsWith(".src.txt"));
        assertNotNull(srcFiles);
        for (File src : srcFiles) {
            String caseName = src.getName().replace(".src.txt", "");
            Path expectedPath = src.toPath().resolveSibling(caseName + ".expected.json");
            cases.add(new Object[]{
                    caseName,
                    src.toPath(),
                    Files.exists(expectedPath) ? expectedPath : null
            });
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    void parse(String caseName, Path srcFile, Path expectedFile) throws Exception {
        List<String> lines = Files.readAllLines(srcFile);
        ModelMenuParser.ModelDiscovery result = new ModelMenuParser().parse(lines);

        if (expectedFile == null) {
            assertTrue(result.models().isEmpty(),
                    "Expected empty models for case '" + caseName + "' but got: " + result.models());
            assertEquals(-1, result.currentIndex(),
                    "Expected currentIndex -1 for case '" + caseName + "'");
        } else {
            JsonNode expected = MAPPER.readTree(expectedFile.toFile());
            List<String> expectedModels = new ArrayList<>();
            expected.get("models").forEach(n -> expectedModels.add(n.asText()));
            int expectedIndex = expected.get("currentIndex").asInt();

            assertEquals(expectedModels, result.models(),
                    "models mismatch for case '" + caseName + "'");
            assertEquals(expectedIndex, result.currentIndex(),
                    "currentIndex mismatch for case '" + caseName + "'");
        }
    }
}
