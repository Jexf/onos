/*
 * Copyright 2018-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.openstackvtap.impl;

import com.google.common.collect.Sets;
import com.google.common.testing.EqualsTester;
import org.junit.Before;
import org.junit.Test;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.TpPort;
import org.onosproject.openstackvtap.api.OpenstackVtap;
import org.onosproject.openstackvtap.api.OpenstackVtapCriterion;
import org.onosproject.openstackvtap.api.OpenstackVtapId;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Unit tests for DefaultOpenstackVtap.
 */
public class DefaultOpenstackVtapTest {

    private static final OpenstackVtapId VTAP_ID_1 = OpenstackVtapId.vTapId();
    private static final OpenstackVtapId VTAP_ID_2 = OpenstackVtapId.vTapId();

    private static final OpenstackVtap.Type VTAP_TYPE_1 = OpenstackVtap.Type.VTAP_TX;
    private static final OpenstackVtap.Type VTAP_TYPE_2 = OpenstackVtap.Type.VTAP_RX;

    private static final IpPrefix SRC_IP_PREFIX_1 =
            IpPrefix.valueOf(IpAddress.valueOf("10.10.10.1"), 32);
    private static final IpPrefix SRC_IP_PREFIX_2 =
            IpPrefix.valueOf(IpAddress.valueOf("20.20.20.1"), 32);
    private static final IpPrefix DST_IP_PREFIX_1 =
            IpPrefix.valueOf(IpAddress.valueOf("10.10.10.2"), 32);
    private static final IpPrefix DST_IP_PREFIX_2 =
            IpPrefix.valueOf(IpAddress.valueOf("20.20.20.2"), 32);

    private static final TpPort SRC_PORT_1 = TpPort.tpPort(10);
    private static final TpPort SRC_PORT_2 = TpPort.tpPort(20);
    private static final TpPort DST_PORT_1 = TpPort.tpPort(30);
    private static final TpPort DST_PORT_2 = TpPort.tpPort(40);

    private static final byte IP_PROTOCOL_1 = (byte) 1;
    private static final byte IP_PROTOCOL_2 = (byte) 2;

    private static final OpenstackVtapCriterion CRITERION_1 =
            createVtapCriterion(SRC_IP_PREFIX_1, DST_IP_PREFIX_1, SRC_PORT_1, DST_PORT_1, IP_PROTOCOL_1);
    private static final OpenstackVtapCriterion CRITERION_2 =
            createVtapCriterion(SRC_IP_PREFIX_2, DST_IP_PREFIX_2, SRC_PORT_2, DST_PORT_2, IP_PROTOCOL_2);

    private OpenstackVtap vtap1;
    private OpenstackVtap sameAsVtap1;
    private OpenstackVtap vtap2;

    @Before
    public void setup() {

        OpenstackVtap.Builder builder1 = DefaultOpenstackVtap.builder();

        vtap1 = builder1
                    .id(VTAP_ID_1)
                    .type(VTAP_TYPE_1)
                    .vTapCriterion(CRITERION_1)
                    .txDeviceIds(Sets.newHashSet())
                    .rxDeviceIds(Sets.newHashSet())
                    .annotations()
                    .build();

        OpenstackVtap.Builder builder2 = DefaultOpenstackVtap.builder();

        sameAsVtap1 = builder2
                        .id(VTAP_ID_1)
                        .type(VTAP_TYPE_1)
                        .vTapCriterion(CRITERION_1)
                        .txDeviceIds(Sets.newHashSet())
                        .rxDeviceIds(Sets.newHashSet())
                        .annotations()
                        .build();

        OpenstackVtap.Builder builder3 = DefaultOpenstackVtap.builder();

        vtap2 = builder3
                    .id(VTAP_ID_2)
                    .type(VTAP_TYPE_2)
                    .vTapCriterion(CRITERION_2)
                    .txDeviceIds(Sets.newHashSet())
                    .rxDeviceIds(Sets.newHashSet())
                    .annotations()
                    .build();

    }

    @Test
    public void testEquality() {
        new EqualsTester()
                .addEqualityGroup(vtap1, sameAsVtap1)
                .addEqualityGroup(vtap2).testEquals();
    }

    @Test
    public void testConstruction() {
        DefaultOpenstackVtap vtap = (DefaultOpenstackVtap) vtap1;

        assertThat(vtap.id(), is(VTAP_ID_1));
        assertThat(vtap.vTapCriterion(), is(CRITERION_1));
        assertThat(vtap.type(), is(VTAP_TYPE_1));
    }

    private static OpenstackVtapCriterion createVtapCriterion(IpPrefix srcIp, IpPrefix dstIp,
                                                              TpPort srcPort, TpPort dstPort,
                                                              byte ipProtocol) {
        return DefaultOpenstackVtapCriterion.builder()
                                            .srcIpPrefix(srcIp)
                                            .dstIpPrefix(dstIp)
                                            .srcTpPort(srcPort)
                                            .dstTpPort(dstPort)
                                            .ipProtocol(ipProtocol)
                                            .build();
    }
}
