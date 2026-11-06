package com.jingjump.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A small, independent implementation of the commonly used GKD selector subset.
 * It deliberately does not include computed expressions or context traversal.
 */
final class SelectorEngine {
    private SelectorEngine() {
    }

    interface Node {
        String name();

        String id();

        String vid();

        String text();

        String desc();

        boolean clickable();

        boolean focusable();

        boolean checkable();

        boolean checked();

        boolean editable();

        boolean longClickable();

        boolean visibleToUser();

        int left();

        int top();

        int right();

        int bottom();

        int width();

        int height();

        int childCount();

        int index();

        int depth();

        Node parent();

        Node child(int index);
    }

    static Selector compile(String source) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("Selector is empty");
        }
        List<String> tokens = tokenize(source.trim());
        if (tokens.isEmpty() || tokens.size() % 2 == 0) {
            throw new IllegalArgumentException("Selector terms are incomplete");
        }
        List<PropertyPart> parts = new ArrayList<>();
        List<Relation> relations = new ArrayList<>();
        int target = -1;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (i % 2 == 0) {
                PropertyPart part = parseProperty(token);
                if (part.target) {
                    if (target >= 0) {
                        throw new IllegalArgumentException("Only one @ target is allowed");
                    }
                    target = parts.size();
                }
                parts.add(part);
            } else {
                relations.add(parseRelation(token));
            }
        }
        if (target < 0) {
            target = parts.size() - 1;
        }
        return new Selector(parts, relations, target);
    }

    private static List<String> tokenize(String source) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;
        int parenthesisDepth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (quote != 0) {
                current.append(value);
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == quote) {
                    quote = 0;
                }
                continue;
            }
            if (value == '\'' || value == '"' || value == '`') {
                quote = value;
                current.append(value);
            } else if (value == '[') {
                bracketDepth++;
                current.append(value);
            } else if (value == ']') {
                bracketDepth--;
                current.append(value);
            } else if (value == '(') {
                parenthesisDepth++;
                current.append(value);
            } else if (value == ')') {
                parenthesisDepth--;
                current.append(value);
            } else if (Character.isWhitespace(value)
                    && bracketDepth == 0
                    && parenthesisDepth == 0) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(value);
            }
            if (bracketDepth < 0 || parenthesisDepth < 0) {
                throw new IllegalArgumentException("Unbalanced selector brackets");
            }
        }
        if (quote != 0 || bracketDepth != 0 || parenthesisDepth != 0) {
            throw new IllegalArgumentException("Unbalanced selector");
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private static PropertyPart parseProperty(String token) {
        String value = token.trim();
        boolean target = value.startsWith("@");
        if (target) {
            value = value.substring(1);
        }
        int firstBracket = value.indexOf('[');
        String className;
        String filters;
        if (firstBracket < 0) {
            className = value;
            filters = "";
        } else {
            className = value.substring(0, firstBracket);
            filters = value.substring(firstBracket);
        }
        className = className.trim();
        if (className.contains(" ") || className.contains("]")) {
            throw new IllegalArgumentException("Invalid class selector");
        }
        List<Condition> conditions = new ArrayList<>();
        int index = 0;
        while (index < filters.length()) {
            if (filters.charAt(index) != '[') {
                throw new IllegalArgumentException("Expected property selector");
            }
            int end = findClosing(filters, index, '[', ']');
            String expression = filters.substring(index + 1, end).trim();
            if (expression.isEmpty()) {
                throw new IllegalArgumentException("Empty property selector");
            }
            conditions.add(parseCondition(expression));
            index = end + 1;
        }
        return new PropertyPart(className, conditions, target);
    }

    private static Relation parseRelation(String rawToken) {
        String token = rawToken.trim();
        RelationType type;
        String suffix;
        if (token.startsWith("<<")) {
            type = RelationType.DESCENDANT;
            suffix = token.substring(2);
        } else if (token.startsWith(">")) {
            type = RelationType.ANCESTOR;
            suffix = token.substring(1);
        } else if (token.startsWith("<")) {
            type = RelationType.CHILD;
            suffix = token.substring(1);
        } else if (token.startsWith("+")) {
            type = RelationType.PREVIOUS_SIBLING;
            suffix = token.substring(1);
        } else if (token.startsWith("-")) {
            type = RelationType.NEXT_SIBLING;
            suffix = token.substring(1);
        } else {
            throw new IllegalArgumentException("Unsupported relation: " + token);
        }
        if (suffix.isEmpty()) {
            return new Relation(type, 0, false);
        }
        if ("n".equals(suffix)) {
            return new Relation(type, 0, true);
        }
        if (suffix.matches("\\d+")) {
            int value = Integer.parseInt(suffix);
            if (value < 2) {
                throw new IllegalArgumentException("Relation offset must be >= 2");
            }
            return new Relation(type, value - 1, false);
        }
        throw new IllegalArgumentException("Invalid relation offset: " + suffix);
    }

    private static Condition parseCondition(String source) {
        return new Condition(source.trim());
    }

    private static int findClosing(String source, int start, char open, char close) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = start; i < source.length(); i++) {
            char value = source.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == quote) {
                    quote = 0;
                }
                continue;
            }
            if (value == '\'' || value == '"' || value == '`') {
                quote = value;
            } else if (value == open) {
                depth++;
            } else if (value == close && --depth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unclosed selector bracket");
    }

    static final class Selector {
        private final List<PropertyPart> parts;
        private final List<Relation> relations;
        private final int targetIndex;

        Selector(List<PropertyPart> parts, List<Relation> relations, int targetIndex) {
            this.parts = parts;
            this.relations = relations;
            this.targetIndex = targetIndex;
        }

        Node findFirst(Node root) {
            return findFirst(flatten(root));
        }

        Node findFirst(List<Node> nodes) {
            for (Node node : nodes) {
                if (matchesCandidate(node) && matchLeft(targetIndex, node)
                        && matchRight(targetIndex, node)) {
                    return node;
                }
            }
            return null;
        }

        static List<Node> flatten(Node root) {
            List<Node> nodes = new ArrayList<>();
            if (root == null) {
                return nodes;
            }
            ArrayDeque<Node> queue = new ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty() && nodes.size() < 1800) {
                Node node = queue.removeFirst();
                nodes.add(node);
                try {
                    for (int i = 0; i < node.childCount(); i++) {
                        Node child = node.child(i);
                        if (child != null) {
                            queue.addLast(child);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return nodes;
        }

        private boolean matchesCandidate(Node node) {
            try {
                return parts.get(targetIndex).matches(node);
            } catch (Throwable ignored) {
                return false;
            }
        }

        private boolean matchLeft(int index, Node node) {
            if (index == 0) {
                return true;
            }
            Relation relation = relations.get(index - 1);
            List<Node> candidates = traverseLeft(relation, node);
            for (Node candidate : candidates) {
                if (parts.get(index - 1).matches(candidate) && matchLeft(index - 1, candidate)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchRight(int index, Node node) {
            if (index == parts.size() - 1) {
                return true;
            }
            Relation relation = relations.get(index);
            List<Node> candidates = traverseRight(relation, node);
            for (Node candidate : candidates) {
                if (parts.get(index + 1).matches(candidate) && matchRight(index + 1, candidate)) {
                    return true;
                }
            }
            return false;
        }

        private static List<Node> traverseLeft(Relation relation, Node node) {
            return switch (relation.type) {
                case ANCESTOR -> ancestors(node, relation);
                case CHILD -> descendants(node, relation);
                case DESCENDANT -> descendants(node, relation);
                case PREVIOUS_SIBLING -> previousSiblings(node, relation);
                case NEXT_SIBLING -> followingSiblings(node, relation);
            };
        }

        private static List<Node> traverseRight(Relation relation, Node node) {
            return switch (relation.type) {
                case ANCESTOR -> descendants(node, relation);
                case CHILD -> ancestors(node, relation);
                case DESCENDANT -> ancestors(node, relation);
                case PREVIOUS_SIBLING -> followingSiblings(node, relation);
                case NEXT_SIBLING -> previousSiblings(node, relation);
            };
        }

        private static List<Node> ancestors(Node node, Relation relation) {
            List<Node> result = new ArrayList<>();
            Node current = node;
            for (int offset = 0; current != null && offset <= 40; offset++) {
                current = current.parent();
                if (current == null || relation.afterMax(offset)) {
                    break;
                }
                if (relation.accepts(offset)) {
                    result.add(current);
                }
            }
            return result;
        }

        private static List<Node> descendants(Node node, Relation relation) {
            if (!relation.any && relation.offset == 0) {
                List<Node> children = directChildren(node);
                return children;
            }
            List<Node> result = new ArrayList<>();
            ArrayDeque<Node> queue = new ArrayDeque<>();
            addChildren(queue, node);
            int offset = 0;
            while (!queue.isEmpty() && offset <= 1800 && !relation.afterMax(offset)) {
                Node current = queue.removeFirst();
                if (relation.accepts(offset)) {
                    result.add(current);
                }
                addChildren(queue, current);
                offset++;
            }
            return result;
        }

        private static List<Node> previousSiblings(Node node, Relation relation) {
            List<Node> result = new ArrayList<>();
            Node parent = node.parent();
            if (parent == null) {
                return result;
            }
            int index = node.index();
            for (int offset = 0; index - offset - 1 >= 0; offset++) {
                if (relation.afterMax(offset)) {
                    break;
                }
                Node candidate = parent.child(index - offset - 1);
                if (candidate != null && relation.accepts(offset)) {
                    result.add(candidate);
                }
            }
            return result;
        }

        private static List<Node> followingSiblings(Node node, Relation relation) {
            List<Node> result = new ArrayList<>();
            Node parent = node.parent();
            if (parent == null) {
                return result;
            }
            int index = node.index();
            for (int offset = 0; index + offset + 1 < parent.childCount(); offset++) {
                if (relation.afterMax(offset)) {
                    break;
                }
                Node candidate = parent.child(index + offset + 1);
                if (candidate != null && relation.accepts(offset)) {
                    result.add(candidate);
                }
            }
            return result;
        }

        private static List<Node> directChildren(Node node) {
            List<Node> result = new ArrayList<>();
            for (int i = 0; i < node.childCount(); i++) {
                Node child = node.child(i);
                if (child != null) {
                    result.add(child);
                }
            }
            return result;
        }

        private static void addChildren(ArrayDeque<Node> queue, Node node) {
            for (int i = 0; i < node.childCount(); i++) {
                Node child = node.child(i);
                if (child != null) {
                    queue.addLast(child);
                }
            }
        }
    }

    private static final class PropertyPart {
        private final String className;
        private final List<Condition> conditions;
        private final boolean target;

        PropertyPart(String className, List<Condition> conditions, boolean target) {
            this.className = className;
            this.conditions = conditions;
            this.target = target;
        }

        boolean matches(Node node) {
            if (!className.isEmpty() && !"*".equals(className)) {
                String name = safe(node.name());
                if (!name.equals(className) && !name.endsWith("." + className)) {
                    return false;
                }
            }
            for (Condition condition : conditions) {
                if (!condition.matches(node)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class Condition {
        private final List<List<AtomicCondition>> orGroups;

        Condition(String source) {
            this.orGroups = new ArrayList<>();
            for (String orPart : splitTopLevel(source, "||")) {
                List<AtomicCondition> andParts = new ArrayList<>();
                for (String andPart : splitTopLevel(orPart, "&&")) {
                    String value = andPart.trim();
                    if (value.startsWith("!(") && value.endsWith(")")) {
                        value = value.substring(2, value.length() - 1);
                        andParts.add(new AtomicCondition(value, true));
                    } else {
                        andParts.add(new AtomicCondition(value, false));
                    }
                }
                orGroups.add(andParts);
            }
        }

        boolean matches(Node node) {
            for (List<AtomicCondition> andGroup : orGroups) {
                boolean valid = true;
                for (AtomicCondition condition : andGroup) {
                    if (!condition.matches(node)) {
                        valid = false;
                        break;
                    }
                }
                if (valid) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class AtomicCondition {
        private static final String[] OPERATORS = {
                "!~=", "!*=", "!^=", "!$=", ">=", "<=", "!=", "~=", "*=", "^=", "$=", "=", ">", "<"
        };

        private final String attribute;
        private final String operator;
        private final Object expected;
        private final boolean negate;
        private final Pattern regex;

        AtomicCondition(String source, boolean negate) {
            this.negate = negate;
            int operatorOffset = -1;
            String matchedOperator = null;
            char quote = 0;
            for (int i = 0; i < source.length(); i++) {
                char value = source.charAt(i);
                if (value == '\'' || value == '"' || value == '`') {
                    if (quote == 0) {
                        quote = value;
                    } else if (quote == value) {
                        quote = 0;
                    }
                } else if (quote == 0) {
                    for (String candidate : OPERATORS) {
                        if (source.startsWith(candidate, i)) {
                            operatorOffset = i;
                            matchedOperator = candidate;
                            break;
                        }
                    }
                    if (operatorOffset >= 0) {
                        break;
                    }
                }
            }
            if (operatorOffset <= 0 || matchedOperator == null) {
                throw new IllegalArgumentException("Unsupported condition: " + source);
            }
            attribute = source.substring(0, operatorOffset).trim();
            operator = matchedOperator;
            expected = parseLiteral(source.substring(operatorOffset + matchedOperator.length()).trim());
            regex = ("~=".equals(operator) || "!~=".equals(operator))
                    ? Pattern.compile(String.valueOf(expected))
                    : null;
        }

        boolean matches(Node node) {
            Object actual = valueOf(node, attribute);
            boolean result = compare(actual);
            return negate ? !result : result;
        }

        private boolean compare(Object actual) {
            if (actual == null && !"=".equals(operator) && !"!=".equals(operator)) {
                return false;
            }
            return switch (operator) {
                case "=" -> Objects.equals(actual, expected);
                case "!=" -> !Objects.equals(actual, expected);
                case "^=" -> string(actual).startsWith(string(expected));
                case "!^=" -> !string(actual).startsWith(string(expected));
                case "*=" -> string(actual).contains(string(expected));
                case "!*=" -> !string(actual).contains(string(expected));
                case "$=" -> string(actual).endsWith(string(expected));
                case "!$=" -> !string(actual).endsWith(string(expected));
                case "~=" -> regex != null && regex.matcher(string(actual)).matches();
                case "!~=" -> regex == null || !regex.matcher(string(actual)).matches();
                case ">" -> actual instanceof Number && expected instanceof Number
                        && ((Number) actual).intValue() > ((Number) expected).intValue();
                case ">=" -> actual instanceof Number && expected instanceof Number
                        && ((Number) actual).intValue() >= ((Number) expected).intValue();
                case "<" -> actual instanceof Number && expected instanceof Number
                        && ((Number) actual).intValue() < ((Number) expected).intValue();
                case "<=" -> actual instanceof Number && expected instanceof Number
                        && ((Number) actual).intValue() <= ((Number) expected).intValue();
                default -> false;
            };
        }

        private static Object valueOf(Node node, String attribute) {
            return switch (attribute) {
                case "name" -> node.name();
                case "name.length" -> safe(node.name()).length();
                case "id" -> node.id();
                case "id.length" -> safe(node.id()).length();
                case "vid" -> node.vid();
                case "vid.length" -> safe(node.vid()).length();
                case "text" -> node.text();
                case "text.length" -> safe(node.text()).length();
                case "desc" -> node.desc();
                case "desc.length" -> safe(node.desc()).length();
                case "clickable" -> node.clickable();
                case "focusable" -> node.focusable();
                case "checkable" -> node.checkable();
                case "checked" -> node.checked();
                case "editable" -> node.editable();
                case "longClickable" -> node.longClickable();
                case "visibleToUser" -> node.visibleToUser();
                case "left" -> node.left();
                case "top" -> node.top();
                case "right" -> node.right();
                case "bottom" -> node.bottom();
                case "width" -> node.width();
                case "height" -> node.height();
                case "childCount" -> node.childCount();
                case "index" -> node.index();
                case "depth" -> node.depth();
                default -> null;
            };
        }

        private static Object parseLiteral(String raw) {
            String value = raw.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Missing comparison value");
            }
            if ("null".equals(value)) {
                return null;
            }
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            if (value.matches("-?\\d+")) {
                return Integer.parseInt(value);
            }
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))
                    || (value.startsWith("`") && value.endsWith("`"))) {
                return unescape(value.substring(1, value.length() - 1));
            }
            throw new IllegalArgumentException("Only literal values are supported: " + value);
        }

        private static String unescape(String value) {
            StringBuilder result = new StringBuilder(value.length());
            boolean escaped = false;
            for (int i = 0; i < value.length(); i++) {
                char current = value.charAt(i);
                if (!escaped) {
                    if (current == '\\') {
                        escaped = true;
                    } else {
                        result.append(current);
                    }
                    continue;
                }
                escaped = false;
                switch (current) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case '\\' -> result.append('\\');
                    case '\'' -> result.append('\'');
                    case '"' -> result.append('"');
                    case '`' -> result.append('`');
                    case 'u' -> {
                        if (i + 4 >= value.length()) {
                            throw new IllegalArgumentException("Invalid unicode escape");
                        }
                        result.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    case 'x' -> {
                        if (i + 2 >= value.length()) {
                            throw new IllegalArgumentException("Invalid hex escape");
                        }
                        result.append((char) Integer.parseInt(value.substring(i + 1, i + 3), 16));
                        i += 2;
                    }
                    default -> result.append(current);
                }
            }
            return result.toString();
        }

        private static String string(Object value) {
            return value == null ? "" : String.valueOf(value);
        }
    }

    private static List<String> splitTopLevel(String source, String delimiter) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        int start = 0;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == quote) {
                    quote = 0;
                }
                continue;
            }
            if (value == '\'' || value == '"' || value == '`') {
                quote = value;
            } else if (value == '(') {
                depth++;
            } else if (value == ')') {
                depth--;
            } else if (depth == 0 && source.startsWith(delimiter, i)) {
                result.add(source.substring(start, i));
                start = i + delimiter.length();
                i += delimiter.length() - 1;
            }
        }
        result.add(source.substring(start));
        return result;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private enum RelationType {
        ANCESTOR,
        CHILD,
        DESCENDANT,
        PREVIOUS_SIBLING,
        NEXT_SIBLING
    }

    private static final class Relation {
        final RelationType type;
        final int offset;
        final boolean any;

        Relation(RelationType type, int offset, boolean any) {
            this.type = type;
            this.offset = offset;
            this.any = any;
        }

        boolean accepts(int candidateOffset) {
            return any || candidateOffset == offset;
        }

        boolean afterMax(int candidateOffset) {
            return !any && candidateOffset > offset;
        }
    }
}
