# 3D Cylinder / Point Intersection

Determines whether a 3D point lies within the volume of a cylinder of arbitrary
orientation, defined by the two endpoints of its axis and a radius. The same
class also tests containment in a **capsule** (spherocylinder): the cylinder
with a hemispherical cap of the same radius at each end.

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
```

There is also a constructor taking seven raw coordinates, and supporting
queries: `closestPointOnAxisSegment`, `distanceToAxisLine`, `axis`, `height`,
and `isDegenerate`.

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
per line, twelve whitespace separated fields:

```
shape   x1 y1 z1   x2 y2 z2   radius   qx qy qz   expected
```

`shape` is `CYLINDER` or `CAPSULE`, the first six numbers are the axis
endpoints, the next three are the point being tested, and `expected` is `IN` or
`OUT`. A `#` begins a comment; blank lines are ignored. Pass different files as
arguments to `ContainmentTestRunner` to run them instead.

The cases cover on-axis and off-axis interiors, points exactly on the lateral
surface and on the end caps, points past an end cap but within the radius (the
case an infinite-cylinder test gets wrong), arbitrary axis orientations,
negative coordinates, a long thin cylinder that stresses numerical precision,
and the degenerate shapes below.

The runner additionally checks, on every case, that a point inside the cylinder
is also inside the capsule. That invariant holds for any geometry regardless of
the expected answers, so it catches errors the listed cases might miss.

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

## Conventions

- **Closed shapes.** A point exactly on the surface is contained, subject to
  floating point rounding of the inputs.
- **Zero radius.** The cylinder degenerates to its axis segment, which is
  consistent with the general case.
- **Coincident endpoints.** The cylinder has no volume and its axis has no
  direction to orient the remaining zero-height disc, so `contains` returns
  false for every point. The capsule is the exact limiting case, a sphere of
  the same radius centered on the shared endpoint, and needs no special
  handling: the closest point on a zero-length segment is the endpoint itself.
- **Invalid input.** The constructor rejects a negative, NaN, or infinite
  radius and non-finite endpoints with `IllegalArgumentException`.

## A note on `javax`

The JDK has no built-in vector or matrix type. `javax.vecmath` belongs to
Java 3D, which is a separate and long discontinued library rather than part of
the standard library, so this code uses plain `double` arithmetic in a small
immutable `Vec3` record and depends on nothing outside the JDK. For the same
reason the tests are a `main` based runner over a text file rather than JUnit.
