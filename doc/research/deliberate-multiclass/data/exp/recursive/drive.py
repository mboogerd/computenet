"""Run one pinned question through the real deliberate backend to completion (or a 30-min kill).
usage: python3 drive.py <qid> [extra deliberate args...]"""
import json, os, signal, subprocess, sys, time, urllib.request, urllib.parse
R = os.path.dirname(os.path.abspath(__file__))
qid = sys.argv[1]; extra = sys.argv[2:]
pins = json.load(open(f"{R}/pins.json")); pin = pins[qid]
PORT = 8197
out = f"{R}/runs/{qid}"; os.makedirs(out, exist_ok=True)
env = dict(os.environ); env["PATH"] = f"{R}/bin:" + env["PATH"]; env["DELIBERATE_LOG_USAGE"] = "1"
args = [f"{R}/dist/bin/deliberate", str(PORT), "--data", f"{out}/data"] + extra
json.dump({"args": args, "started": time.time()}, open(f"{out}/settings.json", "w"))
log = open(f"{out}/server.log", "w")
srv = subprocess.Popen(args, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
def get(path):
    with urllib.request.urlopen(f"http://127.0.0.1:{PORT}{path}", timeout=20) as r: return r.read()
def kill():
    try: os.killpg(srv.pid, signal.SIGTERM)
    except Exception: pass
    try: srv.wait(20)
    except Exception:
        os.killpg(srv.pid, signal.SIGKILL)
try:
    for _ in range(120):
        try: get("/graph"); break
        except Exception: time.sleep(1)
    else: raise SystemExit("server did not come up")
    body = urllib.parse.urlencode({"text": pin["text"]}).encode()
    root = json.loads(urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{PORT}/question", data=body), timeout=30).read())["root"]
    t0 = time.time(); outcome = "done"; g = None
    while True:
        time.sleep(15)
        try: g = json.loads(get("/graph"))
        except Exception as e: print("graph err", e, flush=True); continue
        q = next(x for x in g["questions"] if x["root"] == root)
        el = time.time() - t0
        print(f"{el:6.0f}s claims={q['claims']} active={q['active']} cost=${q['costUsd']:.3f} stopped={q.get('stoppedBy')}", flush=True)
        if not q["active"] and el > 45: break
        if el > 1800: outcome = "killed_30min"; break
    json.dump(g, open(f"{out}/graph.json", "w"))
    q = next(x for x in g["questions"] if x["root"] == root)
    json.dump({"root": root, "outcome": outcome, "elapsed": time.time() - t0, "question": q}, open(f"{out}/result.json", "w"), indent=1)
    print("OUTCOME", outcome, "cost", q["costUsd"], "claims", q["claims"], "framing", json.dumps(q.get("framing"))[:600])
finally:
    kill()
