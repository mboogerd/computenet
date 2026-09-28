## Verdicts

**C1 — REFUTED.** The reported test does not cover “each layer.” [combine.py](/private/tmp/claude-503/-Users-merlijn-Documents-local-projects-computenet/203e4fbc-0355-41f9-a52e-dd5404776b66/scratchpad/combine/combine.py:167) tests only LL-WoE and LL-MLP against locally reimplemented formulas. It never tests WLO or JNB, nor calls Kotlin.

- WoE constants match Kotlin for the sampled domain: \(e=s c\), \(e_{\max}=.7,p=2,k=1.2,\alpha=1\).
- MLP also matches for complementary binary positions.
- WLO is absent; matching it requires \(w(e)=e^{1.3},p=2,k=2.4\).
- JNB cannot be represented from scalar \(e=sc\): Kotlin retains strength and credence separately. Equal-product examples give different energies: \((s,c)=(.8,.5)\to1.03949\), while \((.5,.8)\to.65552\), despite \(sc=.4\).
- Kotlin clamps bases to \([.01,.99]\); LL uses raw \(\log\pi_k\). Sampling bases only in \([.05,.95]\) hides this.
- Exactness also requires \(\pi=(b,1-b)\); `prior()` instead normalizes independently elicited class plausibilities.

Open-world marginal exactness fails immediately: with \(b=.5\), no evidence and \(\pi_\Omega=.05\), shares are \((.47619,.47619,.04762)\), not \((.5,.5)\). Only the conditional A:B odds remain unchanged when Ω is unjudged.

**C2 — CONFIRMED, narrowly.** For crisp binary, one-sided evidence, conjunctive simple supports give \(E=1-\prod(1-e_i)\). Base-rate BetP yields \(b+(1-b)E\) for supports or \(b(1-E)\) for attacks—exactly DF-QuAD. Two-sided evidence diverges: with \(b=.3,E_s=.8,E_a=.4\), Dempster gives
\[
(.8(.6)+.3(.2)(.6))/(1-.32)=.75882,
\]
while DF-QuAD gives \(.3+.7(.8-.4)=.58\).

**C3 — CONFIRMED.** Complementary logits are \(l,-l\), so T=1 produces \(\operatorname{softmax}(l,-l)=\sigma(2l)\); T=2 returns \(\sigma(l)=p\). Normalized odds for \((.15,.2,.7)\) are \((.06394,.09059,.84547)\). T=2 is principled only for complementary binary positions, not general \(K\).

**C4 — REFUTED as stated.** The identity is exact only if:

1. current node value equals its resolution probability \(p\);
2. the root/margin is affine over the entire move to 0 or 1;
3. \(g\) is the corresponding constant derivative.

Then \(E|\Delta(2r-1)|=|g|4p(1-p)\). The [analytic check](/private/tmp/claude-503/-Users-merlijn-Documents-local-projects-computenet/203e4fbc-0355-41f9-a52e-dd5404776b66/scratchpad/voi/voi_categorical.py:194) merely asserts this assumed affine algebra; it never invokes `measures()` or a combiner.

Nonlinear exact combination breaks it. One WoE support, base .5, current \(p=.5\), gives root values \(F(0)=.5,F(.5)=.69673,F(1)=.80919\): exact eM \(=.30919\), versus derivative formula \(=.50711\).

Today’s code is not this proposed calculation: `p` is Jev plausibility, `g` is the consensus-headline gradient dotted with per-layer sensitivity, missing sensitivity falls back to .5, and unjudged \(p\) gets factor 1. If propagated credence is \(q\ne p\), even an affine map gives \(2|g|[p(1-q)+(1-p)q]\), not \(4|g|p(1-p)\).

**C5 — PARTIAL.** Entropy gradient is exactly zero at a uniform tie, but exact endpoint entropy need not be. Tail-over-leader ranking is rule-dependent: Dirichlet near-tie has tail eH .039 versus leader .019, while softmax has .070 versus .147.

The reported eM pattern is not general either:

- softmax near-tie: leader .291, surge .225;
- Dirichlet contender: anti .037, surge .025;
- likelihood contender: anti .405, surge .041.

eM behaves poorly at arbitrary multiway ties, where the baseline leader is tie-order dependent; for broad claims moving several leading classes together; at energy clamps, where local \(g=0\) despite a large false-resolution move; and when Ω is a tail class, since Ω-specific uncertainty can remain invisible until it approaches second place.

**C6 — PARTIAL.** \(m(\varnothing)\) is a valid conflict measure, but not a diagnostic of *why* conflict occurred. Five routine supports and five routine attacks of energy .3 yield
\[
(1-.7^5)^2=.6921
\]
empty mass. Conversely, arbitrarily many eliminating claims whose compatible sets all contain one surviving class produce exactly zero conflict. Thus it cannot distinguish disagreement, none-of-these, or a false exclusivity assumption.

**C7 — REFUTED if linearisation is treated as accurate.** Errors can be large:

- Two-level WoE support chain at \(p=.5\): exact eM .11246 versus linear .31231—178% high.
- WoE clamp at current \(p=.8>.7\): local derivative and predicted VoI are zero, but false resolution gives exact eM .12368.
- DF-QuAD with fixed attack .6 and current support .5 crosses its branch. At base .2, exact eM is .44 versus linear .20; at base .8, exact .56 versus .80.

## Script bugs affecting conclusions

- `continuity()` omits WLO/JNB and masks the .01 base clamp.
- `bayesVE` is not Kotlin JNB.
- `mass_of()` loses mass when every compatibility is zero instead of assigning \(e\) to \(\varnothing\), corrupting conflict/none-of-these cases.
- Model A omits Ω, so open-world rows compare different frames.
- VoI fixes every candidate at \(p=.5\), silently removes SURGE from most Dirichlet scenarios, and caps its likelihood multiplier at 1.5 despite declaring 4.5. These weaken the claimed ranking generality.