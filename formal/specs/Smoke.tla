------------------------------ MODULE Smoke ------------------------------
EXTENDS Naturals

CONSTANT Bound

VARIABLE n

Init == n = 0

Step ==
    /\ n < 3
    /\ n' = n + 1

Terminating ==
    /\ n = 3
    /\ UNCHANGED n

Next == Step \/ Terminating

Inv == n <= Bound

Spec == Init /\ [][Next]_n

=============================================================================
