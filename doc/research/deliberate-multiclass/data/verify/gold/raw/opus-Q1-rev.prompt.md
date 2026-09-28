You are an expert rater building a gold-standard reference. Work alone, from your own knowledge.

Question: What is the ultimate fate of the universe?
The answer classes are treated as mutually exclusive alternatives: exactly one of them is the answer.

Answer classes:
- BR: Big Rip: accelerating expansion grows without bound and eventually tears apart galaxies, stars and atoms.
- BC: Big Crunch: expansion eventually reverses and the universe recollapses.
- HD: Heat death (Big Freeze): the universe expands forever and cools towards a cold, dark, maximum-entropy state.

Claims:
- c1: Observations of distant Type Ia supernovae show that the expansion of the universe is currently accelerating.
- c2: Dark energy is a cosmological constant: its density stays exactly the same forever.
- c3: Dark energy has an equation-of-state parameter w that is less than -1 and stays below -1.
- c4: Baryon acoustic oscillation measurements from the DESI survey hint that the density of dark energy is decreasing over time.
- c5: The average density of matter in the universe is well below the critical density needed for gravity alone to halt the expansion.
- c6: Measurements of the cosmic microwave background show that space is flat to within about 0.2 percent.
- c7: Gravitationally bound systems such as galaxies and the Solar System are not being pulled apart by cosmic expansion today.
- c8: The expansion of the universe will eventually stop and reverse into a contraction.
- c9: The universe will last long enough for all stars to stop forming and for black holes to evaporate through Hawking radiation.
- c10: Current cosmological data cannot distinguish dark energy that is exactly constant from dark energy that changes slowly.
- c11: Dark energy with w below -1 would violate the null energy condition, which many physicists regard as a sign that it is unphysical.

For EVERY (claim, class) cell, assume the claim is TRUE and rate how it bears on that class being the answer, with exactly one of five codes:
  "--"  rules the class out (with the claim true, the class could not be the answer except under exceptional conditions)
  "-"   counts against the class, without ruling it out
  "0"   no bearing: makes the class neither more nor less likely
  "+"   counts for the class, without settling it
  "++"  settles it (with the claim true, the class would be the answer except under exceptional conditions)
Rate only what the claim, assumed true, changes; not whether the claim is actually true and not how likely the class is overall.

For any cell you find genuinely contestable (reasonable experts could pick a different code), give a short reason naming the alternative code.

Reply with ONLY one JSON object, no prose, no code fence, of this form:
{"ratings": {"<claim id>": {"<class id>": "<code>", ...}, ...},
  "contested": {"<claim id>": {"<class id>": "<short reason>"}, ...}}
List classes in this order inside every claim: BR, BC, HD. Include all 11 claims and all 3 classes for each. Example shape of one ratings entry: {"c1": {"BR": "<level>", "BC": "<level>", "HD": "<level>"}}
