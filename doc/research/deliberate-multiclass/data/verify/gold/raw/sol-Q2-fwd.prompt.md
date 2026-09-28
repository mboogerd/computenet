You are an expert rater building a gold-standard reference. Work alone, from your own knowledge.

Question: Which measure should a mid-sized city of about 500,000 people prioritise over the next five years to reduce car traffic in its centre?
The question asks which ONE measure to prioritise, so the classes compete as answers even though in reality a city could do several.

Answer classes:
- CC: A congestion charge on cars entering the centre.
- PT: Expanding public transport: more frequent buses and new bus or tram lines.
- CY: A network of protected cycle lanes.
- PK: Parking reform: removing and pricing on-street parking in the centre.

Claims:
- p1: Stockholm's congestion charge reduced traffic across the charging cordon by about 20 percent, and the reduction persisted for years.
- p2: Congestion charges take a larger share of income from lower-income drivers than from higher-income drivers.
- p3: The city's buses are more than half empty outside peak hours.
- p4: The city has no legal authority to levy a congestion charge, and obtaining it would require national legislation that is not planned.
- p5: In comparable European cities, a new tram line takes eight to twelve years from planning to opening.
- p6: Most car trips into the city centre are shorter than five kilometres.
- p7: The city is hilly and has winter temperatures below minus ten degrees Celsius for about two months a year.
- p8: About a third of car traffic in the centre consists of drivers circling in search of on-street parking.
- p9: Measures that discourage driving only reduce car traffic without backlash when good alternatives to the car already exist.
- p10: Most car traffic in the centre is through-traffic passing to other districts rather than trips ending in the centre.

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
List classes in this order inside every claim: CC, PT, CY, PK. Include all 10 claims and all 4 classes for each. Example shape of one ratings entry: {"p1": {"CC": "<level>", "PT": "<level>", "CY": "<level>", "PK": "<level>"}}
