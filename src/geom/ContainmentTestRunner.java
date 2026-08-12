package geom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks {@link Cylinder#contains} and {@link Cylinder#containsAsCapsule}
 * against a file of cases with known answers.
 *
 * <p>Usage: {@code java geom.ContainmentTestRunner [cases-file...]}, defaulting
 * to {@code test/cases.txt}. Exits with status 0 if every case passes and 1
 * otherwise.
 *
 * <p>The file is line oriented. A {@code #} begins a comment that runs to the
 * end of the line, and blank lines are ignored. Every other line is one case of
 * twelve whitespace separated fields:
 *
 * <pre>
 *   shape  x1 y1 z1  x2 y2 z2  radius  qx qy qz  expected
 * </pre>
 *
 * where {@code shape} is {@code CYLINDER} or {@code CAPSULE}, the first six
 * numbers are the axis endpoints, the next three are the point being tested,
 * and {@code expected} is {@code IN} or {@code OUT}. Keywords are matched
 * without regard to case, and {@code TRUE}/{@code FALSE} and {@code YES}/
 * {@code NO} are accepted in place of {@code IN}/{@code OUT}.
 *
 * <p>Beyond the declared answers, every case is also checked against the
 * invariant that a capsule contains its cylinder, which holds no matter what
 * the expected answers say.
 */
public final class ContainmentTestRunner {

    private static final String DEFAULT_CASES_FILE = "test/cases.txt";

    /** One parsed line of the cases file. */
    private record TestCase(
            int lineNumber, String sourceLine, Shape shape, Cylinder cylinder,
            Vec3 point, boolean expected) {
    }

    private enum Shape {
        CYLINDER,
        CAPSULE;

        boolean contains(Cylinder cylinder, Vec3 point) {
            return this == CYLINDER
                    ? cylinder.contains(point)
                    : cylinder.containsAsCapsule(point);
        }
    }

    /** Raised for a malformed line, carrying the line number for reporting. */
    private static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    private ContainmentTestRunner() {
    }

    public static void main(String[] args) {
        String[] files = args.length > 0 ? args : new String[] {DEFAULT_CASES_FILE};

        int failures = 0;
        for (String file : files) {
            try {
                failures += run(Path.of(file));
            } catch (IOException e) {
                System.err.println("ERROR: cannot read " + file + ": " + e.getMessage());
                failures++;
            }
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Runs every case in {@code file} and returns the number of failures. */
    private static int run(Path file) throws IOException {
        List<TestCase> cases = new ArrayList<>();
        int parseErrors = 0;

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNumber = i + 1;
            String content = stripComment(line);
            if (content.isBlank()) {
                continue;
            }
            try {
                cases.add(parse(lineNumber, line, content));
            } catch (ParseException | IllegalArgumentException e) {
                System.out.printf("PARSE ERROR %s:%d: %s%n    %s%n",
                        file, lineNumber, e.getMessage(), line.strip());
                parseErrors++;
            }
        }

        int mismatches = 0;
        int violations = 0;
        for (TestCase c : cases) {
            boolean actual = c.shape().contains(c.cylinder(), c.point());
            if (actual != c.expected()) {
                System.out.printf("FAIL %s:%d: expected %s, got %s%n    %s%n",
                        file, c.lineNumber(), describe(c.expected()), describe(actual),
                        c.sourceLine().strip());
                mismatches++;
            }
            violations += checkCapsuleContainsCylinder(file, c);
        }

        System.out.printf("%s: %d case%s, %d passed, %d failed%n",
                file, cases.size(), cases.size() == 1 ? "" : "s",
                cases.size() - mismatches, mismatches);
        if (parseErrors > 0) {
            System.out.printf("%s: %d unparseable line%s%n",
                    file, parseErrors, parseErrors == 1 ? "" : "s");
        }
        if (violations > 0) {
            System.out.printf("%s: %d invariant violation%s%n",
                    file, violations, violations == 1 ? "" : "s");
        }
        return mismatches + parseErrors + violations;
    }

    /**
     * Verifies that the capsule contains the cylinder for this case's geometry,
     * which must hold regardless of the expected answer, and returns 1 if it
     * does not.
     */
    private static int checkCapsuleContainsCylinder(Path file, TestCase c) {
        if (!c.cylinder().contains(c.point()) || c.cylinder().containsAsCapsule(c.point())) {
            return 0;
        }
        System.out.printf(
                "INVARIANT VIOLATION %s:%d: point is in the cylinder but not in the capsule%n"
                        + "    %s%n",
                file, c.lineNumber(), c.sourceLine().strip());
        return 1;
    }

    private static String stripComment(String line) {
        int comment = line.indexOf('#');
        return comment < 0 ? line : line.substring(0, comment);
    }

    private static TestCase parse(int lineNumber, String sourceLine, String content) {
        String[] fields = content.strip().split("\\s+");
        if (fields.length != 12) {
            throw new ParseException("expected 12 fields, found " + fields.length);
        }

        Shape shape = parseShape(fields[0]);
        Vec3 p1 = new Vec3(number(fields[1]), number(fields[2]), number(fields[3]));
        Vec3 p2 = new Vec3(number(fields[4]), number(fields[5]), number(fields[6]));
        double radius = number(fields[7]);
        Vec3 point = new Vec3(number(fields[8]), number(fields[9]), number(fields[10]));
        boolean expected = parseExpected(fields[11]);

        return new TestCase(
                lineNumber, sourceLine, shape, new Cylinder(p1, p2, radius), point, expected);
    }

    private static Shape parseShape(String field) {
        try {
            return Shape.valueOf(field.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ParseException("unknown shape \"" + field + "\", expected CYLINDER or CAPSULE");
        }
    }

    private static boolean parseExpected(String field) {
        return switch (field.toUpperCase()) {
            case "IN", "TRUE", "YES" -> true;
            case "OUT", "FALSE", "NO" -> false;
            default -> throw new ParseException(
                    "unknown expected result \"" + field + "\", expected IN or OUT");
        };
    }

    private static double number(String field) {
        try {
            return Double.parseDouble(field);
        } catch (NumberFormatException e) {
            throw new ParseException("not a number: \"" + field + "\"");
        }
    }

    private static String describe(boolean contained) {
        return contained ? "IN" : "OUT";
    }
}
