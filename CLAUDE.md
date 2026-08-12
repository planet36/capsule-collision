# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```
make            # compile src/ to out/ with -Xlint:all
make test       # compile, then run test/cases.txt
make clean
```

To run a different or reduced set of cases, pass files to the runner directly;
it accepts any number of paths, which is the way to run a single case in
isolation (copy the line into a scratch file):

```
java -cp out geom.CapsuleTestRunner path/to/other-cases.txt
```

The runner exits nonzero if any case fails, any line is unparseable, or any
invariant is violated.

## Constraints

- **JDK only, no dependencies.** No Maven or Gradle, no JUnit, no third-party
  jars. `javax.vecmath` is Java 3D, not part of the standard library, so it is
  not available; vector math lives in `geom.Vec3`.
- Built and tested against JDK 26. Uses records.

## Architecture

Three files in `src/geom`, plus the test data in `test/cases.txt`:

- `Vec3` — immutable record, a 3D point or vector, with the vector operations.
- `Capsule` — record of `(p1, p2, radius)`. `contains` tests a point,
  `intersectsSphere` tests a sphere.
- `CapsuleTestRunner` — `main` that reads cases with known answers from a text
  file and compares them against both methods.

The shape is a **capsule** (spherocylinder), not a cylinder: it is every point
within `radius` of the axis *segment*, so its ends are hemispheres rather than
flat caps. A point up to `radius` beyond an endpoint is inside. This was a
deliberate narrowing; git history has an earlier version supporting both shapes
if flat ends are ever needed again.

Defining the capsule by distance to the segment is what keeps this small, and
every method reduces to `distanceSquaredToAxisSegment`, which is the class's
square-root-free primitive:

- `contains` compares it against `radius * radius`.
- `intersectsSphere` compares it against `(radius + sphereRadius)` squared.
  Growing a capsule by a radius yields a capsule, so no separate geometry is
  needed, and the two methods agree exactly at `sphereRadius == 0`.

Keep both comparisons squared — they are exact for points placed on the surface
by construction, which is what lets the cases file assert boundary behavior.

`distanceTo` is the one method that must take a square root, and it cannot be
converted to a squared form: the distance to the capsule is `d - radius`, and
`(d - radius)^2` needs `d` rather than `d^2`, so a `distanceSquaredTo` would
take the same root and then square the result. That is why the sqrt-free
accessor is offered on the segment instead of on the capsule. Callers ordering
or thresholding distances should use it and pre-square their threshold.

There is no end-plane test, no rim case, and no orientation needed for a
zero-length axis. If you find yourself adding one, you are reintroducing a
cylinder.

## Invariants and validation

- The capsule's `radius` must be **positive**; the constructor rejects zero.
  A sphere's radius passed to `intersectsSphere` may be **zero** (it is then a
  point query, and the basis of one of the runner's invariants) but not
  negative. This asymmetry is intentional.
- Coincident endpoints are legal and yield a sphere — the exact limiting case.
  The `axisLengthSquared == 0.0` guard in `closestPointOnAxisSegment` exists
  only to avoid dividing zero by zero, not to implement a special case. An axis
  shorter than the radius is legal too, giving a nearly spherical capsule.
- `CapsuleTestRunner.checkInvariants` asserts on every case that the methods
  agree with each other. New relationships belong there. Invariants phrased on
  `distanceSquaredToAxisSegment` are exact, since that is what the tests
  compare; any invariant relating `distanceTo` to them still needs slack,
  because it takes a square root while they compare squared distances, so at
  exact tangency the two need not agree on the last bit.

## Test data

`test/cases.txt` is the source of truth for expected behavior. Format, one case
per line, `#` comments and blank lines ignored; the leading keyword names what
is being tested and determines the field count:

```
POINT    x1 y1 z1  x2 y2 z2  radius  qx qy qz                expected
SPHERE   x1 y1 z1  x2 y2 z2  radius  cx cy cz  sphereRadius  expected
```

`expected` is `IN`/`OUT` for a point and `HIT`/`MISS` for a sphere. When adding
behavior, add a keyword and cases here rather than writing a separate harness;
`Query` in the runner carries each keyword's field count.

Boundary cases use values exactly representable in binary floating point, so
that `IN` on a surface is not a coin flip on rounding. Keep new boundary cases
exactly representable.
