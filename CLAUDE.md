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
every method reduces to `distanceSquaredToAxisSegment`, which is the class's
square-root-free primitive:

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

## Invariants and validation

- The capsule's `radius` must be **positive**; the constructor rejects zero.
  A `Sphere`'s radius may be **zero** (it is then a point query, and the basis
  of one of the runner's invariants) but not negative. This asymmetry is
  intentional, and each rule lives in its own record's constructor, which is
  why `contains` and `intersects` are pure geometry that never throws.
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

Boundary cases use values exactly representable in binary floating point, so
that `IN` on a surface is not a coin flip on rounding. Keep new boundary cases
exactly representable.
