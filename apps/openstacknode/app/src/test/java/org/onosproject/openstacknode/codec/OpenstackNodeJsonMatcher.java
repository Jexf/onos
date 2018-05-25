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
package org.onosproject.openstacknode.codec;

import com.fasterxml.jackson.databind.JsonNode;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.onosproject.openstacknode.api.Constants;
import org.onosproject.openstacknode.api.OpenstackAuth;
import org.onosproject.openstacknode.api.OpenstackNode;
import org.onosproject.openstacknode.api.OpenstackPhyInterface;

import static org.onosproject.openstacknode.api.Constants.DATA_IP;
import static org.onosproject.openstacknode.api.Constants.MANAGEMENT_IP;
import static org.onosproject.openstacknode.api.Constants.VLAN_INTF_NAME;

/**
 * Hamcrest matcher for openstack node.
 */
public final class OpenstackNodeJsonMatcher extends TypeSafeDiagnosingMatcher<JsonNode> {

    private final OpenstackNode node;
    private static final String INTEGRATION_BRIDGE = "integrationBridge";
    private static final String STATE = "state";
    private static final String PHYSICAL_INTERFACES = "phyIntfs";
    private static final String AUTHENTICATION = "authentication";
    private static final String END_POINT = "endPoint";

    private OpenstackNodeJsonMatcher(OpenstackNode node) {
        this.node = node;
    }

    @Override
    protected boolean matchesSafely(JsonNode jsonNode, Description description) {
        // check hostname
        String jsonHostname = jsonNode.get(Constants.HOST_NAME).asText();
        String hostname = node.hostname();
        if (!jsonHostname.equals(hostname)) {
            description.appendText("hostname was " + jsonHostname);
            return false;
        }

        // check type
        String jsonType = jsonNode.get(Constants.TYPE).asText();
        String type = node.type().name();
        if (!jsonType.equals(type)) {
            description.appendText("type was " + jsonType);
            return false;
        }

        // check management IP
        String jsonMgmtIp = jsonNode.get(MANAGEMENT_IP).asText();
        String mgmtIp = node.managementIp().toString();
        if (!jsonMgmtIp.equals(mgmtIp)) {
            description.appendText("management IP was " + jsonMgmtIp);
            return false;
        }

        // check integration bridge
        JsonNode jsonIntgBridge = jsonNode.get(INTEGRATION_BRIDGE);
        if (jsonIntgBridge != null) {
            String intgBridge = node.intgBridge().toString();
            if (!jsonIntgBridge.asText().equals(intgBridge)) {
                description.appendText("integration bridge was " + jsonIntgBridge);
                return false;
            }
        }

        // check state
        String jsonState = jsonNode.get(STATE).asText();
        String state = node.state().name();
        if (!jsonState.equals(state)) {
            description.appendText("state was " + jsonState);
            return false;
        }

        // check VLAN interface
        JsonNode jsonVlanIntf = jsonNode.get(VLAN_INTF_NAME);
        if (jsonVlanIntf != null) {
            String vlanIntf = node.vlanIntf();
            if (!jsonVlanIntf.asText().equals(vlanIntf)) {
                description.appendText("VLAN interface was " + jsonVlanIntf.asText());
                return false;
            }
        }

        // check data IP
        JsonNode jsonDataIp = jsonNode.get(DATA_IP);
        if (jsonDataIp != null) {
            String dataIp = node.dataIp().toString();
            if (!jsonDataIp.asText().equals(dataIp)) {
                description.appendText("Data IP was " + jsonDataIp.asText());
                return false;
            }
        }

        // check openstack auth
        JsonNode jsonAuth = jsonNode.get(AUTHENTICATION);
        if (jsonAuth != null) {
            OpenstackAuth auth = node.authentication();
            OpenstackAuthJsonMatcher authMatcher =
                    OpenstackAuthJsonMatcher.matchOpenstackAuth(auth);
            if (!authMatcher.matches(jsonAuth)) {
                return false;
            }
        }

        // check endpoint URL
        JsonNode jsonEndPoint = jsonNode.get(END_POINT);
        if (jsonEndPoint != null) {
            String endPoint = node.endPoint();
            if (!jsonEndPoint.asText().equals(endPoint)) {
                description.appendText("endpoint URL was " + jsonEndPoint);
                return false;
            }
        }

        // check physical interfaces
        JsonNode jsonPhyIntfs = jsonNode.get(PHYSICAL_INTERFACES);
        if (jsonPhyIntfs != null) {
            if (jsonPhyIntfs.size() != node.phyIntfs().size()) {
                description.appendText("physical interface size was " + jsonPhyIntfs.size());
                return false;
            }

            for (OpenstackPhyInterface phyIntf : node.phyIntfs()) {
                boolean intfFound = false;
                for (int intfIndex = 0; intfIndex < jsonPhyIntfs.size(); intfIndex++) {
                    OpenstackPhyInterfaceJsonMatcher intfMatcher =
                            OpenstackPhyInterfaceJsonMatcher.matchesOpenstackPhyInterface(phyIntf);
                    if (intfMatcher.matches(jsonPhyIntfs.get(intfIndex))) {
                        intfFound = true;
                        break;
                    }
                }

                if (!intfFound) {
                    description.appendText("PhyIntf not found " + phyIntf.toString());
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public void describeTo(Description description) {
        description.appendText(node.toString());
    }

    /**
     * Factory to allocate an openstack node matcher.
     *
     * @param node openstack node object we are looking for
     * @return matcher
     */
    public static OpenstackNodeJsonMatcher matchesOpenstackNode(OpenstackNode node) {
        return new OpenstackNodeJsonMatcher(node);
    }
}
