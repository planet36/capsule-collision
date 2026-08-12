package geom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
 * end of the line, and blank lines are ignored. Every other line is one case,
 * of whitespace separated fields whose count depends on the leading keyword:
 *
 * <pre>
 *   CYLINDER         x1 y1 z1  x2 y2 z2  radius  qx qy qz                expected
 *   CAPSULE          x1 y1 z1  x2 y2 z2  radius  qx qy qz                expected
 *   CYLINDER_SPHERE  x1 y1 z1  x2 y2 z2  radius  cx cy cz  sphereRadius  expected
 *   CAPSULE_SPHERE   x1 y1 z1  x2 y2 z2  radius  cx cy cz  sphereRadius  expected
 * </pre>
 *
 * <p>The first six numbers are the axis endpoints and the seventh is the
 * shape's radius. The point forms follow with the point being tested and expect
 * {@code IN} or {@code OUT}; the sphere forms follow with the sphere's center
 * and radius and expect {@code HIT} or {@code MISS}. Keywords are matched
 * without regard to case, the two pairs of result keywords are interchangeable,
 * and {@code TRUE}/{@code FALSE} and {@code YES}/{@code NO} are also accepted.
 *
 * <p>Beyond the declared answers, every case is also checked against the
 * relationships that must hold between the methods for any geometry, such as a
 * capsule containing its cylinder and a point test agreeing with a zero radius
 * sphere. See {@link #checkInvariants}.
 */
public final class ContainmentTestRunner {

    private static final String DEFAULT_CASES_FILE = "test/cases.txt";

    /**
     * One parsed line of the cases file. A point case is represented as a
     * sphere case with a radius of zero, which is exactly what it is.
     */
    private record TestCase(
            int lineNumber, String sourceLine, Shape shape, Cylinder cylinder,
            Vec3 point, double sphereRadius, boolean expected) {
    }

    private enum Shape {
        CYLINDER(12),
        CAPSULE(12),
        CYLINDER_SPHERE(13),
        CAPSULE_SPHERE(13);

        /** Number of whitespace separated fields a case of this shape has. */
        final int fieldCount;

        Shape(int fieldCount) {
            this.fieldCount = fieldCount;
        }

        /** True if this shape's case carries a sphere radius field. */
        boolean isSphere() {
            return this == CYLINDER_SPHERE || this == CAPSULE_SPHERE;
        }

        boolean test(Cylinder cylinder, Vec3 point, double sphereRadius) {
            return switch (this) {
                case CYLINDER -> cylinder.contains(point);
                case CAPSULE -> cylinder.containsAsCapsule(point);
                case CYLINDER_SPHERE -> cylinder.intersectsSphere(point, sphereRadius);
                case CAPSULE_SPHERE -> cylinder.intersectsSphereAsCapsule(point, sphereRadius);
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
            boolean actual = c.shape().test(c.cylinder(), c.point(), c.sphereRadius());
            if (actual != c.expected()) {
                System.out.printf("FAIL %s:%d: expected %s, got %s%n    %s%n",
                        file, c.lineNumber(), describe(c.shape(), c.expected()),
                        describe(c.shape(), actual), c.sourceLine().strip());
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
        Cylinder cyl = c.cylinder();
        Vec3 q = c.point();
        double r = c.sphereRadius();

        boolean inCylinder = cyl.contains(q);
        boolean inCapsule = cyl.containsAsCapsule(q);
        boolean hitsCylinder = cyl.intersectsSphere(q, r);
        boolean hitsCapsule = cyl.intersectsSphereAsCapsule(q, r);

        int violations = 0;
        violations += check(file, c, !inCylinder || inCapsule,
                "the point is in the cylinder but not in the capsule");
        violations += check(file, c, !hitsCylinder || hitsCapsule,
                "the sphere hits the cylinder but not the capsule");
        // A point test is the radius zero case of the corresponding sphere test.
        violations += check(file, c, cyl.intersectsSphere(q, 0.0) == inCylinder,
                "a zero radius sphere disagrees with the point in cylinder test");
        violations += check(file, c, cyl.intersectsSphereAsCapsule(q, 0.0) == inCapsule,
                "a zero radius sphere disagrees with the point in capsule test");
        // Growing a sphere that already touches cannot make it miss.
        violations += check(file, c, !hitsCylinder || cyl.intersectsSphere(q, r + 1.0),
                "growing a sphere that hits the cylinder makes it miss");
        violations += check(file, c, !hitsCapsule || cyl.intersectsSphereAsCapsule(q, r + 1.0),
                "growing a sphere that hits the capsule makes it miss");
        // A sphere reaching at least as far as the measured distance must hit.
        violations += check(file, c, cyl.distanceTo(q) > r || hitsCylinder,
                "the distance to the cylinder is within the sphere radius but the sphere misses");
        violations += check(file, c, cyl.distanceToAsCapsule(q) > r || hitsCapsule,
                "the distance to the capsule is within the sphere radius but the sphere misses");
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
        Shape shape = parseShape(fields[0]);
        if (fields.length != shape.fieldCount) {
            throw new ParseException("expected " + shape.fieldCount + " fields for "
                    + shape + ", found " + fields.length);
        }

        Vec3 p1 = new Vec3(number(fields[1]), number(fields[2]), number(fields[3]));
        Vec3 p2 = new Vec3(number(fields[4]), number(fields[5]), number(fields[6]));
        double radius = number(fields[7]);
        Vec3 point = new Vec3(number(fields[8]), number(fields[9]), number(fields[10]));
        double sphereRadius = shape.isSphere() ? number(fields[11]) : 0.0;
        if (!(sphereRadius >= 0.0) || Double.isInfinite(sphereRadius)) {
            throw new ParseException(
                    "sphere radius must be finite and non-negative: " + sphereRadius);
        }
        boolean expected = parseExpected(fields[fields.length - 1]);

        return new TestCase(lineNumber, sourceLine, shape, new Cylinder(p1, p2, radius),
                point, sphereRadius, expected);
    }

    private static Shape parseShape(String field) {
        try {
            return Shape.valueOf(field.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ParseException("unknown shape \"" + field + "\", expected one of "
                    + Arrays.toString(Shape.values()));
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

    private static String describe(Shape shape, boolean result) {
        if (shape.isSphere()) {
            return result ? "HIT" : "MISS";
        }
        return result ? "IN" : "OUT";
    }
}
