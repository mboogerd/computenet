# Feature unlock: per-item vote tallies & "top wanted" via GroupBy

## Origin
`demo/shopping` models a vote as membership in a global set: `voteOps.add(item)`
into a per-user `SetCell`, all merged by `votesUnion`. Consequences observed while
testing:
- You can only ever know *whether* an item has a vote, never **how many** distinct
  users want it. `voteCount` is "number of distinct voted items", not "votes on the
  most-wanted item".
- Voting twice by the same user is (correctly) idempotent, but there's no way to
  rank items by demand — the single most useful thing a shared shopping list wants
  ("what does the household most want?").

The framework already has the operator to do this — `GroupByCell` (keyed
incremental aggregation → `MapDelta<K,A>`) — but the demo can't use it because its
vote data carries no key to group by: a vote is just the item string, with the
voter identity thrown away at the union.

## What it is
A small data-model change in the demo plus (its only framework dependency) a sink
for `MapDelta` — surfacing **per-item vote counts** and a **"Top wanted"** view:
- model a vote as the pair `(item, user)` in the votes set (voter identity kept),
- `GroupByCell(keyFn = { (item, _) -> item }, aggregator = count())` →
  `MapDelta<Item, Int>` = distinct voters per item,
- render "Milk ×3", and a `Top wanted` list sorted by count.

## Why it fits the framework
- It exercises an already-landed primitive (`GroupByCell`, M11.3) that no demo
  currently drives end to end — good coverage for the operator library the roadmap
  built for exactly this "developer payoff".
- Distinct-voter counting is the textbook case for keyed aggregation over an
  OR-set: membership flips (a user's first vote for an item) drive the count;
  re-votes are absorbed as tag churn (GroupByCell already does "membership flips,
  not tag churn, drive insert/retract"). The semantics line up with zero new cell
  code — the value is proving and surfacing it.
- The only true framework gap it reveals is the **`MapDelta` sink** (see
  `materialize-and-observe-sink.md`): there is `SetHubCell`/`CounterHubCell` but
  nothing to materialize a `MapDelta` stream for the UI. Delivering this feature is
  the forcing function to add that fold.

## Solution sketch
- Votes writer holds `Pair<String,String>` (item,user) instead of `String`.
- `wanted`/`voteCount` continue from the item-projection of votes ∩ items (so the
  existing intersection still works on item identity).
- Add `GroupByCell` on the votes stream keyed by item, counted; materialize its
  `MapDelta<String,Int>` via the new sink into `tally: Map<String,Int>`.
- State JSON gains `"tally": {item: n}`; the page renders counts and a
  count-sorted "Top wanted" list.

## Inputs / outputs
- **Input:** `(item, user)` vote events; `add` is idempotent per (item,user).
- **Output:** `tally: Map<item, distinctVoters>`; a descending-by-count "Top
  wanted" ordering; existing `wanted`/`voteCount` unchanged in meaning.

## Acceptance criteria
- Two distinct users voting the same item → tally `{item: 2}`; the same user
  voting twice → still `{item: 1}` (distinct-voter semantics).
- Removing an item drops it from `wanted`/`Top wanted`; whether its raw tally is
  retained or hidden follows the same "retained but not counted" rule already
  applied to `voteCount` (design decision, documented).
- A late-joining SSE tab receives the current tally immediately (GroupBy
  `onLinked` catch-up through the `MapDelta` sink).
- Demonstrates `GroupByCell` + the `MapDelta` sink with a focused test; no new
  kernel cell types introduced.
