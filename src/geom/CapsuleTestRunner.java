package geom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks {@link Capsule#contains} and {@link Capsule#intersectsSphere} against
 * a file of cases with known answers.
 *
 * <p>Usage: {@code java geom.CapsuleTestRunner [cases-file...]}, defaulting to
 * {@code test/cases.txt}. Exits with status 0 if every case passes and 1
 * otherwise.
 *
 * <p>The file is line oriented. A {@code #} begins a comment that runs to the
 * end of the line, and blank lines are ignored. Every other line is one case,
 * of whitespace separated fields whose count depends on the leading keyword
 * naming what is being tested against the capsule:
 *
 * <pre>
 *   POINT   x1 y1 z1  x2 y2 z2  radius  qx qy qz                expected
 *   SPHERE  x1 y1 z1  x2 y2 z2  radius  cx cy cz  sphereRadius  expected
 * </pre>
 *
 * <p>The first six numbers are the capsule's axis endpoints and the seventh is
 * its radius. A {@code POINT} case follows with the point being tested and
 * expects {@code IN} or {@code OUT}; a {@code SPHERE} case follows with the
 * sphere's center and radius and expects {@code HIT} or {@code MISS}. Keywords
 * are matched without regard to case, the two pairs of result keywords are
 * interchangeable, and {@code TRUE}/{@code FALSE} and {@code YES}/{@code NO}
 * are also accepted.
 *
 * <p>Beyond the declared answers, every case is also checked against the
 * relationships that must hold between the methods for any geometry. See
 * {@link #checkInvariants}.
 */
public final class CapsuleTestRunner {

    private static final String DEFAULT_CASES_FILE = "test/cases.txt";

    /**
     * One parsed line of the cases file. A point case is represented as a
     * sphere case with a radius of zero, which is exactly what it is.
     */
    private record TestCase(
            int lineNumber, String sourceLine, Query query, Capsule capsule,
            Vec3 point, double sphereRadius, boolean expected) {
    }

    /** What is being tested against the capsule. */
    private enum Query {
        POINT(12),
        SPHERE(13);

        /** Number of whitespace separated fields a case of this kind has. */
        final int fieldCount;

        Query(int fieldCount) {
            this.fieldCount = fieldCount;
        }

        boolean test(Capsule capsule, Vec3 point, double sphereRadius) {
            return switch (this) {
                case POINT -> capsule.contains(point);
                case SPHERE -> capsule.intersectsSphere(point, sphereRadius);
            };
        }
    }

    /** Raised for a malformed line, carrying the line number for reporting. */
    private static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    private CapsuleTestRunner() {
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
            boolean actual = c.query().test(c.capsule(), c.point(), c.sphereRadius());
            if (actual != c.expected()) {
                System.out.printf("FAIL %s:%d: expected %s, got %s%n    %s%n",
                        file, c.lineNumber(), describe(c.query(), c.expected()),
                        describe(c.query(), actual), c.sourceLine().strip());
                mismatches++;
            }
            violations += checkInvariants(file, c);
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
     * Checks the relationships that must hold between the methods for this
     * case's geometry no matter what its expected answer is, and returns the
     * number that do not.
     */
    private static int checkInvariants(Path file, TestCase c) {
        Capsule capsule = c.capsule();
        Vec3 q = c.point();
        double r = c.sphereRadius();

        boolean contains = capsule.contains(q);
        boolean hits = capsule.intersectsSphere(q, r);
        double segmentSquared = capsule.distanceSquaredToAxisSegment(q);
        double capsuleRadius = capsule.radius();
        double combined = capsuleRadius + r;

        int violations = 0;
        // Both tests are a squared comparison against the segment distance, so
        // these hold exactly rather than to within a rounding tolerance.
        violations += check(file, c,
                contains == (segmentSquared <= capsuleRadius * capsuleRadius),
                "the point test disagrees with the squared distance to the segment");
        violations += check(file, c, hits == (segmentSquared <= combined * combined),
                "the sphere test disagrees with the squared distance to the segment");
        // A point test is the radius zero case of the sphere test.
        violations += check(file, c, capsule.intersectsSphere(q, 0.0) == contains,
                "a zero radius sphere disagrees with the point test");
        // Growing a sphere that already touches cannot make it miss.
        violations += check(file, c, !hits || capsule.intersectsSphere(q, r + 1.0),
                "growing a sphere that hits makes it miss");
        // A contained point is at distance zero, and a sphere reaching past the
        // measured distance must hit, which rules out the distance being an
        // overestimate. This last one keeps a slack that the squared invariants
        // above do not need: distanceTo() takes a square root while the tests
        // compare squared distances, and at exact tangency the two need not
        // agree on the last bit. That is inherent to relating the two, not
        // something the squared accessor can remove.
        violations += check(file, c, !contains || capsule.distanceTo(q) == 0.0,
                "a contained point has a nonzero distance to the capsule");
        double reach = capsule.distanceTo(q) * (1.0 + 1e-12) + 1e-12;
        violations += check(file, c, capsule.intersectsSphere(q, reach),
                "a sphere reaching past the measured distance misses");
        return violations;
    }

    /** Reports {@code description} if {@code holds} is false, and returns 1 if so. */
    private static int check(Path file, TestCase c, boolean holds, String description) {
        if (holds) {
            return 0;
        }
        System.out.printf("INVARIANT VIOLATION %s:%d: %s%n    %s%n",
                file, c.lineNumber(), description, c.sourceLine().strip());
        return 1;
    }

    private static String stripComment(String line) {
        int comment = line.indexOf('#');
        return comment < 0 ? line : line.substring(0, comment);
    }

    private static TestCase parse(int lineNumber, String sourceLine, String content) {
        String[] fields = content.strip().split("\\s+");
        Query query = parseQuery(fields[0]);
        if (fields.length != query.fieldCount) {
            throw new ParseException("expected " + query.fieldCount + " fields for "
                    + query + ", found " + fields.length);
        }

        Vec3 p1 = new Vec3(number(fields[1]), number(fields[2]), number(fields[3]));
        Vec3 p2 = new Vec3(number(fields[4]), number(fields[5]), number(fields[6]));
        double radius = number(fields[7]);
        Vec3 point = new Vec3(number(fields[8]), number(fields[9]), number(fields[10]));
        double sphereRadius = query == Query.SPHERE ? number(fields[11]) : 0.0;
        if (!(sphereRadius >= 0.0) || Double.isInfinite(sphereRadius)) {
            throw new ParseException(
                    "sphere radius must be finite and non-negative: " + sphereRadius);
        }
        boolean expected = parseExpected(fields[fields.length - 1]);

        return new TestCase(lineNumber, sourceLine, query, new Capsule(p1, p2, radius),
                point, sphereRadius, expected);
    }

    private static Query parseQuery(String field) {
        try {
            return Query.valueOf(field.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ParseException("unknown query \"" + field + "\", expected one of "
                    + Arrays.toString(Query.values()));
        }
    }

    private static boolean parseExpected(String field) {
        return switch (field.toUpperCase()) {
            case "IN", "HIT", "TRUE", "YES" -> true;
            case "OUT", "MISS", "FALSE", "NO" -> false;
            default -> throw new ParseException("unknown expected result \"" + field
                    + "\", expected IN or OUT for a point, HIT or MISS for a sphere");
        };
    }

    private static double number(String field) {
        try {
            return Double.parseDouble(field);
        } catch (NumberFormatException e) {
            throw new ParseException("not a number: \"" + field + "\"");
        }
    }

    private static String describe(Query query, boolean result) {
        if (query == Query.SPHERE) {
            return result ? "HIT" : "MISS";
        }
        return result ? "IN" : "OUT";
    }
}
