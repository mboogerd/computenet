import json, urllib.request, urllib.parse, sys
def sparql(q):
    u="https://query.wikidata.org/sparql?"+urllib.parse.urlencode({"query":q,"format":"json"})
    req=urllib.request.Request(u,headers={"User-Agent":"computenet-research/0.1 (research script; contact mlboogerd)"})
    j=json.loads(urllib.request.urlopen(req,timeout=120).read())
    return [{k:v["value"] for k,v in b.items()} for b in j["results"]["bindings"]]
