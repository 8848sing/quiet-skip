package com.jingjump.app;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class SelectorAssetTest {
    @Test
    public void everyBuiltInSelectorCompiles() throws Exception {
        Path path = Paths.get("src", "main", "assets", "ad_selectors.txt");
        if (!Files.exists(path)) {
            path = Paths.get("app", "src", "main", "assets", "ad_selectors.txt");
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) {
                continue;
            }
            int separator = value.indexOf('|');
            assertNotNull("Missing package separator: " + value, separator > 0 ? value : null);
            String selector = value.substring(separator + 1);
            assertNotNull("Invalid selector: " + selector, SelectorEngine.compile(selector));
        }
    }
}
