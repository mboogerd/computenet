package civictech.concord.oracle

import civictech.concord.schema.ApplyStep
import civictech.concord.schema.CellSpec
import civictech.concord.schema.Check
import civictech.concord.schema.Graph
import civictech.concord.schema.Kind
import civictech.concord.schema.LinkSpec
import civictech.concord.schema.Profile
import civictech.concord.schema.Scenario
import civictech.concord.schema.Step
import civictech.concord.value.Value

/** Terse in-code builders for oracle/check fixtures — no YAML, no kernel. */
object Fx {
    fun cell(id: String, type: String, fn: String? = null): CellSpec = CellSpec(id = id, type = type, fn = fn)

    fun link(from: String, to: String, inlet: String? = null): LinkSpec =
        LinkSpec(from = from, to = to, inlet = inlet)

    fun apply(on: String, op: String, value: Value? = null, times: Int? = null): Step =
        ApplyStep(on = on, op = op, value = value, times = times)

    fun scenario(
        cells: List<CellSpec>,
        links: List<LinkSpec>,
        script: List<Step> = emptyList(),
        checks: List<Check> = emptyList(),
        id: String = "FX-01",
        kind: Kind = Kind.EXAMPLE,
    ): Scenario = Scenario(
        id = id,
        title = "fixture",
        covers = listOf("FX-COVER-01"),
        profile = Profile.CORE,
        kind = kind,
        graph = Graph(cells = cells, links = links),
        script = script,
        checks = checks,
    )

    // Value shorthands
    fun i(n: Long): Value = Value.IntVal(n)
    fun s(v: String): Value = Value.StrVal(v)
    fun list(vararg v: Value): Value = Value.ListVal(v.toList())
    fun map(vararg e: Pair<String, Value>): Value = Value.MapVal(e.toMap())
}
