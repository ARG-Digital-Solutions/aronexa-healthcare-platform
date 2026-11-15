package com.argdigital.aronexa.appointment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.annotation.Testable;

import static org.assertj.core.api.Assertions.assertThat;

/** Emits a sanitized JUnit method inventory for the matrix coordinator. */
class Win11pTestInventoryTests {
    private record TestMethod(String id, List<String> tags) {}

    @Test
    @Tag("win11p-discovery")
    void writeDiscoveredTestMethodInventory() throws IOException {
        Path root = Path.of("target", "test-classes", "com", "argdigital", "aronexa")
                .toAbsolutePath().normalize();
        assertThat(Files.isDirectory(root)).as("compiled backend test classes").isTrue();
        List<TestMethod> methods = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path classFile : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().contains("$"))
                    .sorted().toList()) {
                String simple = classFile.getFileName().toString().replaceFirst("\\.class$", "");
                if (!(simple.startsWith("Test") || simple.endsWith("Test")
                        || simple.endsWith("Tests") || simple.endsWith("TestCase"))) continue;
                String relative = root.relativize(classFile).toString()
                        .replace(classFile.getFileSystem().getSeparator(), ".");
                String className = "com.argdigital.aronexa." + relative.replaceFirst("\\.class$", "");
                Class<?> testClass;
                try {
                    testClass = Class.forName(className, false,
                            Thread.currentThread().getContextClassLoader());
                } catch (LinkageError | ClassNotFoundException failure) {
                    throw new IllegalStateException("Cannot load discovered test class: " + className, failure);
                }
                for (var method : testClass.getDeclaredMethods()) {
                    boolean testMethod = java.util.Arrays.stream(method.getDeclaredAnnotations())
                            .anyMatch(annotation -> annotation.annotationType().isAnnotationPresent(Testable.class));
                    if (!testMethod) continue;
                    List<String> tags = java.util.Arrays.stream(method.getAnnotationsByType(Tag.class))
                            .map(Tag::value).sorted().toList();
                    methods.add(new TestMethod(className + "#" + method.getName(), tags));
                }
            }
        }
        methods.sort(Comparator.comparing(TestMethod::id));
        assertThat(methods).as("discovered JUnit test methods").isNotEmpty();
        for (int i = 1; i < methods.size(); i++) {
            assertThat(methods.get(i).id()).as("unique test-method identity")
                    .isNotEqualTo(methods.get(i - 1).id());
        }
        String sha = System.getProperty("win11p.source.sha", "");
        if (!sha.isEmpty()) assertThat(sha).matches("[0-9a-f]{40}");
        StringBuilder json = new StringBuilder();
        json.append("{\"schema_version\":1,\"source_sha\":\"")
                .append(escape(sha)).append("\",\"methods\":[");
        for (int i = 0; i < methods.size(); i++) {
            if (i > 0) json.append(',');
            TestMethod method = methods.get(i);
            json.append("{\"id\":\"").append(escape(method.id())).append("\",\"tags\":[");
            for (int j = 0; j < method.tags().size(); j++) {
                if (j > 0) json.append(',');
                json.append("\"").append(escape(method.tags().get(j))).append("\"");
            }
            json.append("]}");
        }
        json.append("]}");
        Path target = Path.of("target").toAbsolutePath().normalize();
        Path output = target.resolve("win11p-test-method-inventory.json").normalize();
        assertThat(output.startsWith(target)).as("inventory remains under Maven target").isTrue();
        Files.writeString(output, json.toString(), StandardCharsets.UTF_8);
        System.out.println("WIN11P_TEST_METHOD_INVENTORY count=" + methods.size());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}