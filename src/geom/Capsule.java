package geom;

/**
 * A capsule (spherocylinder) in 3D space: every point within {@code radius} of
 * the segment between {@code p1} and {@code p2}. Equivalently, a cylinder with
 * a hemispherical cap of the same radius at each end. The axis may point in any
 * direction; nothing here assumes an axis-aligned orientation.
 *
 * <p>{@link #contains} tests a point against the capsule and
 * {@link #intersects} tests a {@link Sphere} against it. Both treat the capsule
 * as a closed set: a point exactly on the surface is contained and shapes that
 * touch do intersect, subject to floating point rounding of the inputs.
 *
 * <p>Defining the capsule by its distance to the axis segment, rather than as a
 * cylinder with two caps bolted on, is what keeps the tests here short. There
 * are no end planes to check separately, no rim where a cap meets a lateral
 * surface to special case, and no orientation needed when the endpoints
 * coincide, which simply yields a sphere.
 */
public record Capsule(Vec3 p1, Vec3 p2, double radius) {

    /**
     * @throws IllegalArgumentException if an endpoint is non-finite, or if the
     *     radius is not a positive finite number
     */
    public Capsule {
        if (p1 == null || p2 == null) {
            throw new IllegalArgumentException("endpoints must be non-null");
        }
        if (!p1.isFinite() || !p2.isFinite()) {
            throw new IllegalArgumentException(
                    "endpoints must be finite: " + p1 + ", " + p2);
        }
        if (!(radius > 0.0) || Double.isInfinite(radius)) {
            throw new IllegalArgumentException(
                    "radius must be finite and positive: " + radius);
        }
    }

    /** Convenience constructor taking raw coordinates. */
    public Capsule(double x1, double y1, double z1,
                   double x2, double y2, double z2,
                   double radius) {
        this(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2), radius);
    }

    /** Returns the axis vector {@code p2 - p1}. */
    public Vec3 axis() {
        return p2.minus(p1);
    }

    /**
     * Returns the distance between the endpoints, which is the length of the
     * cylindrical section, excluding the two caps.
     */
    public double height() {
        return p1.distance(p2);
    }

    /**
     * Returns true if {@code q} lies inside or on the surface of this capsule.
     *
     * <p>This is one distance comparison against the closest point on the axis
     * segment, squared on both sides to avoid a square root, which also makes
     * the comparison exact for a point placed on the surface by construction.
     */
    public boolean contains(Vec3 q) {
        return distanceSquaredToAxisSegment(q) <= radius * radius;
    }

    /**
     * Returns true if {@code sphere} overlaps this capsule, that is, if the two
     * solids share at least one point. Touching counts as overlapping, since
     * both shapes are closed.
     *
     * <p>Growing a capsule by the sphere's radius yields a capsule with the
     * same axis and a larger radius, so this is {@link #contains} against the
     * combined radius, and the two agree exactly when the sphere's radius is
     * zero.
     */
    public boolean intersects(Sphere sphere) {
        double combined = radius + sphere.radius();
        return distanceSquaredToAxisSegment(sphere.center()) <= combined * combined;
    }

    /**
     * Returns the squared distance from {@code q} to the closest point on the
     * axis segment, which is the quantity both {@link #contains} and
     * {@link #intersects} are built on.
     *
     * <p>This is the square root free primitive of the class. Comparing it
     * against a squared threshold is exact where the corresponding comparison
     * on distances would round, so prefer it wherever a caller is only ordering
     * or thresholding distances, such as ranking capsules by proximity: square
     * the threshold once rather than taking a root per query.
     *
     * <p>Note that this measures to the axis <em>segment</em>, not to the
     * capsule's surface. Subtracting the radius is what turns it into a
     * distance to the capsule, and that subtraction cannot be done under the
     * square, since {@code (d - radius)^2} needs {@code d} itself and not
     * {@code d^2}. That is why {@link #distanceTo} exists as a distance rather
     * than a squared one: it has to take the root.
     */
    public double distanceSquaredToAxisSegment(Vec3 q) {
        return q.distanceSquared(closestPointOnAxisSegment(q));
    }

    /**
     * Returns the distance from {@code q} to the nearest point of this capsule,
     * or zero if {@code q} is inside it or on its surface.
     *
     * <p>Callers who only need to compare or threshold this should use
     * {@link #distanceSquaredToAxisSegment} instead and avoid the square root.
     */
    public double distanceTo(Vec3 q) {
        return Math.max(0.0, Math.sqrt(distanceSquaredToAxisSegment(q)) - radius);
    }

    /**
     * Returns the point on the axis segment closest to {@code q}, which is the
     * perpendicular projection of {@code q} onto the axis line when that falls
     * between the endpoints, and the nearer endpoint otherwise.
     *
     * <p>Coincident endpoints are not an error and need no caller attention:
     * the segment is then a single point, which is trivially the closest, and
     * the capsule it defines is a sphere. The guard below exists only to avoid
     * dividing zero by zero.
     */
    public Vec3 closestPointOnAxisSegment(Vec3 q) {
        Vec3 axis = axis();
        double axisLengthSquared = axis.lengthSquared();
        if (axisLengthSquared == 0.0) {
            return p1;
        }
        // Fractional position along the axis, clamped to the segment.
        double t = q.minus(p1).dot(axis) / axisLengthSquared;
        if (t <= 0.0) {
            return p1;
        }
        if (t >= 1.0) {
            return p2;
        }
        return p1.plus(axis.scale(t));
    }

    @Override
    public String toString() {
        return "Capsule[p1=" + p1 + ", p2=" + p2 + ", radius=" + radius + "]";
    }
}
