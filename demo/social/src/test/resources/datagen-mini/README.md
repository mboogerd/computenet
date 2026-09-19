# datagen-mini

Hand-written LDBC SNB *datagen* interactive (v0.3.x) fixture — no real
datagen output downloaded or vendored. Entity/edge column names and the
update-event field order/type-ordinal mapping (IU1..IU8) are pinned from
`ldbc/ldbc_snb_datagen_hadoop@main`'s
`CsvBasicDynamicPersonSerializer.java`, `CsvBasicDynamicActivitySerializer.java`,
`UpdateEventSerializer.java` and `UpdateEvent.java` (read 2026-09-19); the
static dimension files (`tagclass`/`tag`/`place`/`organisation`) and the
update-stream CSV shape itself (a header-named union of every IU arm's
fields, rather than the real headerless positional stream) are this task's
own best-reading simplification — see `DatagenCsvSource.kt`'s KDoc for the
file-by-file detail and which half of each claim is independently confirmed.

Every id referenced by the update stream resolves either in the static
slice or in an earlier update event; every arm IU1..IU8 occurs at least
once; comment 202 replies to comment 201 (a comment-replying-to-a-comment
case).
