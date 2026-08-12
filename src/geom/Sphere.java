// SPDX-FileCopyrightText: Steven Ward
// SPDX-License-Identifier: MPL-2.0

package geom;

/**
 * A sphere in 3D space, defined by its center and radius.
 *
 * <p>The radius may be zero, in which case the sphere is a single point.
 * Testing a capsule against such a sphere is the same question as testing it
 * against that point, and {@link Capsule#intersects} and
 * {@link Capsule#contains} agree exactly in that case. This differs from a
 * capsule's own radius, which must be positive: a zero radius capsule would be
 * a bare segment rather than a solid, while a zero radius sphere is a
 * legitimate and useful query.
 *
 * <p>Like the other shapes here this is a closed set, so a point exactly on the
 * surface is inside it.
 */
public record Sphere(Vec3 center, double radius) {

    /**
     * @throws IllegalArgumentException if the center is non-finite, or if the
     *     radius is negative, NaN, or infinite
     */
    public Sphere {
        if (center == null) {
            throw new IllegalArgumentException("center must be non-null");
        }
        if (!center.isFinite()) {
            throw new IllegalArgumentException("center must be finite: " + center);
        }
        if (!(radius >= 0.0) || Double.isInfinite(radius)) {
            throw new IllegalArgumentException(
                    "sphere radius must be finite and non-negative: " + radius);
        }
    }

    /** Convenience constructor taking raw coordinates. */
    public Sphere(double x, double y, double z, double radius) {
        this(new Vec3(x, y, z), radius);
    }
}
