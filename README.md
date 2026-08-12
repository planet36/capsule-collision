# 3D Capsule Collision

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
import geom.Sphere;
import geom.Vec3;

Capsule capsule = new Capsule(
        new Vec3(1, 2, 3),   // one end of the axis
        new Vec3(4, 6, 3),   // the other end of the axis
        2.0);                // radius, which must be positive

capsule.contains(new Vec3(2.5, 4.0, 3.0));                        // point inside?
capsule.intersects(new Sphere(new Vec3(2.5, 4.0, 7.0), 0.5));     // sphere overlapping?
```

Both shapes validate on construction, so the query methods are pure geometry
and never throw. `Sphere` also has a four-argument constructor taking raw
coordinates and a radius.

`Capsule` also has a constructor taking seven raw coordinates, and supporting
queries: `distanceSquaredToAxisSegment`, `distanceTo` (distance from a point to
the capsule, zero inside), `closestPointOnAxisSegment`, and `axis`.

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

## Benchmarking

```
make bench        # time the query methods
make bench-noea   # the same, with escape analysis disabled
```

`CapsuleBench` times the five query methods against three query distributions —
projections landing inside the segment, projections clamping to a cap, and
uniform points in the bounding box — because the branch mix, not the
arithmetic, is what moves the number. It reports nanoseconds per query and
throughput, as the minimum and median over the measured iterations:

```
distribution  method            result    min ns/op    median ns/op     Mops/s
interior      contains            2110        3.271           3.985      305.7
interior      intersects          3072        3.164           4.097      316.1
interior      distanceSq        5.2216        2.811           3.356      355.8
interior      distanceTo        0.4872        4.422           5.255      226.2
interior      closestPoint      1.0034        3.272           3.479      305.6
```

`distanceSq` is `distanceSquaredToAxisSegment` and `closestPoint` is
`closestPointOnAxisSegment`. The `result` column is a hit count for the two
boolean methods and a mean returned value for the rest; it exists so a reader
can see the work was not optimized away.

Two things the numbers show. The square root is worth avoiding: `distanceTo`
costs about 0.9 to 1.6 ns more per query than `distanceSq`, which is 40% to 65%
on top, and is why the sqrt-free accessor is the one recommended for ordering
and thresholding. And `closestPoint` is no more expensive than `contains`,
even though it is the one method whose returned `Vec3` really does escape to
its caller: the single allocation costs less than the distance comparison
`contains` goes on to do.

Pass `--distribution=NAME`, `--warmup=N`, `--measure=N`, or `--sweeps=N`
through `BENCH_ARGS` to narrow or lengthen a run.

Treat the numbers as indicative. JMH is the right tool for Java microbenchmarks
and is a third-party jar, so this harness defends against dead code
elimination, constant folding and cold compilation by hand, and it cannot fork
a JVM per measurement the way JMH does — by default all three distributions
share one compilation of the sweep loop, so each one's branch profile is
blended with the others'. Use `--distribution` for an uncontaminated figure.

`make bench-noea` reruns with `-XX:-DoEscapeAnalysis`. The gap between the two
is the cost the JIT is absorbing by scalarizing the `Vec3` objects each query
allocates, which on this code is a factor of three to four. With the
optimization off, every method converges on the same cost and even the square
root disappears into the noise, which is a fair summary of how much of this
code's speed is allocation the JIT removes rather than arithmetic.

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

Either kind of line may expect `INVALID` instead, which asserts that the shapes
it describes are illegal and that a constructor rejects them. Such a line runs
no query, and is how the validation rules are covered: that a capsule's radius
must be positive while a sphere's need only be non-negative, and that no radius
or coordinate may be NaN or infinite. Because a line names both a capsule and a
sphere and either may be the illegal one, keep the shape that is not under test
obviously legal.

The cases cover on-axis and off-axis interiors, points exactly on the lateral
surface and on the hemispherical caps, spheres exactly touching each of those,
arbitrary axis orientations, negative coordinates, a million-to-one aspect ratio
that stresses numerical precision, the two extreme shapes — an axis shorter than
the radius, and coincident endpoints — and every way a shape can be illegal.

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
- **Sphere intersection** compares it against `(radius + sphere.radius())²`.
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
  sphere is a point, and querying with one is meaningful; `Sphere` rejects only
  negative, NaN, and infinite radii. Validation lives in each shape's
  constructor, so `contains` and `intersects` are pure geometry and never throw.
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
