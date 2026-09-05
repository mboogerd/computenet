# Budget deltas. One file per bead: line-budget.d/<bead-id>.txt
#
# Format, same as line-budget.txt but signed and relative:
#   <skill-dir-name> <+N|-N>
# plus comment lines saying what the growth bought — that justification is the
# whole point of the ratchet and belongs in the same diff as the growth.
#
# Effective budget = the entry in line-budget.txt + every delta here.
#
# Deltas exist so two prepared fixes can be in flight at once: a shared ledger
# line forces a recompute (the second number depends on the first's landed
# value), two new files do not. Fold them back into line-budget.txt whenever a
# session holds the ledger alone, and delete them here in the same commit.
#
# A delta naming a skill with no line-budget.txt entry FAILS the validator.
