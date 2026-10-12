------------------------- MODULE MCFrontierSmall -------------------------
EXTENDS GlitchFree

DiamondFrom ==
    [e \in {"ab", "ac", "bd", "cd"} |->
        CASE e = "ab" -> "A"
          [] e = "ac" -> "A"
          [] e = "bd" -> "B"
          [] e = "cd" -> "C"]

DiamondTo ==
    [e \in {"ab", "ac", "bd", "cd"} |->
        CASE e = "ab" -> "B"
          [] e = "ac" -> "C"
          [] e = "bd" -> "D"
          [] e = "cd" -> "D"]

DiamondCarries ==
    [e \in {"ab", "ac", "bd", "cd"} |-> {"A"}]

NestedFrom ==
    [e \in {"ab", "ac", "bd", "cd", "de", "ce"} |->
        CASE e = "ab" -> "A"
          [] e = "ac" -> "A"
          [] e = "bd" -> "B"
          [] e = "cd" -> "C"
          [] e = "de" -> "D"
          [] e = "ce" -> "C"]

NestedTo ==
    [e \in {"ab", "ac", "bd", "cd", "de", "ce"} |->
        CASE e = "ab" -> "B"
          [] e = "ac" -> "C"
          [] e = "bd" -> "D"
          [] e = "cd" -> "D"
          [] e = "de" -> "E"
          [] e = "ce" -> "E"]

NestedCarries ==
    [e \in {"ab", "ac", "bd", "cd", "de", "ce"} |-> {"A"}]

TwoSourcesFrom ==
    [e \in {"ab", "ac", "bd", "cd", "sd"} |->
        CASE e = "ab" -> "A"
          [] e = "ac" -> "A"
          [] e = "bd" -> "B"
          [] e = "cd" -> "C"
          [] e = "sd" -> "S"]

TwoSourcesTo ==
    [e \in {"ab", "ac", "bd", "cd", "sd"} |->
        CASE e = "ab" -> "B"
          [] e = "ac" -> "C"
          [] e = "bd" -> "D"
          [] e = "cd" -> "D"
          [] e = "sd" -> "D"]

TwoSourcesCarries ==
    [e \in {"ab", "ac", "bd", "cd", "sd"} |->
        IF e = "sd" THEN {"S"} ELSE {"A"}]

OverAlignedCarries ==
    [e \in {"ab", "ac", "bd", "cd", "sd"} |->
        IF e = "sd" THEN {"A", "S"} ELSE {"A"}]

=============================================================================
