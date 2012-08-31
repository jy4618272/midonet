/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.client;

import java.util.UUID;

import com.midokura.midonet.cluster.data.AvailabilityZone;
import com.midokura.midonet.cluster.data.zones.CapwapAvailabilityZone;
import com.midokura.midonet.cluster.data.zones.GreAvailabilityZone;
import com.midokura.midonet.cluster.data.zones.GreAvailabilityZoneHost;
import com.midokura.midonet.cluster.data.zones.IpsecAvailabilityZone;
import com.midokura.packets.IPv4;

public interface AvailabilityZones {

    interface BuildersProvider {
        GreBuilder getGreZoneBuilder();

        IpsecBuilder getIpsecZoneBuilder();

        CapwapZoneBuilder getCapwapZoneBuilder();
    }

    /**
     * This is what an Availability zone Builder should look like:
     * - it needs to have a Zone Configuration
     * - it needs to have a per host Configuration type
     *
     * @param <ZoneConfigType>  is the type of the zone configuration
     * @param <HostConfigType>  is the type of the per host configuration
     * @param <ConcreteBuilder> is the actual interface providing the previous types
     */
    interface Builder<
        ZoneConfigType extends Builder.ZoneConfig,
        HostConfigType,
        ConcreteBuilder extends Builder<
            ZoneConfigType, HostConfigType, ConcreteBuilder>
        >
        extends com.midokura.midonet.cluster.client.Builder<ConcreteBuilder> {

        public interface ZoneConfig<T extends AvailabilityZone<T, ?>> {
            T getAvailabilityZoneConfig();
        }

        public interface HostConfig {
        }

        ConcreteBuilder setConfiguration(ZoneConfigType configuration);

        ConcreteBuilder addHost(UUID hostId, HostConfigType hostConfig);

        ConcreteBuilder removeHost(UUID hostId, HostConfigType hostConfig);
    }


    interface GreBuilder extends Builder<
        GreBuilder.ZoneConfig, GreAvailabilityZoneHost, GreBuilder> {

        interface ZoneConfig extends Builder.ZoneConfig<GreAvailabilityZone> {

        }
    }

    interface IpsecBuilder extends Builder<
        IpsecBuilder.ZoneConfig, IpsecBuilder.HostConfig, IpsecBuilder> {

        interface ZoneConfig extends Builder.ZoneConfig<IpsecAvailabilityZone> {
        }

        interface HostConfig extends Builder.HostConfig {
            IPv4 getAddress();
        }
    }

    interface CapwapZoneBuilder extends Builder<
        CapwapZoneBuilder.ZoneConfig, CapwapZoneBuilder.HostConfig,
        CapwapZoneBuilder> {

        interface ZoneConfig extends Builder.ZoneConfig<CapwapAvailabilityZone> {
        }

        interface HostConfig extends Builder.HostConfig {
            // IPv4 getAddress();
        }
    }
}
