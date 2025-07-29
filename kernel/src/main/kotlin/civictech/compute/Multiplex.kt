package civictech.compute

interface Multiplex {
    /**
     * Must uniquely identify the protocol that is being multiplexed
     */
    val protocolId: Int
}
