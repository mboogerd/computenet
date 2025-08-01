package civictech.compute

interface Protocol {

    /**
     * Must uniquely identify the protocol that is being multiplexed
     */
    val protocolId: Int
}