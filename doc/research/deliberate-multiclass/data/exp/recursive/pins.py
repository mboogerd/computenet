import json, os
E = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "e2e", "questions.json")
Q = {x["id"]: x for x in json.load(open(E))["questions"]}
def P(qid, text, pos, collapse):
    # pos: list of (class id, position sentence); collapse: {e2e class id -> our class id}
    return qid, {"text": text, "positions": pos, "collapse": collapse, "answer": collapse[Q[qid]["answer"]],
                 "e2e_classes": list(Q[qid]["classes"])}
def ident(qid, extra=None):
    d = {c: c for c in Q[qid]["classes"]}; d.update(extra or {}); return d
pins = dict([
 P("9AN6Zp8CEp", "Which team will win the 2026 FIFA World Cup?",
   [("C1", "Spain wins the 2026 FIFA World Cup."), ("C2", "France wins the 2026 FIFA World Cup."),
    ("C3", "England wins the 2026 FIFA World Cup."), ("C4", "Argentina wins the 2026 FIFA World Cup."),
    ("OTHER", "A team other than Spain, France, England and Argentina wins the 2026 FIFA World Cup.")], ident("9AN6Zp8CEp")),
 P("CZhIzp2Zt8", "Who will win the 2026 Democratic primary for US Senator in Michigan?",
   [("C1", "Abdul El-Sayed wins the 2026 Democratic primary for US Senator in Michigan."),
    ("C2", "Haley Stevens wins the 2026 Democratic primary for US Senator in Michigan."),
    ("C3", "Mallory McMorrow wins the 2026 Democratic primary for US Senator in Michigan.")], ident("CZhIzp2Zt8")),
 P("Eyu5LLuc5s", "Who will be the 2026 Republican nominee for Governor of Florida?",
   [("C1", "Byron Donalds is the 2026 Republican nominee for Governor of Florida."),
    ("C2", "James Fishback is the 2026 Republican nominee for Governor of Florida."),
    ("C3", "Jay Collins is the 2026 Republican nominee for Governor of Florida."),
    ("OTHER", "Someone other than Byron Donalds, James Fishback and Jay Collins is the 2026 Republican nominee for Governor of Florida.")], ident("Eyu5LLuc5s")),
 P("gCn5zQz6nl", "Which city will host the Eurovision Song Contest 2027?",
   [("C1", "Sofia hosts the Eurovision Song Contest 2027."), ("C2", "Plovdiv hosts the Eurovision Song Contest 2027."),
    ("C3", "Varna hosts the Eurovision Song Contest 2027."), ("C4", "Burgas hosts the Eurovision Song Contest 2027."),
    ("OTHER", "A city other than Sofia, Plovdiv, Varna and Burgas hosts the Eurovision Song Contest 2027.")], ident("gCn5zQz6nl")),
 P("N8ql0I2d9P", "Which team will win the 2026 MLB National League West division?",
   [("C1", "The Arizona Diamondbacks win the 2026 MLB National League West division."),
    ("C2", "The Colorado Rockies win the 2026 MLB National League West division."),
    ("C3", "The Los Angeles Dodgers win the 2026 MLB National League West division."),
    ("C4", "The San Diego Padres win the 2026 MLB National League West division."),
    ("C5", "The San Francisco Giants win the 2026 MLB National League West division.")], ident("N8ql0I2d9P")),
 P("9uSOIZNcSI", "Which ticket will win the 2026 FIDE presidential election?",
   [("C1", "The Jan Henric Buettner and Malcolm Pein ticket wins the 2026 FIDE presidential election."),
    ("C2", "The Wadim Rosenstein and Gordon Tang ticket wins the 2026 FIDE presidential election."),
    ("C3", "The Timur Turlov and Viswanathan Anand ticket wins the 2026 FIDE presidential election."),
    ("C4", "A ticket other than Buettner/Pein, Rosenstein/Tang and Turlov/Anand wins the 2026 FIDE presidential election.")], ident("9uSOIZNcSI")),
 P("5QzLhsduNU", "Which party will win the 2026 Clacton by-election?",
   [("C1", "Reform UK wins the 2026 Clacton by-election."), ("C2", "The Conservative Party wins the 2026 Clacton by-election."),
    ("C3", "The Labour Party wins the 2026 Clacton by-election."), ("C4", "Restore Britain wins the 2026 Clacton by-election."),
    ("OTHER", "A party other than Reform UK, the Conservatives, Labour and Restore Britain wins the 2026 Clacton by-election.")],
   ident("5QzLhsduNU", {"C5": "OTHER"})),
 P("QE0l9dn2yt", "Who will win the 2026 Wimbledon women's singles title?",
   [("C1", "Iga Swiatek wins the 2026 Wimbledon women's singles title."), ("C2", "Aryna Sabalenka wins the 2026 Wimbledon women's singles title."),
    ("C3", "Jessica Pegula wins the 2026 Wimbledon women's singles title."), ("C4", "Coco Gauff wins the 2026 Wimbledon women's singles title."),
    ("OTHER", "A player other than Iga Swiatek, Aryna Sabalenka, Jessica Pegula and Coco Gauff wins the 2026 Wimbledon women's singles title.")],
   ident("QE0l9dn2yt", {"C5": "OTHER", "C6": "OTHER", "C7": "OTHER"})),
])
json.dump(pins, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "pins.json"), "w"), indent=1)
for k, v in pins.items(): print(k, v["answer"], len(v["positions"]))
