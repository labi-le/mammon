# Review loop — the evidence

> **When to read this:** Read before your FIRST edit to production code, to a contract
> (a public API surface, a manifest declaration, a resource or string other code
> references, a produced artifact such as an APK), or to a document agents act on.
> `AGENTS.md` carries the rules; this file carries why each one exists and what shipping
> without it has cost on other projects.

## Workflow — mandatory, not advisory

Every change to production code, to a contract, or to a document agents act on
(`AGENTS.md`, `routes.md`, `README.md`, the guides) MUST pass through this loop:

> **implement → review (performance + architecture, as SEPARATE passes) → fix → repeat**

**Write the agreement down BEFORE the first implement step** — a numbered list of the
agreed points, plus what is explicitly out of scope, so a round is not spent proposing
redesigns nobody asked for. Without that list "100% of what was agreed" is unfalsifiable
in both directions: a reviewer can assert a point was agreed, an implementer can assert a
dropped point never was, and neither can be disproved.

The loop terminates on two conditions, both required: a full round returns **zero** open
findings, and **every numbered point is either implemented or struck by explicit
agreement**, the strike recorded in the list. Not "the happy path works". Not "tests
pass".

**Proportionality.** The gate is risk, not diff size. A low-risk change — docs-only,
config-only — takes ONE pass, and that pass is the ARCHITECTURE one: a document or a
manifest declaration that points at the wrong seam sends the next agent there, and
neither has a hot path. Never leave which pass survives to the agent's guess. Documents
agents act on are IN scope precisely because they fail this way — this very section is a
docs-only change, it was given a review pass, and it needed one: two of its load-bearing
claims were false as first drafted, taken from a report about an attempt that was
rejected and never landed, so no reader could check them. The termination conditions
NEVER relax: a one-line change with an open finding is not done either.

**A finding may be REFUTED, not only fixed — and the loop must allow it.** A round is
clean when every finding is either fixed or disproved with evidence, and a wrong finding
implemented is a regression the process invited. Both halves are real. On a past project
a reviewer demanded deleting a validation because the parsing library "obviously
enforced" it — measured, the library accepted zero, negative, and far-out-of-range
values silently, and the check was doing real work. Another demanded rejecting an input
whose first port field parsed empty — measured, the consumer skipped the empty field and
used a later valid one, so the demanded rejection would have discarded working
configurations. Verify a finding against the code before implementing it; say so with
evidence when it is wrong. When the refutation is ITSELF disputed, neither side closes
the finding unilaterally: it goes to whoever owns the numbered list — the agent that set
the scope — who rules and records the ruling in that list.

**A green suite is not evidence, and MUST NEVER be reported as a review result.** A
review earns its cost only by trying to break the change; mutation is the cheapest way
and is expected — revert the fix, confirm the new test fails, restore. Mutation has
repeatedly found tests that could not fail at all: on one branch, neutering BOTH
production branches of a fold left the package green behind three tests that looked like
coverage, and replacing a comparator's body with a trivially different key also left
every ranked case passing identically. Run mutations against the unit tests:

```sh
nix-shell --run './gradlew :app:testDebugUnitTest'
```

**Mutate in a `git archive` export under `/tmp`, never in the shared worktree.** A
mutation is a deliberately wrong tree and concurrent agents are the normal mode here, so
a peer reading the shared checkout mid-round sees a defect that does not exist. Isolate:

```sh
tmp=$(mktemp -d /tmp/mammon-mut.XXXXXX)
git -C /home/labile/GolandProjects/mammon archive HEAD | tar -x -C "$tmp"
```

The restore is not done until an empty `git diff` against the pre-mutation tree says so,
and that check MUST run before the round is reported. Isolation makes the empty-diff
check vacuous rather than optional — and a NEW file (untracked work) has no `git diff`
to be empty at all, so there the check is `cmp` against a pre-mutation copy. Figures
measured in an export tree describe that tree, which is why mutation numbers never reach
a document.

**Why review at all when CI is green.** A filter on a past project shipped completely
inert and stayed that way across releases while builds, the linter and the full test
suite were clean the whole time: it stopped matching at the very first character its own
input source had already normalized away, and an upstream stage guaranteed every real
input arrived in exactly that shape. Nothing dropped, nothing warned, and the affected
counter read 0, which an operator reads as "the pipeline was right" — the opposite of
the truth. No test caught it, because every fixture used the pre-normalization shape.
Green automation verifies the behaviors someone thought to exercise; it says nothing
about the ones nobody did.

**Why performance is its OWN pass.** A number that moved is not a regression until the
changed code is shown to be on its path. On one change the benchmark that moved most fed
inputs that never reached the edited function — what moved was code layout, not cost.
Telling that apart from real cost takes running the suspect path in isolation, not
reading the summary line. Without that pass a change either ships a regression or gets
rewritten chasing a phantom. The inverse is just as real: sizes that grow stepwise with
a stored record's width, paid twice before anyone noticed, taught the rule below.
**Tracked metrics are the binding constraint:** a regression in the metrics this project
tracks — lint findings (`./gradlew :app:lintDebug` must pass with ZERO new findings;
a plain exit-code gate, deliberately no baseline file exists to hide growth behind),
APK size once it starts being measured, benchmark numbers once benchmarks exist — is a
BLOCKING finding the change MUST justify and the reviewer MUST accept before the round
closes. It is a finding, not a prohibition, because some payments are deliberate: a new
dependency or a shipped native binary grows the APK on purpose. An agent reading the
rule as absolute would have blocked that. Wall-clock numbers are noisy and mean nothing
without a control; treat small deltas as noise until shown real.

**Why architecture is its OWN pass.** One change on a past project fixed, in a single
commit, every individual place a value was matched against a stale label — each site
locally correct, slice-scoped review with nothing left to say. It still left a seam only
a whole-module reader could see: a shared in-memory map kept the old representation, so
results reached downstream stages in whichever order iteration happened to emit,
silently undoing a best-of-N choice made upstream moments earlier. A reviewer given one
slice structurally cannot see that; someone MUST look at the whole seam, especially
where parallel work merges.

**Why "100% of what was agreed" is its own condition.** "It works" is not "it is done".
All of these passed a working-feature check and were still defects: a published output
kept its pre-transform value while the internal counter counted post-transform, so the
two disagreed by construction; a metric rode a field reused from an unrelated panel, so
it appeared under a heading that described something the feature never did; a test
exercised only the tie-break branch, so the primary sort key could be deleted with the
suite green. Each violates a numbered point nobody struck.

**Comments are a first-class review target,** not polish — and so are the documents,
which rot the same way with nothing compiling against them. The recurring defect is the
true-when-written claim. A routes file on a past project kept pointing at a helper
another change had deleted, and survived a dedicated staleness pass that rewrote three
other lines around it. A comment claimed an endpoint answered a fixed byte count; the
endpoint echoed request-dependent bytes back, so the claim was false for every distinct
request. Per the house rule, a stale comment is worse than none: a review that approves
the code and ignores its comments — or the docs its change falsified — has not finished.
