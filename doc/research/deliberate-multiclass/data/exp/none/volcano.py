"""S3d: 3 plausible classes absolutely eliminated + 1 implausible un-objected class (min-D blind spot),
then add one moderate objection to the implausible class; and exact VoI of that prospective objection."""
import copy
from run import *
V4 = ["X", "Y", "Z", "V"]
base = dict(plaus={"X": .3, "Y": .3, "Z": .3, "V": .02}, bL=.9,
            listed=[con("X", V4, .9, .8), con("Y", V4, .9, .8), con("Z", V4, .9, .8)])
table("S3d three eliminated, implausible V (p=.02) un-objected", base, judges=((J0, "β0"),))
b2 = copy.deepcopy(base); b2["listed"].append(con("V", V4, .6, .6))
table("S3e + moderate absolute objection to V (e=.36)", b2, judges=((J0, "β0"),))
print("\n| candidate | P(Ω) S3d | P(Ω) S3e | exact VoI on P(Ω) of the prospective V-objection (resolve c to 1/0 w.p. .6) |\n|---|---|---|---|")
for cn in CANDS:
    a = pooled(cn, base, J0)[OM]; b = pooled(cn, b2, J0)[OM]
    b1 = copy.deepcopy(b2); b1["listed"][-1]["c"] = 1.0; b0 = copy.deepcopy(b2); b0["listed"][-1]["c"] = 0.0
    v = .6 * abs(pooled(cn, b1, J0)[OM] - b) + .4 * abs(pooled(cn, b0, J0)[OM] - b)
    print(f"| {cn} | {a:.3f} | {b:.3f} | {v:.3f} |")
