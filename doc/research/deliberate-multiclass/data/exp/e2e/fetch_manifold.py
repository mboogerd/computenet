import json, urllib.request, urllib.parse, datetime, time
B="https://api.manifold.markets/v0"
def get(u):
    for i in range(3):
        try:
            with urllib.request.urlopen(u, timeout=60) as r: return json.loads(r.read())
        except Exception as e: time.sleep(1); err=e
    raise err
seen={}
terms=["","2026","winner","who will win","which","election","award","champion","2026 winner","prize","september 2026","august 2026","july 2026","world cup","release","best picture","emmy","primary","grand prix","open 2026","tour de france","nobel","hurricane","chart","billboard","f1","nba","mlb","nfl","tennis","golf","box office","pope","prime minister","president","gdp","inflation","rate","ai model","benchmark"]
sorts=["newest","most-popular","score","close-date"]
for t in terms:
  for s in sorts:
    for off in (0,100,200):
        q=urllib.parse.urlencode({"term":t,"filter":"resolved","contractType":"MULTIPLE_CHOICE","sort":s,"limit":100,"offset":off})
        try: d=get(f"{B}/search-markets?{q}")
        except Exception as e: print("fail",t,s,e); break
        for m in d: seen[m["id"]]=m
        if len(d)<100: break
print(len(seen))
lo=datetime.datetime(2026,7,1).timestamp()*1000
cands=[m for m in seen.values() if (m.get("resolutionTime") or 0)>=lo and (m.get("closeTime") or 0)>=lo and m.get("uniqueBettorCount",0)>=15 and m.get("resolution") not in ("CANCEL",None)]
print(len(cands))
json.dump(cands, open("manifold_cands.json","w"))
for m in sorted(cands,key=lambda m:-m.get("uniqueBettorCount",0)):
    print(datetime.datetime.utcfromtimestamp(m["resolutionTime"]/1000).date(), m.get("uniqueBettorCount"), m["id"], m["question"][:120])
