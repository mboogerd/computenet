package civictech.cell.data.op

import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.tagFold
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * computenet-vvre / doc/demo-findings.md F-9: a reconvergent (diamond) path
 * where one leg runs through an [IntersectSetCell] and the other reaches the
 * same [UnionSetCell] directly.
 *
 * Set algebra is not in dispute: `A ∪ (A ∩ B) = A`, so an element live in `A`
 * stays in the union after it leaves the intersection.
 */
class IntersectDiamondTagTest {

    private fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(
            Use.fixed(
                object : Propagate<T> {
                    override fun propagate(value: T) {
                        collected += value
                    }
                },
                PortRef.generate(),
            ),
        )
        return collected
    }

    @Test
    fun `union of a source with an intersection over that source keeps the source's elements`() {
        val a = SetCell<String>()
        val b = SetCell<String>()
        val intersect = IntersectSetCell<String>()
        val union = UnionSetCell<String>()

        // the diamond: A reaches the union twice — directly, and through A ∩ B
        a.outlet.linkTo(union.inlet)
        a.outlet.linkTo(intersect.left)
        b.outlet.linkTo(intersect.right)
        intersect.outlet.linkTo(union.inlet)

        val out = collect(union.outlet)

        a.inlet.call.add("e")
        b.inlet.call.add("e")               // "e" enters A ∩ B
        assertEquals(setOf("e"), tagFold(out))

        b.inlet.call.remove("e")            // "e" leaves A ∩ B, but is still live in A

        assertEquals(
            setOf("e"),
            tagFold(out),
            "A ∪ (A ∩ B) must still hold e: the direct A edge is live, only the intersection leg exited",
        )
    }

    @Test
    fun `control - a union over a distinct source and an intersection is unaffected`() {
        val a = SetCell<String>()
        val b = SetCell<String>()
        val c = SetCell<String>()
        val intersect = IntersectSetCell<String>()
        val union = UnionSetCell<String>()

        // same shape, but the union's other leg shares no tag with the intersection
        c.outlet.linkTo(union.inlet)
        a.outlet.linkTo(intersect.left)
        b.outlet.linkTo(intersect.right)
        intersect.outlet.linkTo(union.inlet)

        val out = collect(union.outlet)

        c.inlet.call.add("e")
        a.inlet.call.add("e")
        b.inlet.call.add("e")
        assertEquals(setOf("e"), tagFold(out))

        b.inlet.call.remove("e")

        assertEquals(setOf("e"), tagFold(out), "C ∪ (A ∩ B) keeps e via C")
    }

    @Test
    fun `re-entry after the intersection closed and reopened is still live`() {
        val a = SetCell<String>()
        val b = SetCell<String>()
        val intersect = IntersectSetCell<String>()
        val union = UnionSetCell<String>()

        a.outlet.linkTo(union.inlet)
        a.outlet.linkTo(intersect.left)
        b.outlet.linkTo(intersect.right)
        intersect.outlet.linkTo(union.inlet)

        val out = collect(union.outlet)

        a.inlet.call.add("e")
        b.inlet.call.add("e")
        b.inlet.call.remove("e")
        b.inlet.call.add("e")               // re-enters the intersection

        assertEquals(setOf("e"), tagFold(out), "re-entry must not be swallowed by a stale tombstone")
    }

    @Test
    fun `chained intersections over a shared source keep the union live`() {
        val a = SetCell<String>()
        val b = SetCell<String>()
        val c = SetCell<String>()
        val inner = IntersectSetCell<String>()   // A ∩ B
        val outer = IntersectSetCell<String>()   // (A ∩ B) ∩ C
        val union = UnionSetCell<String>()

        a.outlet.linkTo(union.inlet)
        a.outlet.linkTo(inner.left)
        b.outlet.linkTo(inner.right)
        inner.outlet.linkTo(outer.left)
        c.outlet.linkTo(outer.right)
        outer.outlet.linkTo(union.inlet)

        val out = collect(union.outlet)

        a.inlet.call.add("e")
        b.inlet.call.add("e")
        c.inlet.call.add("e")
        assertEquals(setOf("e"), tagFold(out))

        c.inlet.call.remove("e")            // leaves the outer intersection only

        assertEquals(setOf("e"), tagFold(out), "A ∪ ((A ∩ B) ∩ C) keeps e via the direct A edge")
    }
}
