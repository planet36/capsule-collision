// SPDX-FileCopyrightText: Steven Ward
// SPDX-License-Identifier: MPL-2.0

package geom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks {@link Capsule#contains} and {@link Capsule#intersects} against
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
 * <p>Either kind of line may instead expect {@code INVALID}, which asserts that
 * the shapes it describes are illegal and that a constructor rejects them. Such
 * a line runs no query, and is how the validation rules are tested: that
 * neither radius may be negative, NaN, or infinite, and that no coordinate may
 * be NaN or infinite either.
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
     *
     * <p>The shapes are held as their raw parts rather than as a {@link Capsule}
     * and a {@link Sphere}, because a case may assert that those constructors
     * reject what is on the line. Building them is therefore something the
     * runner does per case, so that a rejection can be a result rather than a
     * failure to parse.
     */
    private record TestCase(
            int lineNumber, String sourceLine, Query query,
            Vec3 p1, Vec3 p2, double radius, Vec3 point, double sphereRadius,
            Expected expected) {

        /** @throws IllegalArgumentException if this line's capsule is illegal */
        Capsule capsule() {
            return new Capsule(p1, p2, radius);
        }

        /** @throws IllegalArgumentException if this line's sphere is illegal */
        Sphere sphere() {
            return new Sphere(point, sphereRadius);
        }
    }

    /** What a case asserts about the line it is on. */
    private enum Expected {
        /** The query is true: the point is in, or the sphere hits. */
        TRUE,
        /** The query is false: the point is out, or the sphere misses. */
        FALSE,
        /**
         * At least one shape on the line is illegal and must be rejected by its
         * constructor. This is the only expectation that runs no query.
         */
        INVALID;

        static Expected parse(String field) {
            return switch (field.toUpperCase()) {
                case "IN", "HIT", "TRUE", "YES" -> TRUE;
                case "OUT", "MISS", "FALSE", "NO" -> FALSE;
                case "INVALID", "THROWS" -> INVALID;
                default -> throw new ParseException("unknown expected result \"" + field
                        + "\", expected IN or OUT for a point, HIT or MISS for a sphere,"
                        + " or INVALID for a shape that must be rejected");
            };
        }
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

        boolean test(Capsule capsule, Sphere sphere) {
            return switch (this) {
                case POINT -> capsule.contains(sphere.center());
                case SPHERE -> capsule.intersects(sphere);
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
            if (c.expected() == Expected.INVALID) {
                mismatches += checkRejected(file, c);
                continue;
            }

            Capsule capsule;
            Sphere sphere;
            try {
                capsule = c.capsule();
                sphere = c.sphere();
            } catch (IllegalArgumentException e) {
                System.out.printf("FAIL %s:%d: shape rejected as illegal: %s%n    %s%n",
                        file, c.lineNumber(), e.getMessage(), c.sourceLine().strip());
                mismatches++;
                continue;
            }

            boolean expected = c.expected() == Expected.TRUE;
            boolean actual = c.query().test(capsule, sphere);
            if (actual != expected) {
                System.out.printf("FAIL %s:%d: expected %s, got %s%n    %s%n",
                        file, c.lineNumber(), describe(c.query(), expected),
                        describe(c.query(), actual), c.sourceLine().strip());
                mismatches++;
            }
            violations += checkInvariants(file, c, capsule, sphere);
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
     * Checks that an {@code INVALID} case really is rejected, and returns 1 if
     * it was accepted instead.
     *
     * <p>A line names both a capsule and a sphere and either may be the illegal
     * one, so the case passes when either constructor throws. Keep the shape
     * that is not under test obviously legal, or a case can pass for the wrong
     * reason.
     */
    private static int checkRejected(Path file, TestCase c) {
        try {
            c.capsule();
            c.sphere();
        } catch (IllegalArgumentException e) {
            return 0;
        }
        System.out.printf("FAIL %s:%d: expected an illegal shape, but both were accepted%n"
                + "    %s%n", file, c.lineNumber(), c.sourceLine().strip());
        return 1;
    }

    /**
     * Checks the relationships that must hold between the methods for this
     * case's geometry no matter what its expected answer is, and returns the
     * number that do not.
     */
    private static int checkInvariants(Path file, TestCase c, Capsule capsule, Sphere sphere) {
        Vec3 q = sphere.center();
        double r = sphere.radius();

        boolean contains = capsule.contains(q);
        boolean hits = capsule.intersects(sphere);
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
        violations += check(file, c, capsule.intersects(new Sphere(q, 0.0)) == contains,
                "a zero radius sphere disagrees with the point test");
        // Growing a sphere that already touches cannot make it miss.
        violations += check(file, c, !hits || capsule.intersects(new Sphere(q, r + 1.0)),
                "growing a sphere that hits makes it miss");
        // A contained point is at distance zero. This is exact: containment
        // means the squared distance is at most radius squared, and taking the
        // root of both sides preserves that, since IEEE arithmetic recovers a
        // value exactly from the root of its own square.
        violations += check(file, c, !contains || capsule.distanceTo(q) == 0.0,
                "a contained point has a nonzero distance to the capsule");
        // distanceTo() is the segment distance less the radius, floored at
        // zero. Asserting the definition catches a reformulation that changes
        // the result, and does so without a tolerance.
        //
        // There is deliberately no invariant of the form "a sphere of exactly
        // the measured distance must hit". That crosses the square root in the
        // opposite direction, and no fixed slack makes it hold: the error to be
        // absorbed scales with radius plus distance, not with distance, so for
        // a fat capsule near the query point it is unbounded in ulps of the
        // distance. It is a claim about floating point round tripping rather
        // than about geometry, and the squared invariants above already pin the
        // behavior it was reaching for.
        violations += check(file, c,
                capsule.distanceTo(q)
                        == Math.max(0.0, Math.sqrt(segmentSquared) - capsuleRadius),
                "distanceTo disagrees with the segment distance less the radius");

        // The bounding box is the segment's own box with every face pushed out
        // by the radius. Asserting the definition catches a reformulation that
        // changes the result, including the two obvious ways to get it wrong:
        // omitting the radius, or transposing min and max.
        //
        // There is deliberately no invariant of the form "a contained point
        // lies inside the box". It reads like the one property a bound must
        // have, and it is true of the capsule as a solid, but it is not true of
        // contains(), which is not an exact oracle at its own boundary: its
        // squared comparison accepts points a few ulps outside the true
        // surface, and some of those fall outside the box. Fuzzing puts them
        // within three ulps of a face — never at the computed extreme, never at
        // a random point — so the assertion would pass here and fail later.
        // That is a claim about two roundings agreeing, not about geometry, and
        // no fixed slack fixes it. See Capsule.maxCorner.
        Vec3 lo = capsule.minCorner();
        Vec3 hi = capsule.maxCorner();
        Vec3 p1 = capsule.p1();
        Vec3 p2 = capsule.p2();
        violations += check(file, c,
                lo.equals(new Vec3(Math.min(p1.x(), p2.x()) - capsuleRadius,
                                   Math.min(p1.y(), p2.y()) - capsuleRadius,
                                   Math.min(p1.z(), p2.z()) - capsuleRadius))
                        && hi.equals(new Vec3(Math.max(p1.x(), p2.x()) + capsuleRadius,
                                              Math.max(p1.y(), p2.y()) + capsuleRadius,
                                              Math.max(p1.z(), p2.z()) + capsuleRadius)),
                "the bounding box is not the segment's box grown by the radius");
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
        // A point case is a sphere case of radius zero, which is what it is.
        double sphereRadius = query == Query.SPHERE ? number(fields[11]) : 0.0;
        Expected expected = Expected.parse(fields[fields.length - 1]);

        // The shapes are deliberately not built here. Whether a constructor
        // accepts them is what an INVALID case is asking about, so it belongs
        // with the results and not with the parse errors.
        return new TestCase(lineNumber, sourceLine, query, p1, p2, radius, point,
                sphereRadius, expected);
    }

    private static Query parseQuery(String field) {
        try {
            return Query.valueOf(field.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ParseException("unknown query \"" + field + "\", expected one of "
                    + Arrays.toString(Query.values()));
        }
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
