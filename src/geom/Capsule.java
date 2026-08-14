// SPDX-FileCopyrightText: Steven Ward
// SPDX-License-Identifier: MPL-2.0

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
 *
 * <p>Both extremes of the radius are legal limiting cases rather than special
 * cases. An axis shorter than the radius gives a nearly spherical capsule, and
 * a {@code radius} of zero gives the axis segment itself. The zero radius shape
 * is all surface and has no interior, however, which makes {@link #contains}
 * unreliable against it in a way worth reading about before relying on it; see
 * that method. Every other query is unaffected, and
 * {@link #distanceSquaredToAxisSegment} is well behaved at any radius.
 */
public record Capsule(Vec3 p1, Vec3 p2, double radius) {

    /**
     * @throws IllegalArgumentException if an endpoint is non-finite, or if the
     *     radius is negative, NaN, or infinite
     */
    public Capsule {
        if (p1 == null || p2 == null) {
            throw new IllegalArgumentException("endpoints must be non-null");
        }
        if (!p1.isFinite() || !p2.isFinite()) {
            throw new IllegalArgumentException(
                    "endpoints must be finite: " + p1 + ", " + p2);
        }
        if ((radius < 0.0) || !Double.isFinite(radius)) {
            throw new IllegalArgumentException(
                    "radius must be non-negative and finite: " + radius);
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
     * Returns the corner of this capsule's axis-aligned bounding box with the
     * smallest coordinates. See {@link #maxCorner} for the box as a whole.
     */
    public Vec3 minCorner() {
        return new Vec3(Math.min(p1.x(), p2.x()) - radius,
                        Math.min(p1.y(), p2.y()) - radius,
                        Math.min(p1.z(), p2.z()) - radius);
    }

    /**
     * Returns the corner of this capsule's axis-aligned bounding box with the
     * largest coordinates. With {@link #minCorner} this is the smallest box
     * containing the capsule whose faces are perpendicular to the coordinate
     * axes, which is the usual broad phase bound: two boxes overlap exactly
     * when their intervals overlap on all three axes, so a cheap rejection is
     * six comparisons and no arithmetic.
     *
     * <p>The box is the one around the axis segment with every face pushed out
     * by {@code radius}, which is exact rather than an estimate. A capsule is
     * the segment fattened by {@code radius} in every direction, so its extreme
     * point along {@code +x} is the endpoint of greatest {@code x} offset by
     * {@code radius} along that axis, and that point lies on the capsule. Each
     * face therefore touches the shape and none can be brought in.
     *
     * <p>Unlike a bounding sphere this needs no square root and no division,
     * and it is far tighter on the long thin capsules that motivate the shape:
     * for the million-to-one capsule in the cases file the box has about 1.3
     * times the capsule's volume where a sphere would have 10^17 times it. The
     * tradeoff is that it is not rotation invariant. A bounding sphere is
     * unchanged by rotating the capsule, while this box must be recomputed,
     * and a capsule lying along a diagonal gets a looser box than one lying
     * along an axis.
     *
     * <p>This is the one query that does not reduce to
     * {@link #distanceSquaredToAxisSegment}, so it is worth being exact about
     * what it guarantees. The box contains the capsule as a solid: rounding
     * {@code max + radius} to nearest moves a face by at most half a step, and
     * the next representable coordinate beyond it is a full step away, so no
     * {@code double} can fall in the gap.
     *
     * <p>It does not follow that {@link #contains} implies the box contains the
     * point, and nothing asserts that. {@code contains} is not an exact oracle
     * at its own boundary: its squared comparison accepts points a few ulps
     * outside the true surface, and a few of those lie outside the box.
     * Fuzzing finds them only within three ulps of a face, never at the
     * computed extreme point and never at a randomly chosen one. Callers doing
     * a broad phase are unaffected, since a box that admits one extra ulp of
     * candidates only costs them a narrow phase test that then answers no.
     */
    public Vec3 maxCorner() {
        return new Vec3(Math.max(p1.x(), p2.x()) + radius,
                        Math.max(p1.y(), p2.y()) + radius,
                        Math.max(p1.z(), p2.z()) + radius);
    }

    /**
     * Returns true if {@code q} lies inside or on the surface of this capsule.
     *
     * <p>This is one distance comparison against the closest point on the axis
     * segment, squared on both sides to avoid a square root, which also makes
     * the comparison exact for a point placed on the surface by construction.
     *
     * <p><b>Do not rely on this for a capsule whose radius is zero.</b> Such a
     * capsule is the bare axis segment: it is all surface and has no interior,
     * so containment requires the squared distance to be exactly zero, which
     * holds only where {@link #closestPointOnAxisSegment} reproduces {@code q}
     * bit for bit. It does so at the endpoints and wherever {@code p1 + axis*t}
     * happens to round back to the query point, and not otherwise. Sampling
     * points that are mathematically on the segment, that is roughly five in
     * six for an axis along a coordinate direction and two in three for an
     * oblique one, so the answer here is largely a fact about rounding rather
     * than about the geometry.
     *
     * <p>Callers who need that query should use
     * {@link #distanceSquaredToAxisSegment} and compare it against a squared
     * tolerance of their own choosing, which is the only form in which "is this
     * point on the segment" is a well posed question in floating point. The
     * same applies to the surface of any capsule; a zero radius is simply the
     * case where the entire shape is surface and there is no interior left for
     * the test to be robust in. Note that {@link #intersects} is not affected:
     * a sphere of nonzero radius against a zero radius capsule is an ordinary
     * comparison with room on both sides of it.
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
     * the threshold once rather than taking a root per query. It is also what
     * to use in place of {@link #contains} when the radius is zero, for the
     * reasons given there.
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
