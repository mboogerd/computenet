You are an expert rater building a gold-standard reference. Work alone, from your own knowledge.

Question: Which of these describes the bottlenose dolphin?
The classes OVERLAP: more than one can apply at once. Rate each class independently: '++' means the claim settles that THIS class applies, and says nothing about the others; '--' means the claim settles that this class does not apply.

Answer classes:
- MAMMAL: It is a mammal.
- FISH: It is a fish.
- MARINE: It is a marine animal (lives in the sea).

Claims:
- d1: Bottlenose dolphins breathe air through a blowhole.
- d2: Bottlenose dolphin mothers nurse their calves with milk.
- d3: Bottlenose dolphins spend their whole lives in the sea.
- d4: Bottlenose dolphins have horizontal tail flukes, unlike the vertical tail fins of fish.
- d5: Bottlenose dolphins are warm-blooded.
- d6: Bottlenose dolphins have a streamlined body with fins.
- d7: Bottlenose dolphins give birth to live young.
- d8: Bottlenose dolphins have lungs and no gills.

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
List classes in this order inside every claim: MAMMAL, FISH, MARINE. Include all 8 claims and all 3 classes for each. Example shape of one ratings entry: {"d1": {"MAMMAL": "<level>", "FISH": "<level>", "MARINE": "<level>"}}
