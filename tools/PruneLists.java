import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One-shot tool: prune anti-AD full blocklist (drop domains whose strict
 *  ancestor is already covered by full ∪ builtin) and rewrite the asset. */
public class PruneLists {
    public static void main(String[] args) throws Exception {
        Path assets = Paths.get(args[0]);
        List<String> builtin = readDomains(assets.resolve("ad_domains.txt"));
        List<String> full = readDomains(assets.resolve("full_blocklist.txt"));

        Set<String> universe = new HashSet<>();
        universe.addAll(full);
        // NOTE: builtin must NOT cover full entries here: the extended list is
        // togglable at runtime, so asset-level pruning is self-contained only.
        // Cross-source pruning happens in DnsRuleStore.load with live sets.

        List<String> kept = new ArrayList<>(full.size());
        int dropped = 0;
        for (String d : full) {
            int dot = d.indexOf('.');
            boolean covered = false;
            while (dot >= 0) {
                String ancestor = d.substring(dot + 1);
                if (universe.contains(ancestor)) { covered = true; break; }
                dot = d.indexOf('.', dot + 1);
            }
            if (covered) { dropped++; } else { kept.add(d); }
        }

        List<String> keptBuiltin = new ArrayList<>(builtin.size());
        int droppedBuiltin = 0;
        Set<String> builtinSet = new HashSet<>(builtin);
        // Self-only: the togglable extended list must never cover a builtin
        // entry at asset level, otherwise switching it off would leak the
        // pruned children. Cross-source pruning stays in DnsRuleStore.load.
        for (String d : builtin) {
            int dot = d.indexOf('.');
            boolean covered = false;
            while (dot >= 0) {
                String ancestor = d.substring(dot + 1);
                if (builtinSet.contains(ancestor)) {
                    covered = true; break;
                }
                dot = d.indexOf('.', dot + 1);
            }
            if (covered) { droppedBuiltin++; } else { keptBuiltin.add(d); }
        }

        System.out.println("full: " + full.size() + " -> " + kept.size()
                + " (dropped " + dropped + ")");
        System.out.println("builtin: " + builtin.size() + " -> " + keptBuiltin.size()
                + " (dropped " + droppedBuiltin + ")");

        List<String> header = Arrays.asList(
                "! 静跳 extended DNS blocklist",
                "! Source: anti-AD (https://github.com/privacy-protection-tools/anti-AD), MIT License",
                "! License text: assets/LICENSE-anti-ad.txt",
                "! Format: plain domains, one per line; a parent entry also covers subdomains.",
                "! Pruned 2026-10-28: entries covered by an ancestor rule were removed.");
        List<String> sorted = new ArrayList<>(kept);
        Collections.sort(sorted);
        sorted.addAll(0, header);
        Files.write(assets.resolve("full_blocklist.txt"), sorted, StandardCharsets.UTF_8);

        List<String> builtinHeader = Collections.singletonList(
                "# Conservative ad-network endpoints. A domain entry also covers its subdomains.");
        List<String> sortedBuiltin = new ArrayList<>(keptBuiltin);
        Collections.sort(sortedBuiltin);
        sortedBuiltin.add(0, builtinHeader.get(0));
        Files.write(assets.resolve("ad_domains.txt"), sortedBuiltin, StandardCharsets.UTF_8);
        System.out.println("rewrote assets");
    }

    static List<String> readDomains(Path path) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String v = line.trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty() || v.startsWith("!") || v.startsWith("#")) { continue; }
            if (v.matches("[a-z0-9.-]+")) { out.add(v); }
        }
        return out;
    }
}
