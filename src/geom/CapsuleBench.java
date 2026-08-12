// SPDX-FileCopyrightText: Steven Ward
// SPDX-License-Identifier: MPL-2.0

package geom;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Measures the throughput of the query methods on {@link Capsule}.
 *
 * <p>Usage: {@code java geom.CapsuleBench [--distribution=NAME]
 * [--warmup=N] [--measure=N] [--sweeps=N]}. With no arguments every
 * distribution runs.
 *
 * <p>Five methods are timed, abbreviated in the output as follows:
 *
 * <pre>
 *   contains      contains
 *   intersects    intersects
 *   distanceSq    distanceSquaredToAxisSegment
 *   distanceTo    distanceTo
 *   closestPoint  closestPointOnAxisSegment
 * </pre>
 *
 * <p>The last three are here to put numbers on claims the documentation makes.
 * {@code distanceSq} against {@code distanceTo} is the cost of the square root,
 * which is the whole reason the sqrt-free accessor is exposed on the segment
 * and callers who only order or threshold distances are steered to it.
 * {@code closestPoint} is the one query that hands a freshly allocated
 * {@link Vec3} back to the caller, so unlike the others its result genuinely
 * escapes; its sweep stores the point into an array, as a caller who wanted the
 * point would, rather than reading the components and dropping it.
 *
 * <p>The standard tool for this is JMH, which is a third party jar and so is
 * unavailable here. Most of what follows is therefore not measurement but
 * defense against the compiler, which is entitled to delete a benchmark that
 * does not use its results:
 *
 * <ul>
 *   <li>Every query method is pure, so a discarded result lets C2 remove the
 *       call outright. Each sweep accumulates a checksum, returns it, and has
 *       it checked and printed, which makes the work observable.
 *   <li>A loop invariant query would be computed once and hoisted, so each
 *       sweep walks a prebuilt array the compiler cannot see through.
 *   <li>A loop in {@code main} would be compiled on stack and measured in that
 *       form. The timed region is a call to a method that does the whole sweep,
 *       which C2 compiles normally.
 *   <li>{@link System#nanoTime} resolves to tens of nanoseconds, which is the
 *       same order as a single query. One timed iteration is {@code sweeps}
 *       passes over the whole array, which brings it to roughly a millisecond.
 *   <li>Warmup runs on the same data that is measured, so every branch is
 *       profiled before timing starts and no uncommon trap fires mid run.
 * </ul>
 *
 * <p>Results are reported as the minimum and median over the measured
 * iterations rather than the mean, since a garbage collection or a descheduled
 * thread can only ever make an iteration slower.
 *
 * <p>One caveat this cannot design away: by default all distributions run in
 * one JVM and share a single compilation of the sweep loop, so each one's
 * branch profile is blended with the others'. That is representative of a
 * mixed workload but slightly pessimistic for any single distribution. Pass
 * {@code --distribution} to give one of them a fresh JVM to itself.
 */
public final class CapsuleBench {

    /**
     * Queries per sweep. Small enough that the array stays in cache, so the
     * measurement is arithmetic and branch behavior rather than memory
     * bandwidth.
     */
    private static final int QUERY_COUNT = 4096;

    /** Sweeps per timed iteration, chosen to bring one iteration near a millisecond. */
    private static final int DEFAULT_SWEEPS = 128;

    private static final int DEFAULT_WARMUP = 20;
    private static final int DEFAULT_MEASURE = 50;

    /** Fixed so that every run benchmarks the same queries. */
    private static final long SEED = 0x5CA1AB1E;

    /**
     * The capsule under test. Deliberately not axis aligned and not centered on
     * the origin, so that no coordinate is incidentally zero.
     */
    private static final Capsule CAPSULE =
            new Capsule(new Vec3(-3.0, 1.0, 2.0), new Vec3(5.0, 4.0, -1.0), 2.0);

    /**
     * One pass over the queries, repeated {@code sweeps} times, returning a
     * checksum of everything it computed. The checksum is what keeps the work
     * from being optimized away, and the repeat count is what makes one timed
     * region long enough to measure.
     */
    @FunctionalInterface
    private interface Sweep {
        double run(int sweeps);
    }

    /** How to summarize a sweep's checksum for a human. */
    private enum Summary {
        /** The checksum counts true results. */
        HITS,
        /** The checksum sums returned values. */
        MEAN;

        String describe(double oneSweep) {
            return this == HITS
                    ? String.format("%.0f", oneSweep)
                    : String.format("%.4f", oneSweep / QUERY_COUNT);
        }
    }

    /**
     * One named set of queries, holding both the points and the spheres built
     * around them so every method is measured against the same geometry.
     */
    private record QuerySet(String name, Vec3[] points, Sphere[] spheres) {
    }

    /** The timing of one method against one distribution. */
    private record Result(
            String distribution, String method, String summary,
            double minNanosPerOp, double medianNanosPerOp) {
    }

    private CapsuleBench() {
    }

    public static void main(String[] args) {
        String distribution = null;
        int warmup = DEFAULT_WARMUP;
        int measure = DEFAULT_MEASURE;
        int sweeps = DEFAULT_SWEEPS;

        for (String arg : args) {
            if (arg.startsWith("--distribution=")) {
                distribution = value(arg);
            } else if (arg.startsWith("--warmup=")) {
                warmup = positiveInt(arg);
            } else if (arg.startsWith("--measure=")) {
                measure = positiveInt(arg);
            } else if (arg.startsWith("--sweeps=")) {
                sweeps = positiveInt(arg);
            } else {
                System.err.println("unknown argument: " + arg);
                System.err.println("usage: java geom.CapsuleBench [--distribution=NAME] "
                        + "[--warmup=N] [--measure=N] [--sweeps=N]");
                System.exit(2);
            }
        }

        Map<String, QuerySet> sets = buildQuerySets();
        if (distribution != null && !sets.containsKey(distribution)) {
            System.err.println("unknown distribution \"" + distribution + "\", expected one of "
                    + sets.keySet());
            System.exit(2);
        }

        System.out.printf("%s %s%n",
                System.getProperty("java.vm.name"), System.getProperty("java.vm.version"));
        System.out.println(CAPSULE);
        System.out.printf("%d queries per sweep, %d sweeps per iteration, "
                        + "%d warmup + %d measured iterations%n%n",
                QUERY_COUNT, sweeps, warmup, measure);

        System.out.printf("%-12s  %-12s  %10s  %11s  %14s  %9s%n",
                "distribution", "method", "result", "min ns/op", "median ns/op", "Mops/s");
        System.out.printf("%-12s  %-12s  %10s  %11s  %14s  %9s%n",
                "-".repeat(12), "-".repeat(12), "-".repeat(10),
                "-".repeat(11), "-".repeat(14), "-".repeat(9));

        int batch = sweeps;
        for (QuerySet set : sets.values()) {
            if (distribution != null && !distribution.equals(set.name())) {
                continue;
            }
            Capsule capsule = CAPSULE;
            Vec3[] points = set.points();
            Sphere[] spheres = set.spheres();
            // Allocated once, outside the timed region, so that only the
            // stores into it are measured and not its own construction.
            Vec3[] closest = new Vec3[points.length];

            report(measure(set.name(), "contains", Summary.HITS,
                    n -> sweepContains(capsule, points, n), batch, warmup, measure));
            report(measure(set.name(), "intersects", Summary.HITS,
                    n -> sweepIntersects(capsule, spheres, n), batch, warmup, measure));
            report(measure(set.name(), "distanceSq", Summary.MEAN,
                    n -> sweepDistanceSquared(capsule, points, n), batch, warmup, measure));
            report(measure(set.name(), "distanceTo", Summary.MEAN,
                    n -> sweepDistanceTo(capsule, points, n), batch, warmup, measure));
            report(measure(set.name(), "closestPoint", Summary.MEAN,
                    n -> sweepClosestPoint(capsule, points, closest, n), batch, warmup, measure));
        }
    }

    // -----------------------------------------------------------------------
    // The timed sweeps.
    //
    // The returned checksum is what keeps these alive: it is checked and
    // printed, so neither the loop nor the query inside it can be discarded as
    // having no effect. Summing in a fixed order over fixed data reproduces
    // bit for bit, so the check is exact and needs no tolerance.
    // -----------------------------------------------------------------------

    private static double sweepContains(Capsule capsule, Vec3[] points, int sweeps) {
        long count = 0;
        for (int i = 0; i < sweeps; i++) {
            for (Vec3 q : points) {
                if (capsule.contains(q)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static double sweepIntersects(Capsule capsule, Sphere[] spheres, int sweeps) {
        long count = 0;
        for (int i = 0; i < sweeps; i++) {
            for (Sphere s : spheres) {
                if (capsule.intersects(s)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static double sweepDistanceSquared(Capsule capsule, Vec3[] points, int sweeps) {
        double sum = 0.0;
        for (int i = 0; i < sweeps; i++) {
            for (Vec3 q : points) {
                sum += capsule.distanceSquaredToAxisSegment(q);
            }
        }
        return sum;
    }

    private static double sweepDistanceTo(Capsule capsule, Vec3[] points, int sweeps) {
        double sum = 0.0;
        for (int i = 0; i < sweeps; i++) {
            for (Vec3 q : points) {
                sum += capsule.distanceTo(q);
            }
        }
        return sum;
    }

    /**
     * Times {@code closestPointOnAxisSegment} as a caller who wants the point
     * would use it. The store into {@code out} is the reason this row differs
     * from the others: it publishes the returned {@link Vec3} to the heap, so
     * escape analysis cannot scalarize the allocation away as it does inside
     * {@code contains}. Summing one component keeps a checksum without
     * defeating that.
     */
    private static double sweepClosestPoint(
            Capsule capsule, Vec3[] points, Vec3[] out, int sweeps) {
        double sum = 0.0;
        for (int i = 0; i < sweeps; i++) {
            for (int j = 0; j < points.length; j++) {
                Vec3 c = capsule.closestPointOnAxisSegment(points[j]);
                out[j] = c;
                sum += c.x();
            }
        }
        return sum;
    }

    /**
     * Warms up and then times {@code sweep}, returning the per query cost.
     *
     * <p>Every batch is checked against the first one, which catches a compiler
     * that has optimized the work away as well as any nondeterminism in the
     * data. The expected value cannot be derived from a single sweep: summing
     * an array n times is not n times its sum in floating point.
     */
    private static Result measure(String distribution, String method, Summary summary,
            Sweep sweep, int sweeps, int warmup, int measure) {
        String described = summary.describe(sweep.run(1));

        double expected = sweep.run(sweeps);
        for (int i = 0; i < warmup; i++) {
            check(sweep.run(sweeps), expected, distribution, method);
        }

        double[] nanosPerOp = new double[measure];
        for (int i = 0; i < measure; i++) {
            long start = System.nanoTime();
            double checksum = sweep.run(sweeps);
            long elapsed = System.nanoTime() - start;
            check(checksum, expected, distribution, method);
            nanosPerOp[i] = (double) elapsed / ((long) sweeps * QUERY_COUNT);
        }

        Arrays.sort(nanosPerOp);
        return new Result(distribution, method, described,
                nanosPerOp[0], nanosPerOp[nanosPerOp.length / 2]);
    }

    private static void check(
            double checksum, double expected, String distribution, String method) {
        if (Double.doubleToRawLongBits(checksum) != Double.doubleToRawLongBits(expected)) {
            throw new AssertionError(distribution + "/" + method + ": checksum " + checksum
                    + " differs from " + expected + "; the benchmark is not measuring "
                    + "what it claims to");
        }
    }

    private static void report(Result r) {
        System.out.printf("%-12s  %-12s  %10s  %11.3f  %14.3f  %9.1f%n",
                r.distribution(), r.method(), r.summary(),
                r.minNanosPerOp(), r.medianNanosPerOp(), 1000.0 / r.minNanosPerOp());
    }

    // -----------------------------------------------------------------------
    // Query generation.
    //
    // closestPointOnAxisSegment branches three ways, on t <= 0, on t >= 1, and
    // on neither, and contains() then makes a data dependent comparison. A
    // query set that always takes the same path measures a perfectly predicted
    // branch and reports a number no real caller will see, so the branch mix is
    // part of what is being varied here rather than an accident of the data.
    // -----------------------------------------------------------------------

    private static Map<String, QuerySet> buildQuerySets() {
        Random random = new Random(SEED);
        Map<String, QuerySet> sets = new LinkedHashMap<>();
        for (QuerySet set : new QuerySet[] {
                interior(random), caps(random), mixed(random)}) {
            requireMixedOutcomes(set);
            sets.put(set.name(), set);
        }
        return sets;
    }

    /**
     * Projections landing strictly between the endpoints, at a radial offset up
     * to twice the radius, so about half the points are inside.
     */
    private static QuerySet interior(Random random) {
        Vec3 axis = CAPSULE.axis();
        Vec3 u = normalize(axis);
        Vec3 v = normalize(perpendicularTo(u));
        Vec3 w = u.cross(v);
        double radius = CAPSULE.radius();

        Vec3[] points = new Vec3[QUERY_COUNT];
        for (int i = 0; i < points.length; i++) {
            double t = 0.05 + 0.90 * random.nextDouble();
            double offset = 2.0 * radius * random.nextDouble();
            double angle = 2.0 * Math.PI * random.nextDouble();
            points[i] = CAPSULE.p1()
                    .plus(axis.scale(t))
                    .plus(v.scale(offset * Math.cos(angle)))
                    .plus(w.scale(offset * Math.sin(angle)));
        }
        return new QuerySet("interior", points, spheresAround(points, random));
    }

    /**
     * Points in the outward hemisphere beyond each endpoint, whose projection
     * therefore clamps to that endpoint. The offset is measured from the
     * endpoint itself, so as with {@link #interior} about half are inside.
     */
    private static QuerySet caps(Random random) {
        Vec3 axis = CAPSULE.axis();
        double radius = CAPSULE.radius();

        Vec3[] points = new Vec3[QUERY_COUNT];
        for (int i = 0; i < points.length; i++) {
            boolean atP1 = random.nextBoolean();
            Vec3 end = atP1 ? CAPSULE.p1() : CAPSULE.p2();
            // Outward is away from the other endpoint, which is the condition
            // for the projection to clamp rather than land on the segment.
            Vec3 outward = randomDirection(random);
            if ((outward.dot(axis) > 0.0) == atP1) {
                outward = outward.scale(-1.0);
            }
            points[i] = end.plus(outward.scale(2.0 * radius * random.nextDouble()));
        }
        return new QuerySet("caps", points, spheresAround(points, random));
    }

    /**
     * Uniform in the capsule's bounding box grown by the radius, which reaches
     * all three branches and both containment outcomes without correlation
     * between them. Most points miss, as in a real broad phase.
     */
    private static QuerySet mixed(Random random) {
        Vec3 p1 = CAPSULE.p1();
        Vec3 p2 = CAPSULE.p2();
        double margin = 2.0 * CAPSULE.radius();

        Vec3[] points = new Vec3[QUERY_COUNT];
        for (int i = 0; i < points.length; i++) {
            points[i] = new Vec3(
                    between(random, p1.x(), p2.x(), margin),
                    between(random, p1.y(), p2.y(), margin),
                    between(random, p1.z(), p2.z(), margin));
        }
        return new QuerySet("mixed", points, spheresAround(points, random));
    }

    /** Spheres centered on {@code points}, with radii up to the capsule's own. */
    private static Sphere[] spheresAround(Vec3[] points, Random random) {
        Sphere[] spheres = new Sphere[points.length];
        for (int i = 0; i < points.length; i++) {
            spheres[i] = new Sphere(points[i], CAPSULE.radius() * random.nextDouble());
        }
        return spheres;
    }

    /**
     * Fails if a distribution has collapsed to all hits or all misses, which
     * would make its comparison perfectly predicted and its timing a fiction.
     */
    private static void requireMixedOutcomes(QuerySet set) {
        long contained = 0;
        long hit = 0;
        for (int i = 0; i < set.points().length; i++) {
            if (CAPSULE.contains(set.points()[i])) {
                contained++;
            }
            if (CAPSULE.intersects(set.spheres()[i])) {
                hit++;
            }
        }
        int total = set.points().length;
        if (contained == 0 || contained == total || hit == 0 || hit == total) {
            throw new AssertionError("distribution \"" + set.name() + "\" is degenerate: "
                    + contained + "/" + total + " contained, " + hit + "/" + total + " hit");
        }
    }

    private static double between(Random random, double a, double b, double margin) {
        double lo = Math.min(a, b) - margin;
        double hi = Math.max(a, b) + margin;
        return lo + (hi - lo) * random.nextDouble();
    }

    /** Returns a unit vector, uniform over the sphere by rejection. */
    private static Vec3 randomDirection(Random random) {
        while (true) {
            Vec3 v = new Vec3(
                    2.0 * random.nextDouble() - 1.0,
                    2.0 * random.nextDouble() - 1.0,
                    2.0 * random.nextDouble() - 1.0);
            double lengthSquared = v.lengthSquared();
            if (lengthSquared > 1.0e-6 && lengthSquared <= 1.0) {
                return normalize(v);
            }
        }
    }

    /**
     * Returns a vector perpendicular to {@code v}, by crossing it with whichever
     * axis it leans on least and so cannot be parallel to.
     */
    private static Vec3 perpendicularTo(Vec3 v) {
        double x = Math.abs(v.x());
        double y = Math.abs(v.y());
        double z = Math.abs(v.z());
        Vec3 leastAligned;
        if (x <= y && x <= z) {
            leastAligned = new Vec3(1.0, 0.0, 0.0);
        } else if (y <= z) {
            leastAligned = new Vec3(0.0, 1.0, 0.0);
        } else {
            leastAligned = new Vec3(0.0, 0.0, 1.0);
        }
        return v.cross(leastAligned);
    }

    private static Vec3 normalize(Vec3 v) {
        return v.scale(1.0 / v.length());
    }

    private static String value(String arg) {
        return arg.substring(arg.indexOf('=') + 1);
    }

    private static int positiveInt(String arg) {
        int n;
        try {
            n = Integer.parseInt(value(arg));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + arg);
        }
        if (n <= 0) {
            throw new IllegalArgumentException("must be positive: " + arg);
        }
        return n;
    }
}
