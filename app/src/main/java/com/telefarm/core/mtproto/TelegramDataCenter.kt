package com.telefarm.core.mtproto

/** One address of a Telegram data center. */
internal data class MtProtoEndpoint(val host: String, val port: Int)

/**
 * Public entry points of the Telegram data centers.
 *
 * These are the primary IPv4 addresses every MTProto client ships with; a session string that
 * carries its own address always wins over this table, and the data center list received from
 * the server is not needed because the import performs a single call on the session's own
 * home data center.
 */
internal object TelegramDataCenter {

    private val PRIMARY_ADDRESSES = mapOf(
        1 to MtProtoEndpoint("149.154.175.53", 443),
        2 to MtProtoEndpoint("149.154.167.51", 443),
        3 to MtProtoEndpoint("149.154.175.100", 443),
        4 to MtProtoEndpoint("149.154.167.91", 443),
        5 to MtProtoEndpoint("91.108.56.130", 443)
    )

    /** Test environment data centers, reachable only from sessions created in test mode. */
    private val TEST_ADDRESSES = mapOf(
        1 to MtProtoEndpoint("149.154.175.10", 443),
        2 to MtProtoEndpoint("149.154.167.40", 443),
        3 to MtProtoEndpoint("149.154.175.117", 443)
    )

    /** True when [dcId] identifies a data center that a session can live on. */
    fun isValid(dcId: Int): Boolean = PRIMARY_ADDRESSES.containsKey(dcId)

    fun address(dcId: Int, testMode: Boolean = false): MtProtoEndpoint? =
        if (testMode) TEST_ADDRESSES[dcId] ?: PRIMARY_ADDRESSES[dcId] else PRIMARY_ADDRESSES[dcId]
}
