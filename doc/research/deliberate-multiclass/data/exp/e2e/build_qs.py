import json, urllib.request, datetime
ids="ht90hnS25U uqsu6qqRh8 z5uEgZt95z lPQQZcItEs p2hNQNl6Lp ygAPp8pRtL 9AN6Zp8CEp Cc0R2ycUU9 QE0l9dn2yt A2cEL82hnh ECEIOSh6ZO RstpUcdPzR SzESlu6nyA ds2IN0lQS9 hN9gtSNN8q CdOARZ8PE9 5QzLhsduNU CZhIzp2Zt8 Izq86PpQq6 Eyu5LLuc5s 6O5QlIUZlt chRLAqAcqI gCn5zQz6nl tSIlCOQt02 2nl5I6cN66 0ncEn65h8l N8ql0I2d9P 9uSOIZNcSI qQAEhSgQQQ hdN5d0pz9u sIc58zydQu 6OLPgSUPqc".split()
out=[]
for i in ids:
    m=json.loads(urllib.request.urlopen(f"https://api.manifold.markets/v0/market/{i}",timeout=60).read())
    ans=sorted(m["answers"],key=lambda a:a["index"])
    win=[a for a in ans if a["id"]==m["resolution"]]
    print(f"\n== {i} sum1={m.get('shouldAnswersSumToOne')} res={m['resolution']} n={len(ans)} {datetime.datetime.utcfromtimestamp(m['resolutionTime']/1000).date()} created={datetime.datetime.utcfromtimestamp(m['createdTime']/1000).date()}")
    print("  Q:",m["question"])
    print("  WIN:", win[0]["text"] if win else None)
    print("  opts:", [ (a['index'],a['text'][:40], a.get('isOther')) for a in ans][:14])
    out.append(m)
json.dump(out,open("markets_raw.json","w"))
