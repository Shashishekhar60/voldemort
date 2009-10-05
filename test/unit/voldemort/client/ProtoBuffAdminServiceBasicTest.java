package voldemort.client;

import com.google.common.collect.ImmutableList;
import junit.framework.TestCase;
import voldemort.ServerTestUtils;
import voldemort.TestUtils;
import voldemort.client.protocol.admin.AdminClientRequestFormat;
import voldemort.client.protocol.admin.NativeAdminClientRequestFormat;
import voldemort.cluster.Cluster;
import voldemort.cluster.Node;
import voldemort.routing.RoutingStrategy;
import voldemort.server.VoldemortConfig;
import voldemort.server.VoldemortServer;
import voldemort.store.Store;
import voldemort.store.metadata.MetadataStore;
import voldemort.utils.ByteArray;
import voldemort.utils.ByteUtils;
import voldemort.utils.Pair;
import voldemort.versioning.Versioned;

import java.io.IOException;
import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: afeinber
 * Date: Sep 30, 2009
 * Time: 12:56:06 PM
 * To change this template use File | Settings | File Templates.
 */
public class ProtoBuffAdminServiceBasicTest extends TestCase {
    private static String storeName = "test-replication-memory";
    private static String storesXmlfile = "test/common/voldemort/config/stores.xml";

    VoldemortConfig config;
    VoldemortServer server;
    Cluster cluster;

    @Override
    public void setUp() throws IOException {
       // start 2 node cluster with free ports
        int[] ports = ServerTestUtils.findFreePorts(2);
        Node node0 = new Node(0, "localhost", ports[0], ports[1], Arrays.asList(new Integer[] { 0,
                1 }));

        ports = ServerTestUtils.findFreePorts(2);
        Node node1 = new Node(1, "localhost", ports[0], ports[1], Arrays.asList(new Integer[] { 2,
                3 }));

        cluster = new Cluster("admin-service-test", Arrays.asList(new Node[] { node0, node1 }));
        config = ServerTestUtils.createServerConfig(0,
                                                    TestUtils.createTempDir().getAbsolutePath(),
                                                    null,
                                                    storesXmlfile);
        server = new VoldemortServer(config, cluster);
        server.start();
    }

    @Override
    public void tearDown() throws IOException, InterruptedException {
        server.stop();
    }

    public AdminClientRequestFormat getAdminClient() {
        return ServerTestUtils.getAdminClient(server.getIdentityNode(), server.getMetadataStore(), true);
    }

         
    public void testUpdateClusterMetadata() {
        Cluster cluster = server.getMetadataStore().getCluster();
        List<Node> nodes = new ArrayList<Node>(cluster.getNodes());
        nodes.add(new Node(3, "localhost", 8883, 6668, ImmutableList.of(4,5)));
        Cluster updatedCluster = new Cluster("new-cluster", nodes);

        AdminClientRequestFormat client = getAdminClient();
        client.updateClusterMetadata(server.getIdentityNode().getId(), updatedCluster);

        assertEquals("Cluster should match", updatedCluster, server.getMetadataStore().getCluster());
        assertEquals("AdminClient.getMetdata() should match",
                     client.getClusterMetadata(server.getIdentityNode().getId()).getValue(),
                     updatedCluster);
        
    }

    public void testStateTransitions() {
        // change to REBALANCING STATE
        AdminClientRequestFormat client = getAdminClient();
        client.updateServerState(server.getIdentityNode().getId(),
                                 MetadataStore.ServerState.REBALANCING_STEALER_STATE);

        MetadataStore.ServerState state = server.getMetadataStore().getServerState();
        assertEquals("State should be changed correctly to rebalancing state",
                     MetadataStore.ServerState.REBALANCING_STEALER_STATE,
                     state);

        // change back to NORMAL state
        client.updateServerState(server.getIdentityNode().getId(),
                                 MetadataStore.ServerState.NORMAL_STATE);

        state = server.getMetadataStore().getServerState();
        assertEquals("State should be changed correctly to rebalancing state",
                     MetadataStore.ServerState.NORMAL_STATE,
                     state);

        // lets revert back to REBALANCING STATE AND CHECK
        client.updateServerState(server.getIdentityNode().getId(),
                                 MetadataStore.ServerState.REBALANCING_DONOR_STATE);

        state = server.getMetadataStore().getServerState();

        assertEquals("State should be changed correctly to rebalancing state",
                     MetadataStore.ServerState.REBALANCING_DONOR_STATE,
                     state);

        client.updateServerState(server.getIdentityNode().getId(),
                                 MetadataStore.ServerState.NORMAL_STATE);

        state = server.getMetadataStore().getServerState();
        assertEquals("State should be changed correctly to rebalancing state",
                     MetadataStore.ServerState.NORMAL_STATE,
                     state);
    }

    public void testDeletePartitionEntries() {
    Store<ByteArray, byte[]> store = server.getStoreRepository().getStorageEngine(storeName);
        assertNotSame("Store '" + storeName + "' should not be null", null, store);

        Set<Pair<ByteArray, Versioned<byte[]>>> entrySet = createEntries();
        for(Pair<ByteArray, Versioned<byte[]>> entry: entrySet) {
            store.put(entry.getFirst(), entry.getSecond());
        }

        getAdminClient().doDeletePartitionEntries(0, storeName, Arrays.asList(0, 2), null);

        RoutingStrategy routingStrategy = server.getMetadataStore().getRoutingStrategy(storeName);
        for(Pair<ByteArray, Versioned<byte[]>> entry: entrySet) {
            if(routingStrategy.getPartitionList(entry.getFirst().get()).contains(0)
               || routingStrategy.getPartitionList(entry.getFirst().get()).contains(2)) {
                assertEquals("store should be missing all 0,2 entries",
                             0,
                             store.get(entry.getFirst()).size());
            } else {
                assertEquals("store should have all 1,3 entries", 1, store.get(entry.getFirst())
                                                                          .size());
                assertEquals("entry should match",
                             entry.getSecond().getValue(),
                             store.get(entry.getFirst()).get(0).getValue());
            }
        }
        
    }
    
    public void testRedirectGet() {
        // user store should be present
        Store<ByteArray, byte[]> store = server.getStoreRepository().getStorageEngine("users");

        assertNotSame("Store 'users' should not be null", null, store);

        ByteArray key = new ByteArray(ByteUtils.getBytes("test_member_1", "UTF-8"));
        byte[] value = "test-value-1".getBytes();

        store.put(key, new Versioned<byte[]>(value));

        // check direct get
        assertEquals("Direct Get should succeed", new String(value), new String(store.get(key)
                                                                                     .get(0)
                                                                                     .getValue()));

        // update server stores info
        AdminClientRequestFormat client = getAdminClient();

        assertEquals("ForcedGet should match put value",
                     new String(value),
                     new String(client.redirectGet(server.getIdentityNode().getId(), "users", key)
                                      .get(0)
                                      .getValue()));
    }

    private Set<Pair<ByteArray, Versioned<byte[]>>> createEntries() {
        Set<Pair<ByteArray, Versioned<byte[]>>> entrySet = new HashSet<Pair<ByteArray, Versioned<byte[]>>>();

        for(int i = 0; i <= 1000; i++) {
            ByteArray key = new ByteArray(ByteUtils.getBytes("" + i, "UTF-8"));
            Versioned<byte[]> value = new Versioned<byte[]>(ByteUtils.getBytes("value-" + i,
                                                                               "UTF-8"));
            entrySet.add(new Pair<ByteArray, Versioned<byte[]>>(key, value));
        }

        return entrySet;
    }

    
}
