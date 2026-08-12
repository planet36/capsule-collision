// SPDX-FileCopyrightText: Steven Ward
// SPDX-License-Identifier: MPL-2.0

package geom;

/**
 * An immutable point or vector in 3D space, with double precision components.
 *
 * <p>The same type serves as both a position and a displacement; which one is
 * meant depends on context, as is conventional in computational geometry.
 */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

    /** Returns {@code this + v}. */
    public Vec3 plus(Vec3 v) {
        return new Vec3(x + v.x, y + v.y, z + v.z);
    }

    /** Returns {@code this - v}. */
    public Vec3 minus(Vec3 v) {
        return new Vec3(x - v.x, y - v.y, z - v.z);
    }

    /** Returns this vector scaled by {@code s}. */
    public Vec3 scale(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    /** Returns the dot product {@code this . v}. */
    public double dot(Vec3 v) {
        return x * v.x + y * v.y + z * v.z;
    }

    /**
     * Returns the cross product {@code this x v}, whose magnitude is
     * {@code |this| |v| sin(theta)}.
     */
    public Vec3 cross(Vec3 v) {
        return new Vec3(
                y * v.z - z * v.y,
                z * v.x - x * v.z,
                x * v.y - y * v.x);
    }

    /** Returns the squared length of this vector. */
    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    /** Returns the length of this vector. */
    public double length() {
        return Math.sqrt(lengthSquared());
    }

    /** Returns the squared distance between this point and {@code v}. */
    public double distanceSquared(Vec3 v) {
        double dx = x - v.x;
        double dy = y - v.y;
        double dz = z - v.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Returns the distance between this point and {@code v}. */
    public double distance(Vec3 v) {
        return Math.sqrt(distanceSquared(v));
    }

    /** Returns true if no component is NaN or infinite. */
    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
