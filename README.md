# 3D Cylinder / Point Intersection

Determines whether a 3D point lies within the volume of a cylinder of arbitrary
orientation, defined by the two endpoints of its axis and a radius, and whether
a sphere overlaps that cylinder. The same class also tests both against a
**capsule** (spherocylinder): the cylinder with a hemispherical cap of the same
radius at each end.

Pure Java, no dependencies beyond the JDK.

## Usage

```java
import geom.Cylinder;
import geom.Vec3;

Cylinder cylinder = new Cylinder(
        new Vec3(1, 2, 3),   // one end of the axis
        new Vec3(4, 6, 3),   // the other end of the axis
        2.0);                // radius

Vec3 point = new Vec3(2.5, 4.0, 3.0);

cylinder.contains(point);           // flat end caps
cylinder.containsAsCapsule(point);  // hemispherical end caps

// Does a sphere overlap the shape?
cylinder.intersectsSphere(point, 0.5);
cylinder.intersectsSphereAsCapsule(point, 0.5);
```

There is also a constructor taking seven raw coordinates, and supporting
queries: `distanceTo` and `distanceToAsCapsule` (distance from a point to the
solid, zero inside), `closestPointOnAxisSegment`, `distanceToAxisLine`, `axis`,
`height`, and `isDegenerate`.

## Building and testing

```
make          # compile to out/
make test     # compile, then run the cases in test/cases.txt
make clean
```

Or without make:

```
javac -d out $(find src -name '*.java')
java -cp out geom.ContainmentTestRunner test/cases.txt
```

The runner prints a line per failure and a summary, and exits nonzero if any
case fails.

## The tests

`test/cases.txt` is a human-readable list of cases with known answers. One case
per line; the leading keyword selects the test and the field count:

```
CYLINDER         x1 y1 z1  x2 y2 z2  radius  qx qy qz                expected
CAPSULE          x1 y1 z1  x2 y2 z2  radius  qx qy qz                expected
CYLINDER_SPHERE  x1 y1 z1  x2 y2 z2  radius  cx cy cz  sphereRadius  expected
CAPSULE_SPHERE   x1 y1 z1  x2 y2 z2  radius  cx cy cz  sphereRadius  expected
```

The first six numbers are the axis endpoints and the seventh is the shape's
radius. The point forms end with the point being tested and expect `IN` or
`OUT`; the sphere forms end with the sphere's center and radius and expect
`HIT` or `MISS`. A `#` begins a comment; blank lines are ignored. Pass different
files as arguments to `ContainmentTestRunner` to run them instead.

The cases cover on-axis and off-axis interiors, points exactly on the lateral
surface and on the end caps, points past an end cap but within the radius (the
case an infinite-cylinder test gets wrong), spheres that exactly touch the
lateral surface, an end cap, and the rim, arbitrary axis orientations, negative
coordinates, a long thin cylinder that stresses numerical precision, and the
degenerate shapes below.

The runner additionally checks relationships that hold for any geometry
regardless of the expected answers, so they catch errors the listed cases might
miss: a point inside the cylinder is inside the capsule, a sphere hitting the
cylinder hits the capsule, a zero radius sphere agrees with the corresponding
point test, and growing a sphere that already hits cannot make it miss.

## How it works

Let `axis = p2 - p1` and `d = q - p1`.

**Cylinder** containment is two independent tests, neither of which needs a
square root or a division:

1. *Between the end caps.* The projection of `q` onto the axis lies within the
   segment exactly when `0 <= d·axis <= axis·axis`. Comparing the unnormalized
   dot product against `|axis|²` avoids normalizing the axis.
2. *Within the radius.* The perpendicular distance from `q` to the axis line is
   `|d × axis| / |axis|`, so the point is within the radius exactly when
   `|d × axis|² <= radius² |axis|²`.

The cross product form of the radial test is used in preference to the
algebraically equivalent `|d|² - (d·axis)²/|axis|²`. That form subtracts two
nearly equal large quantities for a point near the axis of a long cylinder, and
loses most of its significant digits to cancellation; the cross product form
does not.

**Capsule** containment is the set of points within `radius` of the axis
*segment* (rather than of the axis *line*, bounded by the end planes), so it
reduces to one distance comparison against the closest point on the segment,
found by clamping the projection parameter to `[0, 1]`.

**Sphere** intersection asks whether the distance from the sphere's center to
the solid cylinder is at most the sphere's radius. Describe the center by its
distance `along` the axis and its `perpendicular` distance from the axis line;
in those two coordinates the cylinder is the rectangle `[0, height] × [0,
radius]`, and the distance to a rectangle is the excess beyond each side,
combined:

```
axial  = max(0, -along, along - height)
radial = max(0, perpendicular - radius)
result = hypot(axial, radial)
```

This is exact in 3D rather than an approximation in 2D, because the nearest
point of the cylinder always lies in the plane spanned by the axis and the
center. Both terms being nonzero is the rim case, where the nearest point is
the circle at which an end cap meets the lateral surface. Two tempting
shortcuts get that case wrong and both are pinned by cases in the file: taking
the larger of the two excesses understates the distance, and testing the center
against a cylinder grown by the sphere's radius reports a hit for a sphere near
the rim that actually misses, because growing a finite cylinder rounds off its
rim.

For the capsule the grown-shape shortcut is legitimate: a capsule has no rim,
and growing it by the sphere's radius yields a capsule of the same axis and a
larger radius, so that test is one comparison against `radius + sphereRadius`.

## Conventions

- **Closed shapes.** A point exactly on the surface is contained, subject to
  floating point rounding of the inputs.
- **Zero radius.** The cylinder degenerates to its axis segment, which is
  consistent with the general case.
- **Coincident endpoints.** The cylinder has no volume and its axis has no
  direction to orient the remaining zero-height disc, so `contains` returns
  false for every point and `intersectsSphere` false for every sphere. The
  capsule is the exact limiting case, a sphere of the same radius centered on
  the shared endpoint, and needs no special handling: the closest point on a
  zero-length segment is the endpoint itself.
- **Invalid input.** The constructor rejects a negative, NaN, or infinite
  radius and non-finite endpoints with `IllegalArgumentException`, as do the
  sphere methods for the sphere's radius.

## A note on `javax`

The JDK has no built-in vector or matrix type. `javax.vecmath` belongs to
Java 3D, which is a separate and long discontinued library rather than part of
the standard library, so this code uses plain `double` arithmetic in a small
immutable `Vec3` record and depends on nothing outside the JDK. For the same
reason the tests are a `main` based runner over a text file rather than JUnit.
