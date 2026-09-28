You are an expert rater building a gold-standard reference. Work alone, from your own knowledge.

Question: What was the primary cause of the collapse of Late Bronze Age civilisations in the eastern Mediterranean around 1200-1150 BCE?
The answer classes compete as the single PRIMARY cause; NONE is a live answer class meaning none of the listed causes was primary.

Answer classes:
- SP: Invasions and raids by the 'Sea Peoples'.
- DR: A prolonged drought and the famine it caused.
- EQ: A sequence of major earthquakes (an 'earthquake storm').
- NONE: None of these alone: no single one of the causes listed here was primary; the collapse had several interacting causes or a different cause.

Claims:
- b1: Pollen cores from the Sea of Galilee show a sharp decline in trees and cereal crops between about 1250 and 1100 BCE, indicating a long dry period.
- b2: Inscriptions of Ramesses III at Medinet Habu describe a coalition of seaborne peoples attacking Egypt around 1177 BCE.
- b3: Many of the destroyed cities show no arrowheads, weapons or unburied bodies of the kind that warfare usually leaves.
- b4: The migrations of the Sea Peoples were themselves most likely driven by famine in their homelands.
- b5: The destructions were spread over roughly fifty years rather than happening at once.
- b6: Late Bronze Age palace economies depended on long-distance trade in tin and copper, so a disruption in one region could cascade to others.
- b7: The last letters from the city of Ugarit report enemy ships burning its towns while its army and fleet were away.
- b8: Archaeoseismologists have identified earthquake damage from this period at several sites, including Mycenae and Tiryns.
- b9: Egypt and Assyria survived the period with their states intact.
- b10: No single cause can explain destructions at sites as far apart as Hattusa, Ugarit and Pylos.

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
List classes in this order inside every claim: SP, DR, EQ, NONE. Include all 10 claims and all 4 classes for each. Example shape of one ratings entry: {"b1": {"SP": "<level>", "DR": "<level>", "EQ": "<level>", "NONE": "<level>"}}
