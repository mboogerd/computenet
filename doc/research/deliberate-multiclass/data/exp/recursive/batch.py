import json, glob, os, subprocess, sys
R = os.path.dirname(os.path.abspath(__file__))
def spent():
    return sum(json.load(open(f))["question"]["costUsd"] for f in glob.glob(f"{R}/runs/*/result.json"))
for qid in sys.argv[1:]:
    s = spent(); print("spent so far", round(s, 3), flush=True)
    if s > 17.5: print("STOP: budget guard before", qid); break
    if os.path.exists(f"{R}/runs/{qid}/result.json"): continue
    with open(f"{R}/runs/{qid}.drive.log", "w") as log:
        subprocess.run(["python3", f"{R}/drive.py", qid, "--max-claims", "90"], stdout=log, stderr=subprocess.STDOUT, cwd=R)
    print(qid, open(f"{R}/runs/{qid}.drive.log").read().splitlines()[-1][:160], flush=True)
print("BATCH DONE spent", round(spent(), 3))
