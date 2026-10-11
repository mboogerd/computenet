-------------------------- MODULE MCFrontierCut --------------------------
EXTENDS Cuts

OpenLateFrom ==
    [e \in {"ab", "ac", "bd", "cd"} |->
        CASE e = "ab" -> "A"
          [] e = "ac" -> "A"
          [] e = "bd" -> "B"
          [] e = "cd" -> "C"]

OpenLateTo ==
    [e \in {"ab", "ac", "bd", "cd"} |->
        CASE e = "ab" -> "B"
          [] e = "ac" -> "C"
          [] e = "bd" -> "D"
          [] e = "cd" -> "D"]

OpenLateCarries ==
    [e \in {"ab", "ac", "bd", "cd"} |-> {"A"}]

ChainFrom ==
    [e \in {"am", "md", "ab", "bd"} |->
        CASE e = "am" -> "A"
          [] e = "md" -> "M"
          [] e = "ab" -> "A"
          [] e = "bd" -> "B"]

ChainTo ==
    [e \in {"am", "md", "ab", "bd"} |->
        CASE e = "am" -> "M"
          [] e = "md" -> "D"
          [] e = "ab" -> "B"
          [] e = "bd" -> "D"]

ChainCarries ==
    [e \in {"am", "md", "ab", "bd"} |-> {"A"}]

=============================================================================
