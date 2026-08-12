# 3D Capsule Intersection

Determines whether a 3D point lies within the volume of a **capsule**
(spherocylinder) of arbitrary orientation, and whether a sphere overlaps one.

A capsule is every point within `radius` of the segment between two endpoints:
a cylinder with a hemispherical cap of the same radius at each end. Defining it
by distance to the segment, rather than as a cylinder with caps bolted on, is
what keeps the code short.

Pure Java, no dependencies beyond the JDK.

## Usage

```java
import geom.Capsule;
import geom.Vec3;

Capsule capsule = new Capsule(
        new Vec3(1, 2, 3),   // one end of the axis
        new Vec3(4, 6, 3),   // the other end of the axis
        2.0);                // radius, which must be positive

capsule.contains(new Vec3(2.5, 4.0, 3.0));               // point inside?
capsule.intersectsSphere(new Vec3(2.5, 4.0, 7.0), 0.5);  // sphere overlapping?
```

There is also a constructor taking seven raw coordinates, and supporting
queries: `distanceSquaredToAxisSegment`, `distanceTo` (distance from a point to
the capsule, zero inside), `closestPointOnAxisSegment`, `axis`, and `height`.

For ordering or thresholding distances — ranking capsules by proximity, testing
against a cutoff — use `distanceSquaredToAxisSegment` and square the threshold
once, rather than taking a square root per query.

## Building and testing

```
make          # compile to out/
make test     # compile, then run the cases in test/cases.txt
make clean
```

Or without make:

```
javac -d out $(find src -name '*.java')
java -cp out geom.CapsuleTestRunner test/cases.txt
```

The runner prints a line per failure and a summary, and exits nonzero if any
case fails.

## The tests

`test/cases.txt` is a human-readable list of cases with known answers. One case
per line; the leading keyword names what is being tested against the capsule and
determines the field count:

```
POINT    x1 y1 z1  x2 y2 z2  radius  qx qy qz                expected
SPHERE   x1 y1 z1  x2 y2 z2  radius  cx cy cz  sphereRadius  expected
```

The first six numbers are the capsule's axis endpoints and the seventh is its
radius. A `POINT` case ends with the point being tested and expects `IN` or
`OUT`; a `SPHERE` case ends with the sphere's center and radius and expects
`HIT` or `MISS`. A `#` begins a comment; blank lines are ignored. Pass different
files as arguments to `CapsuleTestRunner` to run them instead.

The cases cover on-axis and off-axis interiors, points exactly on the lateral
surface and on the hemispherical caps, spheres exactly touching each of those,
arbitrary axis orientations, negative coordinates, a million-to-one aspect ratio
that stresses numerical precision, and the two extreme shapes: an axis shorter
than the radius, and coincident endpoints.

The runner additionally checks relationships that hold for any geometry
regardless of the expected answers, so they catch errors the listed cases might
miss: both tests agree with a squared comparison against
`distanceSquaredToAxisSegment`, a zero radius sphere agrees with the point test,
growing a sphere that already hits cannot make it miss, a contained point is at
distance zero, and `distanceTo` is the segment distance less the radius.

Every one of those holds exactly. There is no tolerance or epsilon constant
anywhere in this project, in the library or the tests: the containment and
intersection tests are squared comparisons that never take a square root, so
their boundaries are exact rather than approximate.

## How it works

Every method reduces to `distanceSquaredToAxisSegment`, the squared distance
from a query point to the axis segment, found by projecting onto the axis and
clamping the parameter to `[0, 1]`:

- **Point containment** compares it against `radius²`.
- **Sphere intersection** compares it against `(radius + sphereRadius)²`.
  Growing a capsule by the sphere's radius yields a capsule with the same axis
  and a larger radius, so the two agree exactly when the sphere's radius is zero.

Both are squared comparisons, so neither takes a square root, and both are exact
for a point placed on the surface by construction — which is what lets the cases
file pin boundary behavior.

`distanceTo` is the exception that must take a root. The distance to the capsule
is `d - radius`, and that subtraction cannot happen under the square, since
`(d - radius)² = d² - 2·radius·d + radius²` needs `d` itself and not `d²`. A
squared variant of it would therefore compute the same square root and then
square the result, which is why the sqrt-free accessor is offered one step
earlier, on the segment, instead.

There is no end-plane test, no rim where a cap meets a lateral surface, and no
axis direction needed when the endpoints coincide. Those are all cylinder
problems, and a capsule has none of them.

## Conventions

- **Closed shape.** A point exactly on the surface is contained, and shapes that
  touch do intersect, subject to floating point rounding of the inputs.
- **The capsule's radius must be positive.** A zero radius capsule is a bare
  segment rather than a solid, so the constructor rejects it along with negative,
  NaN, and infinite radii, and non-finite endpoints, throwing
  `IllegalArgumentException`.
- **A sphere's radius may be zero**, unlike the capsule's own. A zero radius
  sphere is a point, and querying with one is meaningful; `intersectsSphere`
  rejects only negative, NaN, and infinite radii.
- **Coincident endpoints are legal**, giving a sphere of the same radius. This
  is the exact limiting case, not a special case: the closest point on a
  zero-length segment is the endpoint itself. An axis shorter than the radius is
  likewise legal, giving a nearly spherical capsule.

## A note on `javax`

The JDK has no built-in vector or matrix type. `javax.vecmath` belongs to
Java 3D, which is a separate and long discontinued library rather than part of
the standard library, so this code uses plain `double` arithmetic in a small
immutable `Vec3` record and depends on nothing outside the JDK. For the same
reason the tests are a `main` based runner over a text file rather than JUnit.
