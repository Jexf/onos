package org.onlab.onos.store.device.impl;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.ConcurrentInitializer;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.onos.cluster.ClusterService;
import org.onlab.onos.net.AnnotationsUtil;
import org.onlab.onos.net.DefaultAnnotations;
import org.onlab.onos.net.DefaultDevice;
import org.onlab.onos.net.DefaultPort;
import org.onlab.onos.net.Device;
import org.onlab.onos.net.Device.Type;
import org.onlab.onos.net.DeviceId;
import org.onlab.onos.net.Port;
import org.onlab.onos.net.PortNumber;
import org.onlab.onos.net.SparseAnnotations;
import org.onlab.onos.net.device.DefaultDeviceDescription;
import org.onlab.onos.net.device.DefaultPortDescription;
import org.onlab.onos.net.device.DeviceClockService;
import org.onlab.onos.net.device.DeviceDescription;
import org.onlab.onos.net.device.DeviceEvent;
import org.onlab.onos.net.device.DeviceStore;
import org.onlab.onos.net.device.DeviceStoreDelegate;
import org.onlab.onos.net.device.PortDescription;
import org.onlab.onos.net.device.Timestamp;
import org.onlab.onos.net.device.Timestamped;
import org.onlab.onos.net.provider.ProviderId;
import org.onlab.onos.store.AbstractStore;
import org.onlab.onos.store.cluster.messaging.ClusterCommunicationService;
import org.onlab.onos.store.cluster.messaging.ClusterMessage;
import org.onlab.onos.store.cluster.messaging.ClusterMessageHandler;
import org.onlab.onos.store.common.impl.DeviceMastershipBasedTimestamp;
import org.onlab.onos.store.common.impl.MastershipBasedTimestampSerializer;
import org.onlab.onos.store.serializers.KryoPoolUtil;
import org.onlab.onos.store.serializers.KryoSerializer;
import org.onlab.util.KryoPool;
import org.onlab.util.NewConcurrentHashMap;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.notNull;
import static org.onlab.onos.net.device.DeviceEvent.Type.*;
import static org.slf4j.LoggerFactory.getLogger;
import static org.apache.commons.lang3.concurrent.ConcurrentUtils.createIfAbsentUnchecked;
import static org.onlab.onos.net.DefaultAnnotations.merge;
import static org.onlab.onos.net.DefaultAnnotations.union;
import static com.google.common.base.Verify.verify;

// TODO: give me a better name
/**
 * Manages inventory of infrastructure devices using gossip protocol to distribute
 * information.
 */
@Component(immediate = true)
@Service
public class GossipDeviceStore
        extends AbstractStore<DeviceEvent, DeviceStoreDelegate>
        implements DeviceStore {

    private final Logger log = getLogger(getClass());

    public static final String DEVICE_NOT_FOUND = "Device with ID %s not found";

    // TODO: Check if inner Map can be replaced with plain Map
    // innerMap is used to lock a Device, thus instance should never be replaced.
    // collection of Description given from various providers
    private final ConcurrentMap<DeviceId,
                            ConcurrentMap<ProviderId, DeviceDescriptions>>
                                deviceDescs = Maps.newConcurrentMap();

    // cache of Device and Ports generated by compositing descriptions from providers
    private final ConcurrentMap<DeviceId, Device> devices = Maps.newConcurrentMap();
    private final ConcurrentMap<DeviceId, ConcurrentMap<PortNumber, Port>> devicePorts = Maps.newConcurrentMap();

    // to be updated under Device lock
    private final Map<DeviceId, Timestamp> offline = Maps.newHashMap();
    private final Map<DeviceId, Timestamp> removalRequest = Maps.newHashMap();

    // available(=UP) devices
    private final Set<DeviceId> availableDevices = Sets.newConcurrentHashSet();

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceClockService clockService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterCommunicationService clusterCommunicator;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    private static final KryoSerializer SERIALIZER = new KryoSerializer() {
        @Override
        protected void setupKryoPool() {
            serializerPool = KryoPool.newBuilder()
                    .register(KryoPoolUtil.API)
                    .register(InternalDeviceEvent.class, new InternalDeviceEventSerializer())
                    .register(InternalDeviceOfflineEvent.class, new InternalDeviceOfflineEventSerializer())
                    .register(InternalDeviceRemovedEvent.class)
                    .register(InternalPortEvent.class, new InternalPortEventSerializer())
                    .register(InternalPortStatusEvent.class, new InternalPortStatusEventSerializer())
                    .register(Timestamp.class)
                    .register(Timestamped.class)
                    .register(DeviceMastershipBasedTimestamp.class, new MastershipBasedTimestampSerializer())
                    .build()
                    .populate(1);
        }

    };

    @Activate
    public void activate() {
        clusterCommunicator.addSubscriber(
                GossipDeviceStoreMessageSubjects.DEVICE_UPDATE, new InternalDeviceEventListener());
        clusterCommunicator.addSubscriber(
                GossipDeviceStoreMessageSubjects.DEVICE_OFFLINE, new InternalDeviceOfflineEventListener());
        clusterCommunicator.addSubscriber(
                GossipDeviceStoreMessageSubjects.DEVICE_REMOVED, new InternalDeviceRemovedEventListener());
        clusterCommunicator.addSubscriber(
                GossipDeviceStoreMessageSubjects.PORT_UPDATE, new InternalPortEventListener());
        clusterCommunicator.addSubscriber(
                GossipDeviceStoreMessageSubjects.PORT_STATUS_UPDATE, new InternalPortStatusEventListener());
        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        deviceDescs.clear();
        devices.clear();
        devicePorts.clear();
        availableDevices.clear();
        log.info("Stopped");
    }

    @Override
    public int getDeviceCount() {
        return devices.size();
    }

    @Override
    public Iterable<Device> getDevices() {
        return Collections.unmodifiableCollection(devices.values());
    }

    @Override
    public Device getDevice(DeviceId deviceId) {
        return devices.get(deviceId);
    }

    @Override
    public synchronized DeviceEvent createOrUpdateDevice(ProviderId providerId,
                                     DeviceId deviceId,
                                     DeviceDescription deviceDescription) {
        Timestamp newTimestamp = clockService.getTimestamp(deviceId);
        final Timestamped<DeviceDescription> deltaDesc = new Timestamped<>(deviceDescription, newTimestamp);
        DeviceEvent event = createOrUpdateDeviceInternal(providerId, deviceId, deltaDesc);
        if (event != null) {
            log.info("Notifying peers of a device update topology event for providerId: {} and deviceId: {}",
                providerId, deviceId);
            try {
                notifyPeers(new InternalDeviceEvent(providerId, deviceId, deltaDesc));
            } catch (IOException e) {
                log.error("Failed to notify peers of a device update topology event for providerId: "
                        + providerId + " and deviceId: " + deviceId, e);
            }
        }
        return event;
    }

    private DeviceEvent createOrUpdateDeviceInternal(ProviderId providerId,
                                    DeviceId deviceId,
                                    Timestamped<DeviceDescription> deltaDesc) {

        // Collection of DeviceDescriptions for a Device
        ConcurrentMap<ProviderId, DeviceDescriptions> providerDescs
            = getDeviceDescriptions(deviceId);

        synchronized (providerDescs) {
            // locking per device

            if (isDeviceRemoved(deviceId, deltaDesc.timestamp())) {
                log.debug("Ignoring outdated event: {}", deltaDesc);
                return null;
            }

            DeviceDescriptions descs
                = createIfAbsentUnchecked(providerDescs, providerId,
                    new InitDeviceDescs(deltaDesc));

            final Device oldDevice = devices.get(deviceId);
            final Device newDevice;

            if (deltaDesc == descs.getDeviceDesc() ||
                deltaDesc.isNewer(descs.getDeviceDesc())) {
                // on new device or valid update
                descs.putDeviceDesc(deltaDesc);
                newDevice = composeDevice(deviceId, providerDescs);
            } else {
                // outdated event, ignored.
                return null;
            }
            if (oldDevice == null) {
                // ADD
                return createDevice(providerId, newDevice, deltaDesc.timestamp());
            } else {
                // UPDATE or ignore (no change or stale)
                return updateDevice(providerId, oldDevice, newDevice, deltaDesc.timestamp());
            }
        }
    }

    // Creates the device and returns the appropriate event if necessary.
    // Guarded by deviceDescs value (=Device lock)
    private DeviceEvent createDevice(ProviderId providerId,
                                     Device newDevice, Timestamp timestamp) {

        // update composed device cache
        Device oldDevice = devices.putIfAbsent(newDevice.id(), newDevice);
        verify(oldDevice == null,
                "Unexpected Device in cache. PID:%s [old=%s, new=%s]",
                providerId, oldDevice, newDevice);

        if (!providerId.isAncillary()) {
            markOnline(newDevice.id(), timestamp);
        }

        return new DeviceEvent(DeviceEvent.Type.DEVICE_ADDED, newDevice, null);
    }

    // Updates the device and returns the appropriate event if necessary.
    // Guarded by deviceDescs value (=Device lock)
    private DeviceEvent updateDevice(ProviderId providerId,
                                     Device oldDevice,
                                     Device newDevice, Timestamp newTimestamp) {

        // We allow only certain attributes to trigger update
        if (!Objects.equals(oldDevice.hwVersion(), newDevice.hwVersion()) ||
            !Objects.equals(oldDevice.swVersion(), newDevice.swVersion()) ||
            !AnnotationsUtil.isEqual(oldDevice.annotations(), newDevice.annotations())) {

            boolean replaced = devices.replace(newDevice.id(), oldDevice, newDevice);
            if (!replaced) {
                verify(replaced,
                        "Replacing devices cache failed. PID:%s [expected:%s, found:%s, new=%s]",
                        providerId, oldDevice, devices.get(newDevice.id())
                        , newDevice);
            }
            if (!providerId.isAncillary()) {
                markOnline(newDevice.id(), newTimestamp);
            }
            return new DeviceEvent(DeviceEvent.Type.DEVICE_UPDATED, newDevice, null);
        }

        // Otherwise merely attempt to change availability if primary provider
        if (!providerId.isAncillary()) {
            boolean added = markOnline(newDevice.id(), newTimestamp);
            return !added ? null :
                    new DeviceEvent(DEVICE_AVAILABILITY_CHANGED, newDevice, null);
        }
        return null;
    }

    @Override
    public DeviceEvent markOffline(DeviceId deviceId) {
        Timestamp timestamp = clockService.getTimestamp(deviceId);
        DeviceEvent event = markOfflineInternal(deviceId, timestamp);
        if (event != null) {
            log.info("Notifying peers of a device offline topology event for deviceId: {}",
                    deviceId);
            try {
                notifyPeers(new InternalDeviceOfflineEvent(deviceId, timestamp));
            } catch (IOException e) {
                log.error("Failed to notify peers of a device offline topology event for deviceId: {}",
                     deviceId);
            }
        }
        return event;
    }

    private DeviceEvent markOfflineInternal(DeviceId deviceId, Timestamp timestamp) {

        Map<ProviderId, DeviceDescriptions> providerDescs
            = getDeviceDescriptions(deviceId);

        // locking device
        synchronized (providerDescs) {

            // accept off-line if given timestamp is newer than
            // the latest Timestamp from Primary provider
            DeviceDescriptions primDescs = getPrimaryDescriptions(providerDescs);
            Timestamp lastTimestamp = primDescs.getLatestTimestamp();
            if (timestamp.compareTo(lastTimestamp) <= 0) {
                // outdated event ignore
                return null;
            }

            offline.put(deviceId, timestamp);

            Device device = devices.get(deviceId);
            if (device == null) {
                return null;
            }
            boolean removed = availableDevices.remove(deviceId);
            if (removed) {
                // TODO: broadcast ... DOWN only?
                return new DeviceEvent(DEVICE_AVAILABILITY_CHANGED, device, null);
            }
            return null;
        }
    }

    /**
     * Marks the device as available if the given timestamp is not outdated,
     * compared to the time the device has been marked offline.
     *
     * @param deviceId identifier of the device
     * @param timestamp of the event triggering this change.
     * @return true if availability change request was accepted and changed the state
     */
    // Guarded by deviceDescs value (=Device lock)
    private boolean markOnline(DeviceId deviceId, Timestamp timestamp) {
        // accept on-line if given timestamp is newer than
        // the latest offline request Timestamp
        Timestamp offlineTimestamp = offline.get(deviceId);
        if (offlineTimestamp == null ||
            offlineTimestamp.compareTo(timestamp) < 0) {

            offline.remove(deviceId);
            return availableDevices.add(deviceId);
        }
        return false;
    }

    @Override
    public synchronized List<DeviceEvent> updatePorts(ProviderId providerId,
                                       DeviceId deviceId,
                                       List<PortDescription> portDescriptions) {
        Timestamp newTimestamp = clockService.getTimestamp(deviceId);

        Timestamped<List<PortDescription>> timestampedPortDescriptions =
            new Timestamped<>(portDescriptions, newTimestamp);

        List<DeviceEvent> events = updatePortsInternal(providerId, deviceId, timestampedPortDescriptions);
        if (!events.isEmpty()) {
            log.info("Notifying peers of a port update topology event for providerId: {} and deviceId: {}",
                    providerId, deviceId);
            try {
                notifyPeers(new InternalPortEvent(providerId, deviceId, timestampedPortDescriptions));
            } catch (IOException e) {
                log.error("Failed to notify peers of a port update topology event or providerId: "
                    + providerId + " and deviceId: " + deviceId, e);
            }
        }
        return events;
    }

    private List<DeviceEvent> updatePortsInternal(ProviderId providerId,
                                DeviceId deviceId,
                                Timestamped<List<PortDescription>> portDescriptions) {

        Device device = devices.get(deviceId);
        checkArgument(device != null, DEVICE_NOT_FOUND, deviceId);

        ConcurrentMap<ProviderId, DeviceDescriptions> descsMap = deviceDescs.get(deviceId);
        checkArgument(descsMap != null, DEVICE_NOT_FOUND, deviceId);

        List<DeviceEvent> events = new ArrayList<>();
        synchronized (descsMap) {

            if (isDeviceRemoved(deviceId, portDescriptions.timestamp())) {
                log.debug("Ignoring outdated events: {}", portDescriptions);
                return null;
            }

            DeviceDescriptions descs = descsMap.get(providerId);
            // every provider must provide DeviceDescription.
            checkArgument(descs != null,
                    "Device description for Device ID %s from Provider %s was not found",
                    deviceId, providerId);

            Map<PortNumber, Port> ports = getPortMap(deviceId);

            final Timestamp newTimestamp = portDescriptions.timestamp();

            // Add new ports
            Set<PortNumber> processed = new HashSet<>();
            for (PortDescription portDescription : portDescriptions.value()) {
                final PortNumber number = portDescription.portNumber();
                processed.add(number);

                final Port oldPort = ports.get(number);
                final Port newPort;


                final Timestamped<PortDescription> existingPortDesc = descs.getPortDesc(number);
                if (existingPortDesc == null ||
                    newTimestamp.compareTo(existingPortDesc.timestamp()) >= 0) {
                    // on new port or valid update
                    // update description
                    descs.putPortDesc(new Timestamped<>(portDescription,
                                            portDescriptions.timestamp()));
                    newPort = composePort(device, number, descsMap);
                } else {
                    // outdated event, ignored.
                    continue;
                }

                events.add(oldPort == null ?
                                   createPort(device, newPort, ports) :
                                   updatePort(device, oldPort, newPort, ports));
            }

            events.addAll(pruneOldPorts(device, ports, processed));
        }
        return FluentIterable.from(events).filter(notNull()).toList();
    }

    // Creates a new port based on the port description adds it to the map and
    // Returns corresponding event.
    // Guarded by deviceDescs value (=Device lock)
    private DeviceEvent createPort(Device device, Port newPort,
                                   Map<PortNumber, Port> ports) {
        ports.put(newPort.number(), newPort);
        return new DeviceEvent(PORT_ADDED, device, newPort);
    }

    // Checks if the specified port requires update and if so, it replaces the
    // existing entry in the map and returns corresponding event.
    // Guarded by deviceDescs value (=Device lock)
    private DeviceEvent updatePort(Device device, Port oldPort,
                                   Port newPort,
                                   Map<PortNumber, Port> ports) {
        if (oldPort.isEnabled() != newPort.isEnabled() ||
            !AnnotationsUtil.isEqual(oldPort.annotations(), newPort.annotations())) {

            ports.put(oldPort.number(), newPort);
            return new DeviceEvent(PORT_UPDATED, device, newPort);
        }
        return null;
    }

    // Prunes the specified list of ports based on which ports are in the
    // processed list and returns list of corresponding events.
    // Guarded by deviceDescs value (=Device lock)
    private List<DeviceEvent> pruneOldPorts(Device device,
                                            Map<PortNumber, Port> ports,
                                            Set<PortNumber> processed) {
        List<DeviceEvent> events = new ArrayList<>();
        Iterator<PortNumber> iterator = ports.keySet().iterator();
        while (iterator.hasNext()) {
            PortNumber portNumber = iterator.next();
            if (!processed.contains(portNumber)) {
                events.add(new DeviceEvent(PORT_REMOVED, device,
                                           ports.get(portNumber)));
                iterator.remove();
            }
        }
        return events;
    }

    // Gets the map of ports for the specified device; if one does not already
    // exist, it creates and registers a new one.
    private ConcurrentMap<PortNumber, Port> getPortMap(DeviceId deviceId) {
        return createIfAbsentUnchecked(devicePorts, deviceId,
                NewConcurrentHashMap.<PortNumber, Port>ifNeeded());
    }

    private ConcurrentMap<ProviderId, DeviceDescriptions> getDeviceDescriptions(
            DeviceId deviceId) {
        return createIfAbsentUnchecked(deviceDescs, deviceId,
                NewConcurrentHashMap.<ProviderId, DeviceDescriptions>ifNeeded());
    }

    @Override
    public synchronized DeviceEvent updatePortStatus(ProviderId providerId, DeviceId deviceId,
            PortDescription portDescription) {
        Timestamp newTimestamp = clockService.getTimestamp(deviceId);
        final Timestamped<PortDescription> deltaDesc = new Timestamped<>(portDescription, newTimestamp);
        DeviceEvent event = updatePortStatusInternal(providerId, deviceId, deltaDesc);
        if (event != null) {
            log.info("Notifying peers of a port status update topology event for providerId: {} and deviceId: {}",
                        providerId, deviceId);
            try {
                notifyPeers(new InternalPortStatusEvent(providerId, deviceId, deltaDesc));
            } catch (IOException e) {
                log.error("Failed to notify peers of a port status update topology event or providerId: "
                        + providerId + " and deviceId: " + deviceId, e);
            }
        }
        return event;
    }

    private DeviceEvent updatePortStatusInternal(ProviderId providerId, DeviceId deviceId,
                Timestamped<PortDescription> deltaDesc) {

        Device device = devices.get(deviceId);
        checkArgument(device != null, DEVICE_NOT_FOUND, deviceId);

        ConcurrentMap<ProviderId, DeviceDescriptions> descsMap = deviceDescs.get(deviceId);
        checkArgument(descsMap != null, DEVICE_NOT_FOUND, deviceId);

        synchronized (descsMap) {

            if (isDeviceRemoved(deviceId, deltaDesc.timestamp())) {
                log.debug("Ignoring outdated event: {}", deltaDesc);
                return null;
            }

            DeviceDescriptions descs = descsMap.get(providerId);
            // assuming all providers must to give DeviceDescription
            checkArgument(descs != null,
                    "Device description for Device ID %s from Provider %s was not found",
                    deviceId, providerId);

            ConcurrentMap<PortNumber, Port> ports = getPortMap(deviceId);
            final PortNumber number = deltaDesc.value().portNumber();
            final Port oldPort = ports.get(number);
            final Port newPort;

            final Timestamped<PortDescription> existingPortDesc = descs.getPortDesc(number);
            if (existingPortDesc == null ||
                deltaDesc == existingPortDesc ||
                deltaDesc.isNewer(existingPortDesc)) {
                // on new port or valid update
                // update description
                descs.putPortDesc(deltaDesc);
                newPort = composePort(device, number, descsMap);
            } else {
                // outdated event, ignored.
                return null;
            }

            if (oldPort == null) {
                return createPort(device, newPort, ports);
            } else {
                return updatePort(device, oldPort, newPort, ports);
            }
        }
    }

    @Override
    public List<Port> getPorts(DeviceId deviceId) {
        Map<PortNumber, Port> ports = devicePorts.get(deviceId);
        if (ports == null) {
            return Collections.emptyList();
        }
        return ImmutableList.copyOf(ports.values());
    }

    @Override
    public Port getPort(DeviceId deviceId, PortNumber portNumber) {
        Map<PortNumber, Port> ports = devicePorts.get(deviceId);
        return ports == null ? null : ports.get(portNumber);
    }

    @Override
    public boolean isAvailable(DeviceId deviceId) {
        return availableDevices.contains(deviceId);
    }

    @Override
    public synchronized DeviceEvent removeDevice(DeviceId deviceId) {
        Timestamp timestamp = clockService.getTimestamp(deviceId);
        DeviceEvent event = removeDeviceInternal(deviceId, timestamp);
        if (event != null) {
            log.info("Notifying peers of a device removed topology event for deviceId: {}",
                    deviceId);
            try {
                notifyPeers(new InternalDeviceRemovedEvent(deviceId, timestamp));
            } catch (IOException e) {
                log.error("Failed to notify peers of a device removed topology event for deviceId: {}",
                     deviceId);
            }
        }
        return event;
    }

    private DeviceEvent removeDeviceInternal(DeviceId deviceId,
                                             Timestamp timestamp) {

        Map<ProviderId, DeviceDescriptions> descs = getDeviceDescriptions(deviceId);
        synchronized (descs) {
            // accept removal request if given timestamp is newer than
            // the latest Timestamp from Primary provider
            DeviceDescriptions primDescs = getPrimaryDescriptions(descs);
            Timestamp lastTimestamp = primDescs.getLatestTimestamp();
            if (timestamp.compareTo(lastTimestamp) <= 0) {
                // outdated event ignore
                return null;
            }
            removalRequest.put(deviceId, timestamp);

            Device device = devices.remove(deviceId);
            // should DEVICE_REMOVED carry removed ports?
            Map<PortNumber, Port> ports = devicePorts.get(deviceId);
            if (ports != null) {
                ports.clear();
            }
            markOfflineInternal(deviceId, timestamp);
            descs.clear();
            return device == null ? null :
                new DeviceEvent(DEVICE_REMOVED, device, null);
        }
    }

    private boolean isDeviceRemoved(DeviceId deviceId, Timestamp timestampToCheck) {
        Timestamp removalTimestamp = removalRequest.get(deviceId);
        if (removalTimestamp != null &&
            removalTimestamp.compareTo(timestampToCheck) >= 0) {
            // removalRequest is more recent
            return true;
        }
        return false;
    }

    /**
     * Returns a Device, merging description given from multiple Providers.
     *
     * @param deviceId device identifier
     * @param providerDescs Collection of Descriptions from multiple providers
     * @return Device instance
     */
    private Device composeDevice(DeviceId deviceId,
            ConcurrentMap<ProviderId, DeviceDescriptions> providerDescs) {

        checkArgument(!providerDescs.isEmpty(), "No Device descriptions supplied");

        ProviderId primary = pickPrimaryPID(providerDescs);

        DeviceDescriptions desc = providerDescs.get(primary);

        final DeviceDescription base = desc.getDeviceDesc().value();
        Type type = base.type();
        String manufacturer = base.manufacturer();
        String hwVersion = base.hwVersion();
        String swVersion = base.swVersion();
        String serialNumber = base.serialNumber();
        DefaultAnnotations annotations = DefaultAnnotations.builder().build();
        annotations = merge(annotations, base.annotations());

        for (Entry<ProviderId, DeviceDescriptions> e : providerDescs.entrySet()) {
            if (e.getKey().equals(primary)) {
                continue;
            }
            // TODO: should keep track of Description timestamp
            // and only merge conflicting keys when timestamp is newer
            // Currently assuming there will never be a key conflict between
            // providers

            // annotation merging. not so efficient, should revisit later
            annotations = merge(annotations, e.getValue().getDeviceDesc().value().annotations());
        }

        return new DefaultDevice(primary, deviceId , type, manufacturer,
                            hwVersion, swVersion, serialNumber, annotations);
    }

    /**
     * Returns a Port, merging description given from multiple Providers.
     *
     * @param device device the port is on
     * @param number port number
     * @param providerDescs Collection of Descriptions from multiple providers
     * @return Port instance
     */
    private Port composePort(Device device, PortNumber number,
                ConcurrentMap<ProviderId, DeviceDescriptions> providerDescs) {

        ProviderId primary = pickPrimaryPID(providerDescs);
        DeviceDescriptions primDescs = providerDescs.get(primary);
        // if no primary, assume not enabled
        // TODO: revisit this default port enabled/disabled behavior
        boolean isEnabled = false;
        DefaultAnnotations annotations = DefaultAnnotations.builder().build();

        final Timestamped<PortDescription> portDesc = primDescs.getPortDesc(number);
        if (portDesc != null) {
            isEnabled = portDesc.value().isEnabled();
            annotations = merge(annotations, portDesc.value().annotations());
        }

        for (Entry<ProviderId, DeviceDescriptions> e : providerDescs.entrySet()) {
            if (e.getKey().equals(primary)) {
                continue;
            }
            // TODO: should keep track of Description timestamp
            // and only merge conflicting keys when timestamp is newer
            // Currently assuming there will never be a key conflict between
            // providers

            // annotation merging. not so efficient, should revisit later
            final Timestamped<PortDescription> otherPortDesc = e.getValue().getPortDesc(number);
            if (otherPortDesc != null) {
                annotations = merge(annotations, otherPortDesc.value().annotations());
            }
        }

        return new DefaultPort(device, number, isEnabled, annotations);
    }

    /**
     * @return primary ProviderID, or randomly chosen one if none exists
     */
    private ProviderId pickPrimaryPID(
            Map<ProviderId, DeviceDescriptions> providerDescs) {
        ProviderId fallBackPrimary = null;
        for (Entry<ProviderId, DeviceDescriptions> e : providerDescs.entrySet()) {
            if (!e.getKey().isAncillary()) {
                return e.getKey();
            } else if (fallBackPrimary == null) {
                // pick randomly as a fallback in case there is no primary
                fallBackPrimary = e.getKey();
            }
        }
        return fallBackPrimary;
    }

    private DeviceDescriptions getPrimaryDescriptions(
                            Map<ProviderId, DeviceDescriptions> providerDescs) {
        ProviderId pid = pickPrimaryPID(providerDescs);
        return providerDescs.get(pid);
    }

    public static final class InitDeviceDescs
        implements ConcurrentInitializer<DeviceDescriptions> {

        private final Timestamped<DeviceDescription> deviceDesc;

        public InitDeviceDescs(Timestamped<DeviceDescription> deviceDesc) {
            this.deviceDesc = checkNotNull(deviceDesc);
        }
        @Override
        public DeviceDescriptions get() throws ConcurrentException {
            return new DeviceDescriptions(deviceDesc);
        }
    }


    /**
     * Collection of Description of a Device and it's Ports given from a Provider.
     */
    public static class DeviceDescriptions {

        private final AtomicReference<Timestamped<DeviceDescription>> deviceDesc;
        private final ConcurrentMap<PortNumber, Timestamped<PortDescription>> portDescs;

        public DeviceDescriptions(Timestamped<DeviceDescription> desc) {
            this.deviceDesc = new AtomicReference<>(checkNotNull(desc));
            this.portDescs = new ConcurrentHashMap<>();
        }

        Timestamp getLatestTimestamp() {
            Timestamp latest = deviceDesc.get().timestamp();
            for (Timestamped<PortDescription> desc : portDescs.values()) {
                if (desc.timestamp().compareTo(latest) > 0) {
                    latest = desc.timestamp();
                }
            }
            return latest;
        }

        public Timestamped<DeviceDescription> getDeviceDesc() {
            return deviceDesc.get();
        }

        public Timestamped<PortDescription> getPortDesc(PortNumber number) {
            return portDescs.get(number);
        }

        /**
         * Puts DeviceDescription, merging annotations as necessary.
         *
         * @param newDesc new DeviceDescription
         * @return previous DeviceDescription
         */
        public synchronized Timestamped<DeviceDescription> putDeviceDesc(Timestamped<DeviceDescription> newDesc) {
            Timestamped<DeviceDescription> oldOne = deviceDesc.get();
            Timestamped<DeviceDescription> newOne = newDesc;
            if (oldOne != null) {
                SparseAnnotations merged = union(oldOne.value().annotations(),
                                                 newDesc.value().annotations());
                newOne = new Timestamped<DeviceDescription>(
                        new DefaultDeviceDescription(newDesc.value(), merged),
                        newDesc.timestamp());
            }
            return deviceDesc.getAndSet(newOne);
        }

        /**
         * Puts PortDescription, merging annotations as necessary.
         *
         * @param newDesc new PortDescription
         * @return previous PortDescription
         */
        public synchronized Timestamped<PortDescription> putPortDesc(Timestamped<PortDescription> newDesc) {
            Timestamped<PortDescription> oldOne = portDescs.get(newDesc.value().portNumber());
            Timestamped<PortDescription> newOne = newDesc;
            if (oldOne != null) {
                SparseAnnotations merged = union(oldOne.value().annotations(),
                                                 newDesc.value().annotations());
                newOne = new Timestamped<PortDescription>(
                        new DefaultPortDescription(newDesc.value(), merged),
                        newDesc.timestamp());
            }
            return portDescs.put(newOne.value().portNumber(), newOne);
        }
    }

    private void notifyPeers(InternalDeviceEvent event) throws IOException {
        ClusterMessage message = new ClusterMessage(
                clusterService.getLocalNode().id(),
                GossipDeviceStoreMessageSubjects.DEVICE_UPDATE,
                SERIALIZER.encode(event));
        clusterCommunicator.broadcast(message);
    }

    private void notifyPeers(InternalDeviceOfflineEvent event) throws IOException {
        ClusterMessage message = new ClusterMessage(
                clusterService.getLocalNode().id(),
                GossipDeviceStoreMessageSubjects.DEVICE_OFFLINE,
                SERIALIZER.encode(event));
        clusterCommunicator.broadcast(message);
    }

    private void notifyPeers(InternalDeviceRemovedEvent event) throws IOException {
        ClusterMessage message = new ClusterMessage(
                clusterService.getLocalNode().id(),
                GossipDeviceStoreMessageSubjects.DEVICE_REMOVED,
                SERIALIZER.encode(event));
        clusterCommunicator.broadcast(message);
    }

    private void notifyPeers(InternalPortEvent event) throws IOException {
        ClusterMessage message = new ClusterMessage(
                clusterService.getLocalNode().id(),
                GossipDeviceStoreMessageSubjects.PORT_UPDATE,
                SERIALIZER.encode(event));
        clusterCommunicator.broadcast(message);
    }

    private void notifyPeers(InternalPortStatusEvent event) throws IOException {
        ClusterMessage message = new ClusterMessage(
                clusterService.getLocalNode().id(),
                GossipDeviceStoreMessageSubjects.PORT_STATUS_UPDATE,
                SERIALIZER.encode(event));
        clusterCommunicator.broadcast(message);
    }

    private void notifyDelegateIfNotNull(DeviceEvent event) {
        if (event != null) {
            notifyDelegate(event);
        }
    }

    private class InternalDeviceEventListener implements ClusterMessageHandler {
        @Override
        public void handle(ClusterMessage message) {

            log.info("Received device update event from peer: {}", message.sender());
            InternalDeviceEvent event = (InternalDeviceEvent) SERIALIZER.decode(message.payload());

            ProviderId providerId = event.providerId();
            DeviceId deviceId = event.deviceId();
            Timestamped<DeviceDescription> deviceDescription = event.deviceDescription();

            notifyDelegateIfNotNull(createOrUpdateDeviceInternal(providerId, deviceId, deviceDescription));
        }
    }

    private class InternalDeviceOfflineEventListener implements ClusterMessageHandler {
        @Override
        public void handle(ClusterMessage message) {

            log.info("Received device offline event from peer: {}", message.sender());
            InternalDeviceOfflineEvent event = (InternalDeviceOfflineEvent) SERIALIZER.decode(message.payload());

            DeviceId deviceId = event.deviceId();
            Timestamp timestamp = event.timestamp();

            notifyDelegateIfNotNull(markOfflineInternal(deviceId, timestamp));
        }
    }

    private class InternalDeviceRemovedEventListener implements ClusterMessageHandler {
        @Override
        public void handle(ClusterMessage message) {

            log.info("Received device removed event from peer: {}", message.sender());
            InternalDeviceRemovedEvent event = (InternalDeviceRemovedEvent) SERIALIZER.decode(message.payload());

            DeviceId deviceId = event.deviceId();
            Timestamp timestamp = event.timestamp();

            notifyDelegateIfNotNull(removeDeviceInternal(deviceId, timestamp));
        }
    }

    private class InternalPortEventListener implements ClusterMessageHandler {
        @Override
        public void handle(ClusterMessage message) {

            log.info("Received port update event from peer: {}", message.sender());
            InternalPortEvent event = (InternalPortEvent) SERIALIZER.decode(message.payload());

            ProviderId providerId = event.providerId();
            DeviceId deviceId = event.deviceId();
            Timestamped<List<PortDescription>> portDescriptions = event.portDescriptions();

            notifyDelegate(updatePortsInternal(providerId, deviceId, portDescriptions));
        }
    }

    private class InternalPortStatusEventListener implements ClusterMessageHandler {
        @Override
        public void handle(ClusterMessage message) {

            log.info("Received port status update event from peer: {}", message.sender());
            InternalPortStatusEvent event = (InternalPortStatusEvent) SERIALIZER.decode(message.payload());

            ProviderId providerId = event.providerId();
            DeviceId deviceId = event.deviceId();
            Timestamped<PortDescription> portDescription = event.portDescription();

            notifyDelegateIfNotNull(updatePortStatusInternal(providerId, deviceId, portDescription));
        }
    }
}
