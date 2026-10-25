package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

/**
 * D-076 A1/A2 + N-1: branch names are validated with git's own {@code check-ref-format --branch} rules (not a
 * narrow regex), length counted in Unicode CODE POINTS. The vector set is the SHARED
 * {@code scanner-image/branch-vectors.json}, read here AND by the in-image shell parity test
 * ({@code branch_vectors_test.py}), so the Java validator and the {@code secscan-lib.sh} shell validator are
 * proven to agree on exactly the same inputs. Read with a tiny self-contained reader (core has no JSON lib —
 * no new dependency).
 */
class BranchNameValidationTest {

    private record Vector(String name, boolean valid) {
    }

    @TestFactory
    List<DynamicTest> matchesTheSharedVectorSet() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Vector v : loadVectors()) {
            String label = "len=" + v.name().codePointCount(0, v.name().length())
                    + (v.valid() ? " accept " : " reject ") + describe(v.name());
            tests.add(DynamicTest.dynamicTest(label, () ->
                    assertThat(BranchName.isValid(v.name())).as(describe(v.name())).isEqualTo(v.valid())));
        }
        // Sanity: the shared set is non-trivial and covers both verdicts.
        assertThat(tests).hasSizeGreaterThan(40);
        return tests;
    }

    @Test
    void nullAndNulByteAndRequireValid() {
        // Not in the shared file (a JSON string / argv cannot carry NUL), so asserted here directly.
        assertThat(BranchName.isValid(null)).isFalse();
        assertThat(BranchName.isValid("a\u0000b")).isFalse();
        assertThat(BranchName.requireValid("release/1.2")).isEqualTo("release/1.2");
        assertThatThrownBy(() -> BranchName.requireValid("-bad")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void codePointLengthBoundary() {
        assertThat(BranchName.isValid("ü".repeat(255))).isTrue();   // 255 code points
        assertThat(BranchName.isValid("ü".repeat(256))).isFalse();  // 256 code points
    }

    // --- shared vector file (same file the in-image shell parity test reads) ---

    private static List<Vector> loadVectors() {
        Path file = locate();
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file.toAbsolutePath(), e);
        }
        List<Vector> out = new ArrayList<>();
        // The file is machine-generated as an array of {"name": "...", "valid": true|false}. Pull each pair with
        // a line scan; unescape the JSON string ourselves (no JSON lib on the core test classpath).
        String[] lines = json.split("\n");
        String pendingName = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("\"name\":")) {
                int q1 = t.indexOf('"', t.indexOf(':'));
                int q2 = t.lastIndexOf('"');
                pendingName = unescape(t.substring(q1 + 1, q2));
            } else if (t.startsWith("\"valid\":") && pendingName != null) {
                out.add(new Vector(pendingName, t.contains("true")));
                pendingName = null;
            }
        }
        return out;
    }

    private static Path locate() {
        Path dir = Path.of(System.getProperty("user.dir"));
        for (Path base : List.of(dir, dir.getParent() == null ? dir : dir.getParent())) {
            Path p = base.resolve("scanner-image/branch-vectors.json");
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException("branch-vectors.json not found relative to " + dir);
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case '/' -> b.append('/');
                case '\\' -> b.append('\\');
                case '"' -> b.append('"');
                case 'u' -> {
                    b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));  // one UTF-16 unit
                    i += 4;
                }
                default -> b.append(n);
            }
        }
        return b.toString();
    }

    private static String describe(String name) {
        StringBuilder b = new StringBuilder("\"");
        name.codePoints().limit(16).forEach(cp -> {
            if (cp >= 0x20 && cp < 0x7f) {
                b.append((char) cp);
            } else {
                b.append(String.format("\\u%04x", cp));
            }
        });
        return b.append('"').toString();
    }
}
