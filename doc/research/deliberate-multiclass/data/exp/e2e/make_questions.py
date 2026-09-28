"""Mechanical question set from Manifold markets (fetched 2026-09-28). Option rule, pre-registered
before any model call: answers in creator index order; if more than 8 answers, keep the first 7
non-Other answers + a catch-all. If the market has no Other and was truncated, add a catch-all.
Resolution used only for scoring. Binary (2-option) markets dropped."""
import json, re, datetime
DROP={"Izq86PpQq6","qQAEhSgQQQ"}
QTEXT={  # light tidying of titles only (no outcome info)
 "ygAPp8pRtL":"Who will win the 2026 Formula 1 Dutch Grand Prix?",
 "chRLAqAcqI":"Which show will win the 2026 Primetime Emmy Award for Outstanding Comedy Series?",
 "gCn5zQz6nl":"Which city will host the Eurovision Song Contest 2027?",
 "SzESlu6nyA":"Who will win the men's elite road race at the 2026 UCI Road World Championships?",
 "0ncEn65h8l":"Which team will win the League of Legends Mid-Season Invitational (MSI) 2026?",
 "N8ql0I2d9P":"Which team will win the 2026 MLB National League West division?",
 "sIc58zydQu":"Who will be the next UK Chancellor of the Exchequer after Rachel Reeves?",
 "hdN5d0pz9u":"Which party will the winner of the 2026 Greater Manchester mayoral by-election be from?",
 "9AN6Zp8CEp":"Which team will win the 2026 FIFA World Cup?",
}
EMO=re.compile("[\U0001F000-\U0001FFFF☀-➿‍️]")
def clean(t): return re.sub(r"\s+"," ",EMO.sub("",t)).strip()
ms=json.load(open("markets_raw.json"))
qs=[]
for m in ms:
    if m["id"] in DROP: continue
    ans=sorted(m["answers"],key=lambda a:a["index"])
    other=[a for a in ans if a.get("isOther") or clean(a["text"]).lower() in ("other","other driver")]
    named=[a for a in ans if a not in other]
    trunc=len(ans)>8
    keep=named[:7] if trunc else named
    classes={}; win=None
    for i,a in enumerate(keep):
        classes[f"C{i+1}"]=clean(a["text"])
        if a["id"]==m["resolution"]: win=f"C{i+1}"
    if other or trunc:
        classes["OTHER"]="Someone or something not listed among the other answers"
        if win is None: win="OTHER"
    assert win, m["id"]
    qs.append({"id":m["id"],"root_question":QTEXT.get(m["id"],clean(m["question"])),"classes":classes,"answer":win,
               "source":m["url"],"resolved":datetime.datetime.fromtimestamp(m["resolutionTime"]/1000,datetime.UTC).date().isoformat(),
               "bettors":m["uniqueBettorCount"],"truncated":trunc})
json.dump({"questions":qs},open("questions.json","w"),indent=1)
for q in qs: print(q["id"],len(q["classes"]),q["answer"],q["root_question"][:70],"|",q["classes"][q["answer"]][:30])
print(len(qs))
