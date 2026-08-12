package geom;

/**
 * A finite circular cylinder in 3D space, defined by the two endpoints of its
 * axis and a radius. The axis may point in any direction; nothing here assumes
 * an axis-aligned orientation.
 *
 * <p>The same three parameters also define a <em>capsule</em> (spherocylinder):
 * the cylinder with a hemispherical cap of the same radius added at each end.
 * Equivalently, a capsule is the set of all points within {@code radius} of the
 * axis <em>segment</em>, whereas the cylinder is the set of all points within
 * {@code radius} of the axis <em>line</em> that also lie between the two end
 * planes. {@link #contains} and {@link #containsAsCapsule} implement those two
 * shapes respectively, so a capsule always contains its cylinder.
 *
 * <p>Both tests treat the shapes as closed sets: a point exactly on the surface
 * is contained, subject to floating point rounding of the inputs.
 */
public record Cylinder(Vec3 p1, Vec3 p2, double radius) {

    /**
     * @throws IllegalArgumentException if an endpoint is non-finite, or if the
     *     radius is negative, NaN, or infinite
     */
    public Cylinder {
        if (p1 == null || p2 == null) {
            throw new IllegalArgumentException("endpoints must be non-null");
        }
        if (!p1.isFinite() || !p2.isFinite()) {
            throw new IllegalArgumentException(
                    "endpoints must be finite: " + p1 + ", " + p2);
        }
        if (!(radius >= 0.0) || Double.isInfinite(radius)) {
            throw new IllegalArgumentException(
                    "radius must be finite and non-negative: " + radius);
        }
    }

    /** Convenience constructor taking raw coordinates. */
    public Cylinder(double x1, double y1, double z1,
                    double x2, double y2, double z2,
                    double radius) {
        this(new Vec3(x1, y1, z1), new Vec3(x2, y2, z2), radius);
    }

    /** Returns the axis vector {@code p2 - p1}. */
    public Vec3 axis() {
        return p2.minus(p1);
    }

    /** Returns the distance between the two endpoints, i.e. the height. */
    public double height() {
        return p1.distance(p2);
    }

    /**
     * Returns true if the endpoints coincide, in which case the axis has no
     * direction and the cylinder has no volume. See {@link #contains} and
     * {@link #containsAsCapsule} for how each test handles this.
     */
    public boolean isDegenerate() {
        return axis().lengthSquared() == 0.0;
    }

    /**
     * Returns true if {@code q} lies inside or on the surface of this cylinder.
     *
     * <p>The test has two independent parts, both performed without a square
     * root or a division:
     *
     * <ol>
     *   <li><b>Between the end caps.</b> With {@code d = q - p1} and
     *       {@code axis = p2 - p1}, the projection of {@code q} onto the axis
     *       lies within the segment exactly when
     *       {@code 0 <= d.axis <= axis.axis}. Comparing the unnormalized dot
     *       product against {@code |axis|^2} avoids dividing by the axis length.
     *   <li><b>Within the radius.</b> The perpendicular distance from {@code q}
     *       to the axis line is {@code |d x axis| / |axis|}, so the point is
     *       within the radius exactly when
     *       {@code |d x axis|^2 <= radius^2 |axis|^2}. The cross product form is
     *       used in preference to {@code |d|^2 - (d.axis)^2 / |axis|^2}, which
     *       loses precision to catastrophic cancellation for points near the
     *       axis of a long cylinder.
     * </ol>
     *
     * <p>A degenerate cylinder (coincident endpoints) contains nothing and this
     * returns false for every point: it has zero volume, and its axis has no
     * direction that could orient the zero-height disc that remains.
     */
    public boolean contains(Vec3 q) {
        Vec3 axis = axis();
        double axisLengthSquared = axis.lengthSquared();
        if (axisLengthSquared == 0.0) {
            return false;
        }

        Vec3 d = q.minus(p1);

        // Reject points beyond either end cap.
        double projection = d.dot(axis);
        if (projection < 0.0 || projection > axisLengthSquared) {
            return false;
        }

        // Reject points farther than the radius from the axis line.
        double crossLengthSquared = d.cross(axis).lengthSquared();
        return crossLengthSquared <= radius * radius * axisLengthSquared;
    }

    /**
     * Returns true if {@code q} lies inside or on the surface of the capsule
     * (spherocylinder) with this cylinder's axis and radius, that is, the
     * cylinder plus a hemispherical cap at each end.
     *
     * <p>This is the set of points whose distance to the axis <em>segment</em>
     * is at most the radius, so the test is a single distance comparison against
     * the closest point on the segment.
     *
     * <p>A degenerate capsule (coincident endpoints) is a sphere of the same
     * radius centered on that point, which is the exact limiting case rather
     * than a special case: the closest point on a zero-length segment is always
     * the endpoint itself.
     */
    public boolean containsAsCapsule(Vec3 q) {
        return q.distanceSquared(closestPointOnAxisSegment(q)) <= radius * radius;
    }

    /**
     * Returns the point on the axis segment closest to {@code q}, which is the
     * perpendicular projection of {@code q} onto the axis line when that falls
     * between the endpoints, and the nearer endpoint otherwise.
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

    /** Returns the perpendicular distance from {@code q} to the axis line. */
    public double distanceToAxisLine(Vec3 q) {
        Vec3 axis = axis();
        double axisLengthSquared = axis.lengthSquared();
        if (axisLengthSquared == 0.0) {
            return q.distance(p1);
        }
        return Math.sqrt(q.minus(p1).cross(axis).lengthSquared() / axisLengthSquared);
    }

    @Override
    public String toString() {
        return "Cylinder[p1=" + p1 + ", p2=" + p2 + ", radius=" + radius + "]";
    }
}
