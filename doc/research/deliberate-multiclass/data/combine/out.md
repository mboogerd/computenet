
### K=2 continuity (max |P_rule(A) - P_binary(A)| over 2000 random trees)
| comparison | all trees | one-sided trees |
|---|---|---|
| LL-woe vs WoE layer | 0.0000 | 0.0000 |
| LL-mlp vs MLP layer | 0.0000 | 0.0000 |
| Dempster+BetP vs DF-QuAD | 0.7359 | 0.0000 |
| PCR6+BetP vs DF-QuAD | 0.4345 | 0.0000 |
| SL vs DF-QuAD | 0.4685 | 0.2785 |
| modelA(T=1) vs WoE layer | 0.1501 | 0.1501 |
| modelA(T=2) vs WoE layer | 0.0000 | 0.0000 |

### (i) fate of the universe, 4 claims, open world
| rule | rip | crunch | freeze | OTHER |
|---|---|---|---|---|
| prior pi | 0.136 | 0.182 | 0.636 | 0.045 |
| modelA | 0.012 | 0.007 | 0.981 | — |
| bayesVE | 0.033 | 0.029 | 0.904 | 0.035 |
| LL-woe | 0.052 | 0.045 | 0.874 | 0.030 |
| LL-mlp | 0.049 | 0.066 | 0.846 | 0.039 |
| Dempster | 0.027 | 0.022 | 0.916 | 0.035 |
| TBM-open | 0.027 | 0.022 | 0.916 | 0.035 |
| PCR6 | 0.027 | 0.022 | 0.916 | 0.035 |
| SL | 0.068 | 0.087 | 0.805 | 0.040 |
| **log-pool** | **0.041** | **0.039** | **0.884** | **0.036** |

TBM conflict m(∅)=0.000; SL vacuity u=0.088; JSD across pooled rules=0.010

### (i') same, all claims doubted (c=0.1 each)
| rule | rip | crunch | freeze | OTHER |
|---|---|---|---|---|
| prior pi | 0.136 | 0.182 | 0.636 | 0.045 |
| modelA | 0.051 | 0.075 | 0.874 | — |
| bayesVE | 0.116 | 0.162 | 0.678 | 0.045 |
| LL-woe | 0.122 | 0.165 | 0.670 | 0.044 |
| LL-mlp | 0.116 | 0.163 | 0.676 | 0.045 |
| Dempster | 0.110 | 0.155 | 0.690 | 0.044 |
| TBM-open | 0.110 | 0.155 | 0.690 | 0.044 |
| PCR6 | 0.110 | 0.155 | 0.690 | 0.044 |
| SL | 0.081 | 0.127 | 0.751 | 0.041 |
| **log-pool** | **0.109** | **0.154** | **0.693** | **0.044** |

TBM conflict m(∅)=0.000; SL vacuity u=0.400; JSD across pooled rules=0.002

### (ii-a) Zadeh, e=0.99 (unclamped), closed world
| rule | mening | concuss | tumour |
|---|---|---|---|
| prior pi | 0.333 | 0.333 | 0.333 |
| modelA | 0.468 | 0.063 | 0.468 |
| bayesVE | 0.497 | 0.005 | 0.497 |
| LL-woe | 0.390 | 0.219 | 0.390 |
| LL-mlp | 0.420 | 0.159 | 0.420 |
| Dempster | 0.494 | 0.012 | 0.494 |
| TBM-open | 0.494 | 0.012 | 0.494 |
| PCR6 | 0.500 | 0.000 | 0.500 |
| SL | 0.485 | 0.031 | 0.485 |
| **log-pool** | **0.491** | **0.018** | **0.491** |

TBM conflict m(∅)=0.980; SL vacuity u=0.092; JSD across pooled rules=0.056

### (ii-b) Zadeh, e=0.99, open world (OTHER unjudged)
| rule | mening | concuss | tumour | OTHER |
|---|---|---|---|---|
| prior pi | 0.300 | 0.300 | 0.300 | 0.100 |
| modelA | 0.468 | 0.063 | 0.468 | — |
| bayesVE | 0.028 | 0.000 | 0.028 | 0.943 |
| LL-woe | 0.252 | 0.141 | 0.252 | 0.356 |
| LL-mlp | 0.305 | 0.116 | 0.305 | 0.274 |
| Dempster | 0.007 | 0.000 | 0.007 | 0.985 |
| TBM-open | 0.007 | 0.000 | 0.007 | 0.985 |
| PCR6 | 0.007 | 0.000 | 0.007 | 0.985 |
| SL | 0.368 | 0.028 | 0.368 | 0.236 |
| **log-pool** | **0.092** | **0.006** | **0.092** | **0.810** |

TBM conflict m(∅)=0.000; SL vacuity u=0.092; JSD across pooled rules=0.311

### (ii-c) Zadeh, e=0.7 (energy clamp as WoE emax), closed world
| rule | mening | concuss | tumour |
|---|---|---|---|
| prior pi | 0.333 | 0.333 | 0.333 |
| modelA | 0.468 | 0.063 | 0.468 |
| bayesVE | 0.433 | 0.133 | 0.433 |
| LL-woe | 0.390 | 0.219 | 0.390 |
| LL-mlp | 0.399 | 0.201 | 0.399 |
| Dempster | 0.468 | 0.063 | 0.468 |
| TBM-open | 0.468 | 0.063 | 0.468 |
| PCR6 | 0.484 | 0.032 | 0.484 |
| SL | 0.479 | 0.042 | 0.479 |
| **log-pool** | **0.454** | **0.092** | **0.454** |

TBM conflict m(∅)=0.490; SL vacuity u=0.125; JSD across pooled rules=0.028

### (iii-a) all positions implausible by prior (p=0.1 each), no arguments
| rule | X | Y | Z | OTHER |
|---|---|---|---|---|
| prior pi | 0.100 | 0.100 | 0.100 | 0.700 |
| modelA | 0.333 | 0.333 | 0.333 | — |
| bayesVE | 0.100 | 0.100 | 0.100 | 0.700 |
| LL-woe | 0.100 | 0.100 | 0.100 | 0.700 |
| LL-mlp | 0.100 | 0.100 | 0.100 | 0.700 |
| Dempster | 0.100 | 0.100 | 0.100 | 0.700 |
| TBM-open | 0.100 | 0.100 | 0.100 | 0.700 |
| PCR6 | 0.100 | 0.100 | 0.100 | 0.700 |
| SL | 0.100 | 0.100 | 0.100 | 0.700 |
| **log-pool** | **0.100** | **0.100** | **0.100** | **0.700** |

TBM conflict m(∅)=0.000; SL vacuity u=1.000; JSD across pooled rules=0.000

### (iii-b) plausible priors (0.3 each), each position eliminated by a claim e=0.64
| rule | X | Y | Z | OTHER |
|---|---|---|---|---|
| prior pi | 0.300 | 0.300 | 0.300 | 0.100 |
| modelA | 0.333 | 0.333 | 0.333 | — |
| bayesVE | 0.255 | 0.255 | 0.255 | 0.236 |
| LL-woe | 0.242 | 0.242 | 0.242 | 0.275 |
| LL-mlp | 0.275 | 0.275 | 0.275 | 0.174 |
| Dempster | 0.196 | 0.196 | 0.196 | 0.413 |
| TBM-open | 0.196 | 0.196 | 0.196 | 0.413 |
| PCR6 | 0.196 | 0.196 | 0.196 | 0.413 |
| SL | 0.287 | 0.287 | 0.287 | 0.139 |
| **log-pool** | **0.246** | **0.246** | **0.246** | **0.261** |

TBM conflict m(∅)=0.000; SL vacuity u=0.094; JSD across pooled rules=0.029

### (iii-c) as (iii-b), closed world (no OTHER class)
| rule | X | Y | Z |
|---|---|---|---|
| prior pi | 0.333 | 0.333 | 0.333 |
| modelA | 0.333 | 0.333 | 0.333 |
| bayesVE | 0.333 | 0.333 | 0.333 |
| LL-woe | 0.333 | 0.333 | 0.333 |
| LL-mlp | 0.333 | 0.333 | 0.333 |
| Dempster | 0.333 | 0.333 | 0.333 |
| TBM-open | 0.333 | 0.333 | 0.333 |
| PCR6 | 0.333 | 0.333 | 0.333 |
| SL | 0.333 | 0.333 | 0.333 |
| **log-pool** | **0.333** | **0.333** | **0.333** |

TBM conflict m(∅)=0.262; SL vacuity u=0.094; JSD across pooled rules=0.000

### (iv-a) before: 3 claims, 3 classes
| rule | rip | crunch | freeze | OTHER |
|---|---|---|---|---|
| prior pi | 0.136 | 0.182 | 0.636 | 0.045 |
| modelA | 0.014 | 0.006 | 0.980 | — |
| bayesVE | 0.045 | 0.028 | 0.893 | 0.034 |
| LL-woe | 0.054 | 0.045 | 0.871 | 0.030 |
| LL-mlp | 0.064 | 0.065 | 0.833 | 0.039 |
| Dempster | 0.037 | 0.022 | 0.907 | 0.034 |
| TBM-open | 0.037 | 0.022 | 0.907 | 0.034 |
| PCR6 | 0.037 | 0.022 | 0.907 | 0.034 |
| SL | 0.077 | 0.070 | 0.814 | 0.039 |
| **log-pool** | **0.051** | **0.038** | **0.877** | **0.035** |

TBM conflict m(∅)=0.000; SL vacuity u=0.101; JSD across pooled rules=0.007

### (iv-b) bounce added, links UNJUDGED -> default compatible (inherits OTHER)
| rule | rip | crunch | freeze | bounce | OTHER |
|---|---|---|---|---|---|
| prior pi | 0.125 | 0.167 | 0.583 | 0.083 | 0.042 |
| modelA | 0.013 | 0.006 | 0.936 | 0.045 | — |
| bayesVE | 0.040 | 0.025 | 0.792 | 0.113 | 0.030 |
| LL-woe | 0.048 | 0.040 | 0.775 | 0.111 | 0.026 |
| LL-mlp | 0.057 | 0.058 | 0.744 | 0.106 | 0.035 |
| Dempster | 0.034 | 0.020 | 0.802 | 0.115 | 0.030 |
| TBM-open | 0.034 | 0.020 | 0.802 | 0.115 | 0.030 |
| PCR6 | 0.034 | 0.020 | 0.802 | 0.115 | 0.030 |
| SL | 0.070 | 0.064 | 0.728 | 0.104 | 0.035 |
| **log-pool** | **0.046** | **0.034** | **0.778** | **0.111** | **0.031** |

TBM conflict m(∅)=0.000; SL vacuity u=0.101; JSD across pooled rules=0.007

### (iv-c) bounce added, unjudged -> default INcompatible (closed default, wrong)
| rule | rip | crunch | freeze | bounce | OTHER |
|---|---|---|---|---|---|
| prior pi | 0.125 | 0.167 | 0.583 | 0.083 | 0.042 |
| modelA | 0.014 | 0.006 | 0.979 | 0.001 | — |
| bayesVE | 0.045 | 0.028 | 0.886 | 0.007 | 0.034 |
| LL-woe | 0.053 | 0.044 | 0.856 | 0.018 | 0.029 |
| LL-mlp | 0.062 | 0.064 | 0.817 | 0.019 | 0.038 |
| Dempster | 0.037 | 0.021 | 0.904 | 0.005 | 0.034 |
| TBM-open | 0.037 | 0.021 | 0.904 | 0.005 | 0.034 |
| PCR6 | 0.037 | 0.021 | 0.904 | 0.005 | 0.034 |
| SL | 0.076 | 0.069 | 0.808 | 0.008 | 0.038 |
| **log-pool** | **0.050** | **0.037** | **0.870** | **0.009** | **0.035** |

TBM conflict m(∅)=0.000; SL vacuity u=0.101; JSD across pooled rules=0.009

### (iv-d) bounce links re-judged (0.5, 0, 1)
| rule | rip | crunch | freeze | bounce | OTHER |
|---|---|---|---|---|---|
| prior pi | 0.125 | 0.167 | 0.583 | 0.083 | 0.042 |
| modelA | 0.014 | 0.006 | 0.974 | 0.006 | — |
| bayesVE | 0.044 | 0.027 | 0.868 | 0.028 | 0.033 |
| LL-woe | 0.052 | 0.043 | 0.839 | 0.037 | 0.028 |
| LL-mlp | 0.061 | 0.062 | 0.795 | 0.045 | 0.037 |
| Dempster | 0.035 | 0.020 | 0.884 | 0.029 | 0.032 |
| TBM-open | 0.035 | 0.020 | 0.884 | 0.029 | 0.032 |
| PCR6 | 0.035 | 0.020 | 0.884 | 0.029 | 0.032 |
| SL | 0.070 | 0.064 | 0.761 | 0.070 | 0.035 |
| **log-pool** | **0.048** | **0.035** | **0.845** | **0.038** | **0.033** |

TBM conflict m(∅)=0.000; SL vacuity u=0.101; JSD across pooled rules=0.010

### (v-a) overlapping causes pushed through MECE rules (forced 'the cause' reading)
| rule | subprime | leverage | dereg | imbalance |
|---|---|---|---|---|
| prior pi | 0.286 | 0.286 | 0.238 | 0.190 |
| modelA | 0.489 | 0.422 | 0.038 | 0.051 |
| bayesVE | 0.434 | 0.380 | 0.071 | 0.116 |
| LL-woe | 0.364 | 0.338 | 0.159 | 0.139 |
| LL-mlp | 0.370 | 0.356 | 0.111 | 0.162 |
| Dempster | 0.471 | 0.398 | 0.027 | 0.104 |
| TBM-open | 0.471 | 0.398 | 0.027 | 0.104 |
| PCR6 | 0.422 | 0.382 | 0.066 | 0.130 |
| SL | 0.404 | 0.386 | 0.022 | 0.188 |
| **log-pool** | **0.418** | **0.380** | **0.062** | **0.140** |

TBM conflict m(∅)=0.616; SL vacuity u=0.091; JSD across pooled rules=0.020

### (v-b) same causes as K independent binary roots (WoE layer), marginals
| class | base | marginal |
|---|---|---|
| subprime | 0.60 | 0.864 |
| leverage | 0.60 | 0.855 |
| dereg | 0.50 | 0.395 |
| imbalance | 0.40 | 0.506 |
