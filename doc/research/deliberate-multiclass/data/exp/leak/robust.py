from lib import *
import lib
prior = load_prior()
base=[PILOT/"responses.jsonl"]; B=["responses_b.jsonl"]
vals = {**collect(base,"ISO",["compat","elim","lik","bear"]), **collect(B,"B",["delta","pin","instr","instrx","cinstr"])}
plc = collect(B,"PLC",list(vals)); plq={}
for s,d in plc.items():
    acc={}
    for (qp,c),x in d.items(): acc.setdefault((qp.split("/")[0],c),[]).append(x)
    plq[s]={k:mean(x) for k,x in acc.items()}
def rank(xs):
    o=sorted(range(len(xs)),key=lambda i:xs[i]); r=[0]*len(xs); i=0
    while i<len(o):
        j=i
        while j+1<len(o) and xs[o[j+1]]==xs[o[i]]: j+=1
        for t in range(i,j+1): r[o[t]]=(i+j)/2
        i=j+1
    return r
for gname in ("majority","original"):
    g=load_gold(gname); print("gold",gname)
    zero=[k for k in g["refs"] if g["refs"][k]=="0" and k not in g["contested"]]
    for s,cn in [("compat","raw"),("compat","placebo-diff (logit)"),("compat","class-z (LOO)"),("elim","raw"),("lik","raw"),("lik","lik / class-mean"),("bear","raw"),("delta","raw"),("pin","raw"),("instr","raw"),("instrx","raw"),("cinstr","raw")]:
        sig,_=corrections(vals[s],g,prior,s,plq.get(s))[cn]
        px=[prior[(g["q_of"][k[0]],k[1])] for k in zero]; py=[sig[k] for k in zero]
        r=pearson(px,py); rs=pearson(rank(px),rank(py))
        jk=[pearson([p for p,k in zip(px,zero) if k[0]!=c],[y for y,k in zip(py,zero) if k[0]!=c]) for c in sorted({k[0] for k in zero})]
        noc7=pearson([p for p,k in zip(px,zero) if k[0]!="c7"],[y for y,k in zip(py,zero) if k[0]!="c7"])
        print(f"  {s:7}{cn:22} r {r:5.2f} spearman {rs:5.2f} jackknife-by-claim [{min(jk):5.2f},{max(jk):5.2f}] without c7 {noc7:5.2f}")
