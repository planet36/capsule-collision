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
java -cp out geom.ContainmentTestRunner path/to/other-cases.txt
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
- `Cylinder` — record of `(p1, p2, radius)`. Holds both shapes: `contains` and
  `intersectsSphere` are the cylinder (flat end caps), `containsAsCapsule` and
  `intersectsSphereAsCapsule` are the capsule (hemispherical end caps). One
  type serves both because they share the same three parameters and differ only
  in the tests. Methods come in cylinder/capsule pairs; keep that pairing when
  adding to the API.
- `ContainmentTestRunner` — `main` that reads cases with known answers from a
  text file and compares them against both methods.

The two containment tests are deliberately formulated differently, and the
comments in `Cylinder` explain why; preserve this if you touch the math:

- `contains` compares an unnormalized dot product against `|axis|²` for the end
  caps, and `|d × axis|² <= radius²|axis|²` for the radius. No square root, no
  division. The cross product form of the radial test resists the catastrophic
  cancellation that `|d|² - (d·axis)²/|axis|²` suffers near the axis of a long
  cylinder — `test/cases.txt` has a million-to-one aspect ratio case that
  covers this.
- `containsAsCapsule` is a single distance comparison against the closest point
  on the axis segment, since a capsule is exactly the set of points within
  `radius` of that segment.
- `intersectsSphere` asks whether `distanceTo` the solid is at most the sphere's
  radius. `distanceTo` treats the cylinder as a rectangle in (along, perpendicular)
  coordinates and combines the excess beyond each side with `hypot`. **Do not
  "simplify" this to the larger of the two excesses, and do not reimplement the
  sphere test as a point test against a cylinder grown by the sphere radius.**
  Both are wrong at the rim, which is where the nearest point of a finite
  cylinder often is; `test/cases.txt` has cases that fail under either.
- `intersectsSphere` short circuits on `contains` before measuring, so a center
  exactly on the surface agrees with `contains` rather than depending on how the
  square roots in `distanceTo` round. `intersectsSphereAsCapsule` needs no such
  guard: its expression reduces to `containsAsCapsule` when the sphere radius is
  zero.

Both shapes are closed: a point on the surface is contained, and the cases file
pins boundary behavior with points placed exactly on surfaces using values that
are exact in binary floating point. Keep new boundary cases exactly
representable, or the expected answer becomes a coin flip on rounding.

Degenerate geometry is defined behavior, not an error, and is covered by cases:
zero radius gives the axis segment; coincident endpoints give an empty cylinder
(no volume, and no axis direction to orient the remaining disc) but a sphere
for the capsule.

## Test data

`test/cases.txt` is the source of truth for expected behavior. Format, one case
per line, `#` comments and blank lines ignored; the leading keyword determines
the field count:

```
CYLINDER         x1 y1 z1  x2 y2 z2  radius  qx qy qz                expected
CAPSULE          x1 y1 z1  x2 y2 z2  radius  qx qy qz                expected
CYLINDER_SPHERE  x1 y1 z1  x2 y2 z2  radius  cx cy cz  sphereRadius  expected
CAPSULE_SPHERE   x1 y1 z1  x2 y2 z2  radius  cx cy cz  sphereRadius  expected
```

`expected` is `IN`/`OUT` for a point and `HIT`/`MISS` for a sphere. When adding
behavior, add a keyword and cases here rather than writing a separate harness;
`Shape` in the runner carries each keyword's field count.

Independently of the expected answers, `ContainmentTestRunner.checkInvariants`
asserts on every case that the methods agree with each other: the capsule
contains the cylinder, a zero radius sphere matches the point test, and growing
a sphere that hits cannot make it miss. New geometry code should keep those
true, and new relationships belong there.
