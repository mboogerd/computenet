package civictech.kernel.germ.port

interface Serve<Api> {
    /**
     * Sets `api` as the (or a) new api implementation to serve to users
     * Use case: Set a custom implementation
     */
    fun serve(api: Api)

    /**
     * Reuses the given Use to serve users of the Use complementing this Serve
     * Use case: linking
     */
    fun delegate(useApi: Use<Api>)
}

/*
Inside => serve + delegate
Outside => delegate
 */

/*
Idea: We should distinguish between various types of Use/Serve, starting from the simplest to more complex:
- fixed serve/use: always defined, throws when attempting to re-link
- single serve/use: potentially defined, allows re-linking by replacing
    - single use: default case
    - single serve:
- multiple serve/use: any number of links, can be changed (added only? => removal as suspend)

- fixed use-serve use-case: low-latency systems transferring data ownership between steps.
- single: use-serve use-case: mostly same, but also dynamic
- multi-use use-case: aggregation
- multi-serve use-case: default for freely subscribing on upstream changes

Question: What then, is a port and a link? Do they have identity?
- A Port combines one Use and one Serve, s.t. any use invocations obtain and operate on what is served
- A link combines a Use and a Serve from two _different_ Ports.

Question: How should we administer links? Are they a separate entity, or just a virtual one?
- Links are a separate entity on their own: They have their own singular Use/Serve endpoint
- Links don't have a separate existence. It's just a virtual entity represented by pairing up a Serve with a Use.

It seems as though we can have both. In either case, Uses need to track users, Serves need to track subscribers

Question: Should subscriptions be a generic thing for Serve/Use? For the `fixed` case, a subscription mechanic is superfluous

Idea: We need these two concepts to have:
- Both:
    - Bidirectional pairing; get Serve to know the Use and vice versa
    - pairing should also lead to a pairing event / there should be a pairing API that a Serve/Use owner can hook into
    - pairing should be possible symmetrically too (e.g. Use with Use, Serve with Serve)
- Serve: provide an API
- Use: use an API

Idea: A cell has an API field, which exposes its public API. This is the default Handle to be shared when accessing the
Cell instance. The api field exposes fields that are paired up with the cell fields, but reversed, e.g.
- For each field: Use on cell, there's a field: Serve on api, which is paired up
- For each field: Serve on cell, there's a field: Use on api, which is paired up

Examples
- One mutating Cell (single input, single output (passes data ownership))
- One mapping Cell (single input, any outputs)
- One aggregation Cell (any inputs, any outputs)
- One conditional Cell (two inputs (conditional state, data), two outputs (data receivers, conditional on state))

Mutator: Incrementer
- inlet: Use<SupplyInt>
- outlet: Serve<SupplyInt>

Inc1 -> Inc2 -> Inc3

Incrementer construct:
inlet.serve { // inlet is a Serve within a Cell
    object : SupplyInt {
        fun supplyInt(i: Int) {
            outlet.use().supplyInt(i + 1) // outlet is a Use within a Cell
        }
    }
}

Inc1.outlet.link(Inc2.inlet)
- Inc1.outlet retrieves the current implementation from Inc2.inlet
    - Inc2.inlet provides the current implementation
- Optional: Inc1.outlet sends the current buffer if the outlet buffered increments (otherwise noops)
- Inc1.outlet registers itself with Inc2.inlet to receive updates about its implementation changes
    - Inc2.inlet stores Inc1.outlet as being interested in implementation changes

Inc2.outlet.link(Inc3.inlet)


Side notes:
- Need a NoOpServe<Api>: Implements Api with a no-op dynamic proxy
- Could use a BufferingServe<Api>: Implements Api with a queuing dynamic proxy
- Can implement StatefulServe : Serve<UpdateApi>; Ensures new connections receive the current state before receiving updates (though might also be a Cell responsibility, as that owns the state)
 */