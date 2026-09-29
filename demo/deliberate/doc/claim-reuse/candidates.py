# /// script
# dependencies = ["sentence-transformers", "numpy"]
# ///
"""Recall stage: embed every claim, keep each claim's top-3 neighbours in OTHER questions.

Writes data/pairs.json: {"topk": [[a, b, cos]], "control": [[a, b, cos]] (150 random
cross-question pairs at cos 0.55-0.70), "band_size": <pairs in that band>}.
Run: uv run candidates.py   (downloads BAAI/bge-small-en-v1.5 on first use)
"""
import random, sys
import numpy as np
from sentence_transformers import SentenceTransformer
import common

random.seed(6)
claims = common.load_claims()
E = SentenceTransformer("BAAI/bge-small-en-v1.5").encode([c["text"] for c in claims], normalize_embeddings=True, batch_size=64)
S = E @ E.T
q = np.array([c["q"] for c in claims])
cross = q[:, None] != q[None, :]

pairs = {}
for i in range(len(claims)):
    for j in np.argsort(-np.where(cross[i], S[i], -1))[:3]:
        a, b = sorted((i, int(j)))
        pairs[(a, b)] = float(S[a, b])
band = [(i, j) for i in range(len(claims)) for j in range(i + 1, len(claims)) if cross[i, j] and 0.55 <= S[i, j] < 0.70]
control = random.sample(band, 150)
common.save({"topk": [[a, b, s] for (a, b), s in pairs.items()],
             "control": [[a, b, float(S[a, b])] for a, b in control], "band_size": len(band)}, "pairs.json")
print(len(claims), "claims;", len(pairs), "top-3 pairs;", len(band), "pairs in the control band", file=sys.stderr)
