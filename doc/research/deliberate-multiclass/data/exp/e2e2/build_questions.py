#!/usr/bin/env python3
"""Build the knowledge-domain question set (fixed before any model call). Deterministic (seed 20260928).

Sources
  S  SuperGPQA (m-a-p/SuperGPQA, ODC-BY; arXiv 2502.14739), difficulty=hard, is_calculation=false, knowledge
     disciplines only. Fetched via the HF datasets-server API into supergpqa_hard.json. Options reduced to
     the correct answer + seeded-random distractors (never "all/none of the above"-style options).
     Three variants:  plain (K=6)   none-true (correct removed, 5 distractors + OTHER)   none-false (correct + 4 + OTHER)
  WB Wikidata (CC0) "Which of these was born first?": 5 notable composers/painters (sitelinks>=40/50) with day-precision
     birth dates inside a 4-year window; earliest leads the second by >= 120 days. Source = entity URIs + dates.
  WN Wikidata (CC0) "Which of these cities lies furthest north?": 5 cities (pop > 700k, distinct countries) whose
     coordinates lie within a 3-degree latitude band; the northernmost leads the second by >= 0.5 degrees.
"""
import json, random, re
from collections import defaultdict
from pathlib import Path
HERE = Path(__file__).resolve().parent
rnd = random.Random(20260928)
OTHER_TXT = "None of the other listed answers is correct"
BAD_OPT = re.compile(r"\b(all|none|both|neither) of (the )?(above|these|them|the above|first \w+ options?)\b|^(all|none) (are|is) correct|options are correct", re.I)
# Quality exclusions, decided by reading the question text only (before any model call):
EXCLUDE = {"8cb1a3d6": "ABC music notation options", "5df70786": "ABC music notation", "7509903e": "harmony shorthand, 3 options",
           "5ba6d322": "garbled LaTeX resonance structures", "fa18340c": "truncated option text",
           "b276a7c6": "ambiguous key: B7 / CD80 / B7.1 are synonyms",
           "2e7ecec8": "options overlap ('P = MC' vs 'Both 0 and P = MC')", "d64856ca": "synonymous options (retinitis pigmentosa)",
           "8fa4bfa2": "near-synonymous options (end-plate potential)", "b729d132": "several options satisfy the stem",
           "e1e8a807": "astrocytoma is a glioma (two options correct)"}
KEEP_DISC = {"History", "Philosophy", "Science", "Medicine", "Literature and Arts", "Military Science",
             "Sociology", "Agronomy", "Economics", "Education"}

def mk(qid, question, opts, answer_idx, source, domain, kind, other=None):
    """opts: list of option texts; answer_idx index into opts, or 'OTHER'. other: add OTHER option."""
    order = list(range(len(opts))); rnd.shuffle(order)
    classes = {}; ans = None
    for n, i in enumerate(order, 1):
        classes[f"C{n}"] = opts[i]
        if i == answer_idx: ans = f"C{n}"
    if other:
        classes["OTHER"] = OTHER_TXT
        if answer_idx == "OTHER": ans = "OTHER"
    assert ans
    return {"id": qid, "root_question": question, "classes": classes, "answer": ans, "source": source,
            "domain": "knowledge", "kind": kind}

import difflib
def _norm(o): return re.sub(r"[^a-z0-9()]+", " ", o.lower().replace("\\mathrm", "")).strip()
def _is_list_like(a, b):  # options that differ only in enumerations/numbers are legitimately distinct
    strip = lambda t: re.sub(r"\(?\b([ivx]+|[a-f]|\d+(\.\d+)?)\b\)?|\band\b|\bonly\b|true|false|valid|contingent|unsatisfiable|consistent|inconsistent|equivalent|nonequivalent", "", t)
    return strip(a).split() == strip(b).split()
def near(a, b):
    a, b = _norm(a), _norm(b)
    if a == b: return True
    if _is_list_like(a, b): return False
    ta, tb = set(a.split()), set(b.split())
    jac = len(ta & tb) / max(1, len(ta | tb))
    return difflib.SequenceMatcher(None, a, b).ratio() >= 0.7 or jac >= 0.5
def far_distractors(r):
    out = []
    ds = sorted(o for o in r["options"] if o != r["answer"] and not BAD_OPT.search(o))
    random.Random(r["uuid"]).shuffle(ds)
    for o in ds:
        if not near(o, r["answer"]) and not any(near(o, x) for x in out): out.append(o)
    return out

def supergpqa(n_plain=18, n_none_true=6, n_none_false=6):
    rows = json.load(open(HERE / "supergpqa_hard.json"))
    pool = [r for r in rows if not r["is_calculation"] and r["discipline"] in KEEP_DISC
            and len(r["question"]) <= 500 and all(len(o) <= 160 for o in r["options"])
            and r["answer"] in r["options"] and not BAD_OPT.search(r["answer"])
            and len(set(r["options"])) == len(r["options"])
            and r["uuid"][:8] not in EXCLUDE and not any("L:1/" in o for o in r["options"])
            and not re.search(r"\b(following (figure|table|passage|diagram)|as shown|the figure|the table)\b", r["question"], re.I)]
    pool = [r for r in pool if len(far_distractors(r)) >= 5]
    pool.sort(key=lambda r: r["uuid"]); rnd.shuffle(pool)
    by = defaultdict(list)  # stratify: round-robin over disciplines
    for r in pool: by[r["discipline"]].append(r)
    picked, discs, N = [], sorted(by), n_plain + n_none_true + n_none_false
    while len(picked) < N:
        for d in discs:
            if by[d] and len(picked) < N: picked.append(by[d].pop())
    out = []
    for j, r in enumerate(picked):
        distr = far_distractors(r)
        src = f"SuperGPQA uuid={r['uuid']} ({r['discipline']}/{r['field']}/{r['subfield']})"
        qid = "S" + r["uuid"][:8]
        if j < n_plain:
            out.append(mk(qid, r["question"], [r["answer"]] + distr[:5], 0, src, "knowledge", "S-plain"))
        elif j < n_plain + n_none_true:
            out.append(mk(qid, r["question"], distr[:5], "OTHER", src, "knowledge", "S-none-true", other=True))
        else:
            out.append(mk(qid, r["question"], [r["answer"]] + distr[:4], 0, src, "knowledge", "S-none-false", other=True))
    return out

def people(files, n):
    ps = []
    for f, occ in files:
        by = defaultdict(set); name = {}
        for r in json.load(open(HERE / f)):
            if re.match(r"Q\d+$", r["pLabel"]): continue
            by[r["p"]].add(r["dob"][:10]); name[r["p"]] = r["pLabel"]
        for p, ds in by.items():
            d = next(iter(ds))
            if len(ds) == 1 and not d.endswith("-01-01"): ps.append((d, name[p], p, occ))
    ps.sort(); used = set(); out = []
    from datetime import date
    D = lambda s: date(*map(int, s.split("-")))
    starts = list(range(len(ps))); rnd.shuffle(starts)
    for s in starts:
        if len(out) >= n: break
        occ = ps[s][3]
        cand = [x for x in ps[s:] if x[3] == occ and x[2] not in used and (D(x[0]) - D(ps[s][0])).days <= 4 * 365]
        if len(cand) < 5 or cand[0] != ps[s]: continue
        rest = cand[1:]; rnd.shuffle(rest); grp = [cand[0]] + rest[:4]
        grp.sort()
        if (D(grp[1][0]) - D(grp[0][0])).days < 120: continue
        used.update(x[2] for x in grp)
        noun = {"composer": "composers", "painter": "painters"}[occ]
        out.append(mk(f"WB{len(out):02d}", "Which of these people was born first (earliest date of birth)?",
                      [x[1] for x in grp], 0, "Wikidata P569: " + "; ".join(f"{x[1]} {x[0]} {x[2]}" for x in grp),
                      "knowledge", "WD-born-first"))
    return out

def cities(n):
    by = {}
    for r in json.load(open(HERE / "wd_cities.json")):
        m = re.match(r"Point\(([-\d.]+) ([-\d.]+)\)", r["coord"])
        if not m or re.match(r"Q\d+$", r["cLabel"]): continue
        e = by.setdefault(r["c"], {"lat": set(), "country": set(), "name": r["cLabel"]})
        e["lat"].add(round(float(m.group(2)), 2)); e["country"].add(r["countryLabel"])
    cs = sorted((min(v["lat"]), v["name"], next(iter(v["country"])), k) for k, v in by.items()
                if max(v["lat"]) - min(v["lat"]) < 0.05 and len(v["country"]) == 1)
    names = [c[1] for c in cs]
    cs = [c for c in cs if names.count(c[1]) == 1]
    used = set(); out = []
    idx = list(range(len(cs))); rnd.shuffle(idx)
    for top in idx:
        if len(out) >= n: break
        t = cs[top]
        if t[3] in used: continue
        cand = [c for c in cs if c[3] not in used and c[2] != t[2] and t[0] - 3.0 <= c[0] <= t[0] - 0.5]
        # distinct countries
        seen = {t[2]}; grp = [t]
        rnd.shuffle(cand)
        for c in cand:
            if c[2] not in seen: grp.append(c); seen.add(c[2])
            if len(grp) == 5: break
        if len(grp) < 5: continue
        used.update(c[3] for c in grp)
        out.append(mk(f"WN{len(out):02d}", "Which of these cities lies furthest north (highest latitude of its city centre)?",
                      [f"{c[1]} ({c[2]})" for c in grp], 0,
                      "Wikidata P625: " + "; ".join(f"{c[1]} {c[0]:.2f} {c[3]}" for c in grp), "knowledge", "WD-furthest-north"))
    return out

if __name__ == "__main__":
    qs = supergpqa() + people([("wd_composers.json", "composer"), ("wd_painters.json", "painter")], 10) + cities(10)
    json.dump({"note": __doc__, "seed": 20260928, "questions": qs}, open(HERE / "questions_knowledge.json", "w"), indent=1)
    from collections import Counter
    print(len(qs), Counter(q["kind"] for q in qs), Counter(q["answer"] for q in qs))
