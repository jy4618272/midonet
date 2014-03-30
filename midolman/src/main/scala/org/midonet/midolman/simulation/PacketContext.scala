// Copyright 2012 Midokura Inc.

package org.midonet.midolman.simulation

import java.text.SimpleDateFormat
import java.util.{Date, Set => JSet, UUID}
// Read-only view.  Note this is distinct from immutable.Set in that it
// might be changed by another (mutable) view.
import scala.collection.{Set => ROSet, Seq, mutable}

import akka.actor.ActorSystem

import org.midonet.cache.Cache
import org.midonet.cluster.client.Port
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.ChainPacketContext
import org.midonet.packets._
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.util.functors.Callback0


/**
 * The PacketContext is a serialization token for the devices during the
 * simulation of a packet's traversal of the virtual topology.
 * A device may not modify the PacketContext after the Future[Action]
 * returned by the process method completes.
 *
 * More specifically:  Ownership of a PacketContext passes to the forwarding
 * element with the call to ForwardingElement::process().  It passes back
 * to the Coordinator when the Future returned by process() completes.
 * Coordinators and ForwardingElements are to read from and write to a
 * PacketContext only during the period in which they own it.
 *
 * TODO: This convention could be made explicit by having the contents
 * of the returned Future contain the PacketContext which the Coordinator
 * was to use and pass on to the next forwarding element, instead of being
 * a singleton-per-simulation token.  Investigate whether that'd be better.
 */
/* TODO(Diyari release): Move inPortID & outPortID out of PacketContext. */
class PacketContext(override val flowCookie: Option[Int],
                    val frame: Ethernet,
                    val expiry: Long, val connectionCache: Cache,
                    val traceMessageCache: Cache, val traceIndexCache: Cache,
                    val isGenerated: Boolean, val parentCookie: Option[Int],
                    val origMatch: WildcardMatch)
                   (implicit actorSystem: ActorSystem)
         extends ChainPacketContext {
    import PacketContext._

    private val log =
        LoggerFactory.getActorSystemThreadLog(this.getClass)(actorSystem.eventStream)
    // ingressFE is used for connection tracking. conntrack keys use the
    // forward flow's egress device id. For return packets, symmetrically,
    // the ingress device is used to lookup the conntrack key that would have
    // been written by the forward packet. PacketContext needs to now
    // the ingress device to do this lookup in isForwardFlow()
    private var ingressFE: UUID = null

    var portGroups: JSet[UUID] = null
    private var forwardFlow = false
    private var connectionTracked = false
    override def isConnTracked: Boolean = connectionTracked

    private var _inPortId: UUID = null
    override def inPortId: UUID = _inPortId
    def inPortId_=(port: Port) {
        _inPortId = if (port == null) null else port.id
        // ingressFE is set only once, so it always points to the
        // first device that saw this packet, null for generated packets.
        if (port != null && ingressFE == null && !isGenerated)
            ingressFE = port.deviceID

    }

    var outPortId: UUID = null
    val wcmatch: WildcardMatch = origMatch.clone

    private var traceID: UUID = null
    private var traceStep = 0
    private var isTraced = false

    // This set stores the callback to call when this flow is removed.
    val flowRemovedCallbacks = mutable.ListBuffer[Callback0]()
    override def addFlowRemovedCallback(cb: Callback0): Unit = this.synchronized {
        flowRemovedCallbacks.append(cb)
    }

    def runFlowRemovedCallbacks() = {
        val iterator = flowRemovedCallbacks.iterator
        while (iterator.hasNext) {
            iterator.next().call()
        }
        flowRemovedCallbacks.clear()
    }

    // This Set stores the tags by which the flow may be indexed.
    // The index can be used to remove flows associated with the given tag.
    val flowTags = mutable.Set[Any]()
    override def addFlowTag(tag: Any): Unit = this.synchronized {
        flowTags.add(tag)
    }

    def setTraced(flag: Boolean) {
        if (flag && traceMessageCache == null) {
            log.error("Attempting to trace with no message cache")
            return
        }
        if (!isTraced && flag) {
            traceID = UUID.randomUUID
        }
        isTraced = flag
    }

    def traceMessage(equipmentID: UUID, msg: String) {
        if (isTraced) {
            traceStep += 1
            val key: String = traceID.toString + ":" + traceStep
            val equipStr: String = if (equipmentID == null)
                                       "(none)"
                                   else
                                       equipmentID.toString
            val value: String = (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS "
                                ).format(new Date)) + equipStr + " " + msg
            traceMessageCache.set(key, value)
            traceIndexCache.set(traceID.toString, traceStep.toString)
        }
    }

    /* Packet context methods used by Chains. */
    override def addTraversedElementID(id: UUID) { /* XXX */ }

    override def isForwardFlow: Boolean = {


        // Packets which aren't TCP-or-UDP over IPv4 aren't connection
        // tracked, and always treated as forward flows.
        if (!IPv4.ETHERTYPE.equals(wcmatch.getEtherType) ||
            (!TCP.PROTOCOL_NUMBER.equals(wcmatch.getNetworkProtocol) &&
             !UDP.PROTOCOL_NUMBER.equals(wcmatch.getNetworkProtocol) &&
             !ICMP.PROTOCOL_NUMBER.equals(wcmatch.getNetworkProtocol)))
            return true

        // Generated packets have ingressFE == null. We will treat all generated
        // tcp udp packets as return flows TODO(rossella) is that
        // enough to determine that it's a return flow?
        if (isGenerated) {
            return false
        } else if (ingressFE == null) {
            throw new IllegalArgumentException(
                    "isForwardFlow cannot be calculated because the ingress " +
                    "device is not set")
        }

        // Connection tracking:  connectionTracked starts out as false.
        // If isForwardFlow is called, connectionTracked becomes true and
        // a lookup into Cassandra determines which direction this packet
        // is considered to be going.
        if (connectionTracked)
            return forwardFlow

        connectionTracked = true
        val key = connectionKey(origMatch.getNetworkSourceIP,
                                icmpIdOrTransportSrc(origMatch),
                                origMatch.getNetworkDestinationIP,
                                icmpIdOrTransportDst(origMatch),
                                origMatch.getNetworkProtocol.toShort,
                                ingressFE)
        // TODO(jlm): Finish org.midonet.cassandra.AsyncCassandraCache
        //            and use it instead.
        val value = connectionCache.get(key)
        forwardFlow = value != "r"
        log.debug("isForwardFlow conntrack lookup - key:{},value:{}", key, value)
        forwardFlow
    }

    def installConnectionCacheEntry(deviceId: UUID) {
        val key = connectionKey(wcmatch.getNetworkDestinationIP,
                                icmpIdOrTransportDst(wcmatch),
                                wcmatch.getNetworkSourceIP,
                                icmpIdOrTransportSrc(wcmatch),
                                wcmatch.getNetworkProtocol.toShort,
                                deviceId)
        log.debug("Installing conntrack entry: key:{}", key)
        connectionCache.set(key, "r")
    }

    private def icmpIdOrTransportSrc(wm: WildcardMatch): Int = {
        if (ICMP.PROTOCOL_NUMBER.equals(wm.getNetworkProtocol)) {
            val icmpId: java.lang.Short = wm.getIcmpIdentifier
            if (icmpId != null)
                return icmpId.toInt
        }
        return wm.getTransportSource
    }

    private def icmpIdOrTransportDst(wm: WildcardMatch): Int = {
        if (ICMP.PROTOCOL_NUMBER.equals(wm.getNetworkProtocol)) {
            val icmpId: java.lang.Short = wm.getIcmpIdentifier
            if (icmpId != null)
                return icmpId.toInt
        }
        return wm.getTransportDestination
    }

}

object PacketContext {
    def connectionKey(ip1: IPAddr, port1: Int, ip2: IPAddr, port2: Int,
                      proto: Short, deviceID: UUID): String = {
        new StringBuilder(ip1.toString).append('|').append(port1).append('|')
                .append(ip2.toString).append('|')
                .append(port2).append('|').append(proto).append('|')
                .append(deviceID.toString).toString()
    }
}

