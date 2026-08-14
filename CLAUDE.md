# CLAUDE.md

## Constraints

- **JDK only, no dependencies.** No Maven or Gradle, no JUnit, no third-party
  jars. `javax.vecmath` is Java 3D, not part of the standard library, so it is
  not available; vector math lives in `geom.Vec3`.
- Built and tested against JDK 26. Uses records.

## Architecture

`Sphere` is a pure data type on purpose: it validates and nothing else. Resist
adding `Sphere.contains`, `Sphere.intersects(Sphere)`, or a `Segment` type; that
is a general geometry library and a different project.

The shape is a **capsule** (spherocylinder), not a cylinder: it is every point
within `radius` of the axis *segment*, so its ends are hemispheres rather than
flat caps. A point up to `radius` beyond an endpoint is inside. This was a
deliberate narrowing; git history has an earlier version supporting both shapes
if flat ends are ever needed again.

Defining the capsule by distance to the segment is what keeps this small, and
every method except `minCorner`/`maxCorner` reduces to
`distanceSquaredToAxisSegment`, which is the class's square-root-free
primitive:

- `contains` compares it against `radius * radius`.
- `intersects` compares it against `(radius + sphere.radius())` squared.
  Growing a capsule by a radius yields a capsule, so no separate geometry is
  needed, and the two methods agree exactly for a zero radius `Sphere`.

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

`minCorner` and `maxCorner` are the one exception to the reduction above: the
axis-aligned bounding box, for use as a broad phase bound. It is the segment's
own box with every face pushed out by `radius`, which is exact and needs no
square root and no division, and it is the *only* bound this class should grow.

A bounding sphere was considered and rejected. It is `|axis|/2 + radius`
centered on the axis midpoint, which is correct and minimal, but: it would be a
second square root; it is one line a caller can already write from `axis()` and
`radius()`; and it is a terrible bound for the long thin capsules that motivate
the shape — 10^17 times the volume of the million-to-one capsule in the cases
file, against about 1.3 times for the box. Do not add it back without a caller
that specifically needs a rotation-invariant bound, which is the one thing the
box is not.

## Invariants and validation

- Either radius may be **zero**, but neither may be negative, NaN, or infinite.
  Each rule lives in its own record's constructor, which is why `contains` and
  `intersects` are pure geometry that never throws.

  A zero radius `Sphere` is a point query and the basis of one of the runner's
  invariants. A zero radius `Capsule` is the bare axis segment. That was
  rejected until the constructor was relaxed, on the grounds that it is "not a
  solid"; the real objection is narrower and is now documented on
  `Capsule.contains`, which is the only method it degrades. That capsule has no
  interior, so containment needs a squared distance of exactly zero, which
  holds only where `p1 + axis*t` rounds back to the query point — about 5/6 of
  on-segment points for an axis-aligned segment, 2/3 for an oblique one. Send
  such callers to `distanceSquaredToAxisSegment` with a tolerance of their own.
  `intersects` and the rest are unaffected, and all six runner invariants still
  hold exactly at radius zero, so this cost no tolerance constant.

  Note that a zero radius capsule *is* the `Segment` type the architecture
  section tells you not to add. Allowing the radius is not permission to grow
  segment operations on `Capsule`; `distanceSquaredToAxisSegment` is already
  the whole of what a caller needs from one.
- Coincident endpoints are legal and yield a sphere — the exact limiting case.
  The `axisLengthSquared == 0.0` guard in `closestPointOnAxisSegment` exists
  only to avoid dividing zero by zero, not to implement a special case. An axis
  shorter than the radius is legal too, giving a nearly spherical capsule.
- `CapsuleTestRunner.checkInvariants` asserts on every case that the methods
  agree with each other. New relationships belong there, and **every one of them
  currently holds exactly — there is no tolerance constant anywhere in this
  repository. Keep it that way.**

  If a proposed invariant seems to need a fudge factor, it is almost certainly
  asserting a round trip through a square root rather than a fact about the
  geometry. The specific one to avoid is "a sphere of exactly `distanceTo(q)`
  must hit": no fixed slack makes it hold, because the error to absorb scales
  with `radius + distance` rather than with `distance`, so for a fat capsule
  near the query point it is unbounded when measured in ulps of the distance.
  Phrase invariants on `distanceSquaredToAxisSegment` instead, which is what
  the tests actually compare, or assert a method's definition directly.

  The second one to avoid is "a contained point lies inside the bounding box".
  It looks like the one property a bound must have, and it is true of the
  capsule as a solid, but `contains` is not an exact oracle at its own
  boundary: its squared comparison accepts points a few ulps outside the true
  surface, and some of those fall outside the box. Fuzzing puts them within
  three ulps of a face — never at the computed extreme, never at a random
  point — so the assertion passes the current cases and would fail later on
  inputs nobody has tried. It is the same round-trip-through-rounding mistake
  wearing different clothes. `minCorner`/`maxCorner` are asserted by their
  definition instead, which catches both ways of getting them wrong (omitting
  the radius, transposing min and max) — verified by mutation, 103 and 28
  violations respectively.

  Do not solve this by adding a tolerance parameter to `intersects`. That
  would export a harness problem into the public API and change the method's
  meaning from "do these solids share a point" to "do they nearly share one",
  and any fixed default is scale-broken: at the million-to-one capsule in the
  cases file, `1e-12` is smaller than one ulp of the coordinates and does
  nothing at all.

## Performance

`CapsuleBench` measures the five query methods; run it with `make bench`.
It is hand rolled because JMH is a third-party jar, so most of it is defense
against the compiler rather than measurement. Read its class comment before
changing it: the accumulated count, the prebuilt query arrays, and the batch
loop are each doing a job, and removing one silently turns the benchmark into a
measurement of nothing.

Two things it has already settled:

- **The query path calls no `Math` method at all.** `contains` and `intersects`
  reduce to additions, multiplies, one divide and three compares; the only
  square root in the class is in `distanceTo`. So proposals to swap in a faster
  math library have nothing to act on here. (`FastMath` specifically is Apache
  Commons Math, which is a dependency, and its `sqrt` is a bare delegation to
  `Math.sqrt`, which is a JIT intrinsic already.)
- **Escape analysis is what makes the `Vec3` design free.** `contains` allocates up to four `Vec3`
  objects per call, and C2 scalarizes all of them: `make bench-noea` reruns
  with `-XX:-DoEscapeAnalysis` and is three to four times slower, tracking the
  allocation count per branch. The immutable `Vec3` design is therefore free in
  practice but not free by construction. If a change makes one of those objects
  escape — storing it, returning it through a non-inlined interface, making
  `Vec3` non-final — the cost reappears, and `bench-noea` is the upper bound on
  what that costs.

- **The square root is worth avoiding, and now there is a number for it.**
  `distanceTo` costs 0.9 to 1.6 ns per query more than
  `distanceSquaredToAxisSegment`, 40% to 65% on top. That is the measured
  justification for steering callers who only order or threshold distances to
  the squared accessor, so the advice in the README is a performance claim and
  not just an exactness one.
- **An escaping `Vec3` is cheaper than it sounds.** `closestPointOnAxisSegment`
  is the one query that returns a freshly allocated point to its caller, and
  the benchmark stores it into an array so the allocation really happens. It
  still costs no more than `contains`, which allocates the same objects but
  gets them all scalarized and then does a distance comparison on top. So a
  change that makes one object escape is not a disaster; it is a change that
  makes all four escape, by defeating inlining, that costs the factor above.

Because escape analysis already does it, hand-inlining the math over raw
`double` fields is not worth doing. Measure before believing otherwise.

## Test data

`test/cases.txt` is the source of truth for expected behavior; its own comment
header documents the line format. When adding behavior, add a keyword and cases
here rather than writing a separate harness; `Query` in the runner carries each
keyword's field count.

Validation is tested by the `INVALID` expectation rather than by a new query
keyword, so those lines keep the field layout of the `POINT` and `SPHERE` cases
around them. This is why `TestCase` holds the raw endpoints and radii instead of
a built `Capsule` and `Sphere`: whether a constructor accepts them is the
question an `INVALID` case is asking, so construction happens per case in the
runner and a rejection is a result rather than a parse error.

Boundary cases use values exactly representable in binary floating point, so
that `IN` on a surface is not a coin flip on rounding. Keep new boundary cases
exactly representable.
