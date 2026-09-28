"""Objection items for the absolute-vs-comparative gate (computenet-1ow0x, Hmin-pen).
`intent` is the author's design label (A=absolute, C=comparative, M=mixed); raters never see it.
Absolute = counts against the answer on its own terms (would still count if no rival were listed),
regardless of strength. Comparative = counts against it only by favouring a rival."""
import json
from pathlib import Path

Q = {
 "Q1": dict(q="What is the ultimate fate of the universe?", frame="The answers are mutually exclusive: exactly one is the fate.", cls={
   "HD": "Heat death (Big Freeze): the universe expands forever and cools towards a cold, dark, maximum-entropy state.",
   "BC": "Big Crunch: expansion eventually reverses and the universe recollapses.",
   "BR": "Big Rip: accelerating expansion grows without bound and eventually tears apart galaxies, stars and atoms."}, items=[
   ("Dark energy is a cosmological constant: its density stays exactly the same forever.", "BR", "A"),
   ("The expansion of the universe is accelerating now and will keep accelerating forever.", "BC", "A"),
   ("Dark energy has an equation-of-state parameter w that is less than -1 and stays below -1.", "HD", "A"),
   ("Dark energy will decay and turn negative within 20 billion years, halting and reversing the expansion.", "BR", "A"),
   ("A Big Rip needs dark energy whose density grows over time, and no known physical field behaves that way without instabilities.", "BR", "A"),
   ("Heat death is the outcome predicted by the simplest model that fits all current cosmological data (Lambda-CDM).", "BR", "C"),
   ("Big Rip models need more free parameters than a cosmological constant, so Occam's razor favours heat death.", "BR", "C"),
   ("Most cosmologists consider heat death the most likely fate of the universe.", "BC", "C"),
   ("The observed acceleration is fitted slightly better by a constant dark energy than by a growing one.", "BR", "C"),
   ("Heat death follows from well-tested thermodynamics, while a Big Crunch relies on speculative future changes in dark energy.", "BC", "C"),
   ("The universe is measured to be spatially flat to within 0.2%, while classic Big Crunch models assume a closed universe.", "BC", "M"),
   ("Recent survey results hint that dark energy is weakening over time.", "BR", "M"),
 ]),
 "Q2": dict(q="Which measure should a mid-sized city of about 500,000 people prioritise over the next five years to reduce car traffic in its centre?",
   frame="The question asks which ONE measure to prioritise, so the measures compete even though a city could do several.", cls={
   "CC": "A congestion charge on cars entering the centre.",
   "PT": "Expanding public transport: more frequent buses and new bus or tram lines.",
   "CY": "A network of protected cycle lanes.",
   "PK": "Parking reform: removing and pricing on-street parking in the centre."}, items=[
   ("National law in this country forbids municipalities from charging fees for the use of public roads.", "CC", "A"),
   ("The city's budget cannot fund any new bus or tram service in the next five years, and no outside funding is available.", "PT", "A"),
   ("The centre has almost no on-street parking; nearly all parking is in private garages the city cannot regulate.", "PK", "A"),
   ("Trials in comparable cities found that congestion charges did not reduce central car traffic at all.", "CC", "A"),
   ("Congestion charges are unpopular and have been voted down in referenda in several cities.", "CC", "A"),
   ("A congestion charge reduces central car traffic more per euro spent than any other measure.", "PT", "C"),
   ("Protected cycle lanes are much cheaper to build than new tram lines.", "PT", "C"),
   ("Parking reform can be implemented faster than a congestion charge.", "CC", "C"),
   ("Expanding public transport is more popular with voters than a congestion charge.", "CC", "C"),
   ("In cities of this size, cycling investments achieved larger traffic reductions than bus expansions.", "PT", "C"),
   ("A congestion charge also raises revenue that could fund the other measures.", "PK", "C"),
   ("Many residents live beyond cycling distance of the centre, so cycle lanes would change only a small share of car trips.", "CY", "M"),
   ("The centre's streets are narrow, so protected cycle lanes would require removing bus lanes as well as car lanes.", "CY", "M"),
 ]),
 "Q3": dict(q="What was the primary cause of the collapse of Late Bronze Age civilisations in the eastern Mediterranean around 1200-1150 BCE?",
   frame="The answers compete as the single PRIMARY cause.", cls={
   "SP": "Invasions and raids by the 'Sea Peoples'.",
   "DR": "A prolonged drought and the famine it caused.",
   "EQ": "A sequence of major earthquakes (an 'earthquake storm')."}, items=[
   ("Pollen cores show no drought in the eastern Mediterranean between 1250 and 1100 BCE.", "DR", "A"),
   ("Destruction layers at the affected cities show no signs of seismic damage.", "EQ", "A"),
   ("The Sea Peoples appear in Egyptian records only after most of the affected cities had already been abandoned.", "SP", "A"),
   ("Bronze Age cities routinely rebuilt within a generation after earthquakes.", "EQ", "A"),
   ("Egyptian inscriptions at Medinet Habu explicitly describe invasions by the Sea Peoples.", "DR", "C"),
   ("Most historians today give more weight to climate than to invasions.", "SP", "C"),
   ("Evidence for the drought is stronger and more widespread than evidence for an earthquake storm.", "EQ", "C"),
   ("The last letters from Ugarit describe enemy ships attacking the coast.", "EQ", "C"),
   ("Hittite texts from the period request urgent grain shipments from Egypt.", "SP", "C"),
   ("Drought evidence is found across the whole region, whereas earthquake damage is found at only some sites.", "EQ", "M"),
   ("The Sea Peoples were themselves probably refugees displaced by famine in their homelands.", "SP", "M"),
   ("Arrowheads are found embedded in the walls of several destroyed cities.", "EQ", "M"),
 ]),
 "Q5": dict(q="Which language should a five-person startup use for the backend of a new web SaaS product?",
   frame="The team will pick ONE backend language.", cls={
   "PY": "Python.", "GO": "Go.", "JV": "Java."}, items=[
   ("None of the five engineers knows Java, and the startup cannot afford to hire anyone who does.", "JV", "A"),
   ("The product depends on a numerical library that exists only as a Python package, with no bindings for Go.", "GO", "A"),
   ("Go's database tooling is immature, so the team would hand-write large amounts of data-access code.", "GO", "A"),
   ("The investors' contract forbids interpreted languages in production.", "PY", "A"),
   ("Go programs usually run faster than equivalent Python programs.", "PY", "C"),
   ("Python has more web-framework options than Go.", "GO", "C"),
   ("Java has a larger hiring pool than Go in most markets.", "GO", "C"),
   ("Python lets small teams ship a first version faster than Java does.", "JV", "C"),
   ("The team's two senior engineers each have ten years of Python experience.", "JV", "C"),
   ("Go produces a single static binary, which makes deployment simpler than with Python.", "PY", "C"),
   ("Java's verbosity slows down prototyping at a stage where speed matters most.", "JV", "M"),
   ("Python's performance would force a costly rewrite if the product reaches large scale.", "PY", "M"),
 ]),
 "Q6": dict(q="What caused the extinction of the non-avian dinosaurs 66 million years ago?",
   frame="The answers compete as the main cause.", cls={
   "AS": "The Chicxulub asteroid impact.", "DV": "Deccan Traps volcanism.", "CL": "Gradual long-term climate cooling."}, items=[
   ("The extinction was geologically instantaneous, taking a few thousand years or less.", "CL", "A"),
   ("The Deccan Traps eruptions began and ended more than five million years before the extinction.", "DV", "A"),
   ("No impact crater of sufficient size and the right age exists anywhere on Earth.", "AS", "A"),
   ("Dinosaur diversity was stable, not declining, in the ten million years before the extinction.", "CL", "A"),
   ("Shocked quartz found at the boundary layer is produced by impacts, not by volcanoes.", "DV", "C"),
   ("The asteroid hypothesis has more direct evidence than the volcanism hypothesis.", "DV", "C"),
   ("The Chicxulub crater is dated to within 30,000 years of the extinction.", "CL", "C"),
   ("Most paleontologists regard the impact as the main cause.", "DV", "C"),
   ("The iridium-rich layer at the boundary is found worldwide.", "DV", "C"),
   ("The largest pulse of Deccan eruptions came after the impact, not before it.", "DV", "M"),
   ("Mammals and birds survived the boundary while the non-avian dinosaurs did not.", "CL", "M"),
 ]),
 "Q7": dict(q="Who was the first European to reach the Americas?", frame="Exactly one of the listed people is the answer.", cls={
   "LE": "Leif Erikson.", "CO": "Christopher Columbus.", "JC": "John Cabot."}, items=[
   ("John Cabot's first voyage to North America took place in 1497, five years after Columbus's first landfall.", "JC", "A"),
   ("A Norse settlement at L'Anse aux Meadows in Newfoundland has been dated to around 1021 CE.", "CO", "A"),
   ("The Vinland sagas are wholly legendary: no Norse site in the Americas has ever been found.", "LE", "A"),
   ("Irish monks reached North America centuries before any Norse voyage.", "LE", "A"),
   ("Columbus is the name most schoolbooks give as the discoverer of America.", "LE", "C"),
   ("Columbus's voyages had far greater historical consequences than the Norse voyages.", "LE", "C"),
   ("The Norse presence in America is better documented than any voyage by Cabot.", "JC", "C"),
   ("Columbus's first landfall is precisely dated to 12 October 1492.", "JC", "C"),
   ("Leif Erikson's voyage has archaeological support, while Columbus's claim to be first rests on his fame.", "CO", "C"),
   ("Some historians argue that Bristol fishermen, from whose port Cabot later sailed, reached Newfoundland before 1492.", "CO", "M"),
   ("Leif Erikson was born in Iceland, a Norse settlement some count as part of North America.", "LE", "M"),
 ]),
}

def items():
    out = []
    for qid, q in Q.items():
        for i, (claim, cls, intent) in enumerate(q["items"]):
            out.append(dict(id=f"{qid}-{i:02d}", qid=qid, claim=claim, cls=cls, intent=intent))
    return out

if __name__ == "__main__":
    it = items(); from collections import Counter
    print(len(it), Counter(x["intent"] for x in it))
    Path(__file__).with_name("items.json").write_text(json.dumps(it, indent=1))
