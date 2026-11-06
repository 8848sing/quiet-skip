package com.jingjump.app;

import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in and per-app GKD-style selectors.
 *
 * <p>1.9.0 compiles the built-in selector table once instead of looking every
 * rule up in a synchronized cache on every scan, and only flattens the node
 * tree when there is at least one rule that can apply to the foreground app.</p>
 */
final class GkdRuleSet {
    private static final Map<String, SelectorEngine.Selector> CACHE = new HashMap<>();
    private static volatile List<CompiledRule> builtInRules;

    private GkdRuleSet() {
    }

    static AccessibilityNodeInfo findFirst(
            Context context,
            AccessibilityNodeInfo root,
            String packageName,
            List<String> customSelectors
    ) {
        if (root == null) {
            return null;
        }
        List<CompiledRule> rules = builtIns(context);
        List<SelectorEngine.Selector> applicable = new ArrayList<>(customSelectors.size() + 4);
        for (String source : customSelectors) {
            SelectorEngine.Selector selector = compiled(source);
            if (selector != null) {
                applicable.add(selector);
            }
        }
        for (CompiledRule rule : rules) {
            if (rule.appliesTo(packageName)) {
                applicable.add(rule.selector);
            }
        }
        if (applicable.isEmpty()) {
            return null;
        }
        AccessibilitySelectorNode wrappedRoot = AccessibilitySelectorNode.root(root);
        List<SelectorEngine.Node> nodes = SelectorEngine.Selector.flatten(wrappedRoot);
        for (SelectorEngine.Selector selector : applicable) {
            AccessibilityNodeInfo hit = match(selector, nodes);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo match(
            SelectorEngine.Selector selector,
            List<SelectorEngine.Node> nodes
    ) {
        if (selector == null) {
            return null;
        }
        try {
            SelectorEngine.Node node = selector.findFirst(nodes);
            return node instanceof AccessibilitySelectorNode
                    ? ((AccessibilitySelectorNode) node).original()
                    : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static SelectorEngine.Selector compiled(String source) {
        if (source == null || source.trim().isEmpty()) {
            return null;
        }
        String value = source.trim();
        synchronized (CACHE) {
            if (CACHE.containsKey(value)) {
                return CACHE.get(value);
            }
        }
        SelectorEngine.Selector compiled;
        try {
            compiled = SelectorEngine.compile(value);
        } catch (Throwable ignored) {
            compiled = null;
        }
        synchronized (CACHE) {
            CACHE.put(value, compiled);
        }
        return compiled;
    }

    private static List<CompiledRule> builtIns(Context context) {
        List<CompiledRule> current = builtInRules;
        if (current != null) {
            return current;
        }
        synchronized (GkdRuleSet.class) {
            current = builtInRules;
            if (current != null) {
                return current;
            }
            List<CompiledRule> loaded = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open("ad_selectors.txt"),
                    StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = line.trim();
                    if (value.isEmpty() || value.startsWith("#")) {
                        continue;
                    }
                    int separator = value.indexOf('|');
                    if (separator <= 0 || separator + 1 >= value.length()) {
                        continue;
                    }
                    SelectorEngine.Selector selector =
                            compiled(value.substring(separator + 1).trim());
                    if (selector == null) {
                        continue;
                    }
                    loaded.add(new CompiledRule(
                            value.substring(0, separator).trim(),
                            selector
                    ));
                }
            } catch (Throwable ignored) {
                // An unreadable asset simply disables the built-in selectors.
            }
            builtInRules = Collections.unmodifiableList(loaded);
            return builtInRules;
        }
    }

    private static final class CompiledRule {
        final String packageName;
        final SelectorEngine.Selector selector;

        CompiledRule(String packageName, SelectorEngine.Selector selector) {
            this.packageName = packageName;
            this.selector = selector;
        }

        boolean appliesTo(String packageName) {
            return "*".equals(this.packageName) || this.packageName.equals(packageName);
        }
    }
}