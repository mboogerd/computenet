# Error classes

How skill text fails the agents that follow it. These were catalogued from
the /work skill before its distillation (PR #867). Triage and revision judge
friction against them. Each line gives the class, then its remedy.

**How rules are born**

- **E1 Incident-to-rule reflex.** Every friction becomes text and nothing removes text. → Apply the bar for text; incidents stay in beads.
- **E2 Remedy-induced friction.** A fix causes the next incident, for example when its example breaks another rule. → Examples obey every rule; each rule has one owner.

**How rules are phrased**

- **E3 Codified exception.** A one-off situation becomes a standing rule that a capable agent would have reasoned through. → State the goal and the constraint.
- **E4 Literal-reading patch chain.** A rule is worded too broadly or too narrowly, then patched with "except when…". → Rephrase it once as a purpose plus a constraint.
- **E5 Open-class enumeration.** One hazard is written as a growing list of members. → Give the principle and at most two examples, plus a check that catches members not on the list.
- **E6 Prose against non-volitional behaviour.** The text asks the agent to perceive elapsed time, notice a stall or not end its turn. → Point at a script that measures it, and detect after the fact.
- **E7 Gatekeeper folklore.** Permission or harness refusals are recorded as stable facts. → Don't retry a refused command in disguise; do the permitted equivalent or hand the command back.
- **E8 Calibration constants.** Magic numbers appear without rationale, or in tension with each other. → Keep only numbers that a script uses or policy fixes, stated once.
- **E9 Judgment prohibition.** All judgment is forbidden because judgment failed once. → Forbid the harmful action, not the reasoning.

**How the text stays consistent**

- **E10 Duplication → drift → contradiction.** The same rule lives in several files and the copies diverge. → Keep one full statement; elsewhere use one line and a link.
- **E11 Wrong prescription.** A command, flag, path or fact is false as written. → Check it against the tool and fix it.
- **E12 Splice damage.** Insertions leave orphaned referents and stale counts. → Rewrite whole sections.
- **E13 Stale or unresolved text.** Open disputes, retractions or dated counts remain inside instructions. → Decide the question, or remove the text.

**What the reader pays**

- **E14 Non-instructional content.** Narrative, rule genealogy, bead ids or defensive argument sit in the text. → Allow at most a short "because" clause; history lives in git and beads.
- **E15 Overload and emphasis inflation.** There are too many rules, bold is everywhere, and files are too long to read. → Short files; bold only for hard constraints.
- **E16 Misplacement.** The rule sits in the file of the role that filed it, not the role that trips on it. → Place it with the acting role.

**Where text meets mechanism**

- **E17 Prose standing in for a mechanism.** A deterministic procedure is written out as steps the agent must remember. → Script closed classes; keep prose for judgment.
- **E18 Tool output needing caveats.** Prose explains how to read misleading output. → Fix the output.
- **E19 Authority conflict.** Instruction sources disagree. → Fix the conflict at the source.
