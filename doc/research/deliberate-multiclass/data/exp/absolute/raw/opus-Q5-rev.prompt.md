You are an expert rater building a gold-standard reference. Work alone, from your own knowledge.

Question: Which language should a five-person startup use for the backend of a new web SaaS product?
The team will pick ONE backend language.

Listed answers:
- PY: Python.
- GO: Go.
- JV: Java.

Each item below is a claim offered as an objection to ONE listed answer. Assume the claim is TRUE. Classify the KIND of objection:
  ABSOLUTE     it counts against that answer on its own terms: it shows the answer false, infeasible, ineffective or bad.
               It would still count against the answer even if no other answer existed (e.g. if this were the only
               answer anyone had proposed). This is about kind, not strength: a weak objection can be ABSOLUTE.
  COMPARATIVE  it counts against that answer ONLY by favouring a rival (a rival is better, cheaper, better supported,
               more popular, or has evidence for it). If no rival answer existed it would say nothing against this answer.
  MIXED        it genuinely has both parts, or reasonable experts would split between the two.
Also give "abs": your probability (0-100) that an expert panel would call it ABSOLUTE rather than COMPARATIVE.

Items:
- Q5-11: objection to PY: "Python's performance would force a costly rewrite if the product reaches large scale."
- Q5-10: objection to JV: "Java's verbosity slows down prototyping at a stage where speed matters most."
- Q5-09: objection to PY: "Go produces a single static binary, which makes deployment simpler than with Python."
- Q5-08: objection to JV: "The team's two senior engineers each have ten years of Python experience."
- Q5-07: objection to JV: "Python lets small teams ship a first version faster than Java does."
- Q5-06: objection to GO: "Java has a larger hiring pool than Go in most markets."
- Q5-05: objection to GO: "Python has more web-framework options than Go."
- Q5-04: objection to PY: "Go programs usually run faster than equivalent Python programs."
- Q5-03: objection to PY: "The investors' contract forbids interpreted languages in production."
- Q5-02: objection to GO: "Go's database tooling is immature, so the team would hand-write large amounts of data-access code."
- Q5-01: objection to GO: "The product depends on a numerical library that exists only as a Python package, with no bindings for Go."
- Q5-00: objection to JV: "None of the five engineers knows Java, and the startup cannot afford to hire anyone who does."

Reply with ONLY one JSON object, no prose, no code fence:
{"<item id>": {"label": "ABSOLUTE|COMPARATIVE|MIXED", "abs": <0-100>}, ...}
Include all 12 items.
