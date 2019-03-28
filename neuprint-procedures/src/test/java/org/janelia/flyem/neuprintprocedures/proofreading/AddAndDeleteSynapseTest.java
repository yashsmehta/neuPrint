package org.janelia.flyem.neuprintprocedures.proofreading;

import apoc.convert.Json;
import apoc.create.Create;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.janelia.flyem.neuprint.Neo4jImporter;
import org.janelia.flyem.neuprint.NeuPrinterMain;
import org.janelia.flyem.neuprint.SynapseMapper;
import org.janelia.flyem.neuprint.model.BodyWithSynapses;
import org.janelia.flyem.neuprint.model.Neuron;
import org.janelia.flyem.neuprint.model.SynapseCounter;
import org.janelia.flyem.neuprintloadprocedures.procedures.LoadingProcedures;
import org.janelia.flyem.neuprintprocedures.functions.NeuPrintUserFunctions;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.neo4j.driver.v1.Config;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.types.Node;
import org.neo4j.harness.junit.Neo4jRule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.neo4j.driver.v1.Values.parameters;

public class AddAndDeleteSynapseTest {

    @ClassRule
    public static Neo4jRule neo4j;
    private static Driver driver;

    static {
        neo4j = new Neo4jRule()
                .withFunction(Json.class)
                .withProcedure(Create.class)
                .withProcedure(LoadingProcedures.class)
                .withProcedure(ProofreaderProcedures.class)
                .withFunction(NeuPrintUserFunctions.class);
    }

    @BeforeClass
    public static void before() {

        List<Neuron> neuronList = NeuPrinterMain.readNeuronsJson("src/test/resources/smallNeuronList.json");
        SynapseMapper mapper = new SynapseMapper();
        List<BodyWithSynapses> bodyList = mapper.loadAndMapBodies("src/test/resources/smallBodyListWithExtraRois.json");
        HashMap<String, Set<String>> preToPost = mapper.getPreToPostMap();

        driver = GraphDatabase.driver(neo4j.boltURI(), Config.build().withoutEncryption().toConfig());

        String dataset = "test";

        Neo4jImporter neo4jImporter = new Neo4jImporter(driver);
        neo4jImporter.prepDatabase(dataset);

        neo4jImporter.addSegments(dataset, neuronList);

        neo4jImporter.addConnectsTo(dataset, bodyList);
        neo4jImporter.addSynapsesWithRois(dataset, bodyList);

        neo4jImporter.addSynapsesTo(dataset, preToPost);
        neo4jImporter.addSegmentRois(dataset, bodyList);
        neo4jImporter.addConnectionSets(dataset, bodyList, mapper.getSynapseLocationToBodyIdMap(), .2F, .8F, true);
        neo4jImporter.addSynapseSets(dataset, bodyList);
        neo4jImporter.createMetaNodeWithDataModelNode(dataset, 1.0F, .20F, .80F, true);
        neo4jImporter.addAutoNamesAndNeuronLabels(dataset, 1);

    }

    @AfterClass
    public static void after() {
        driver.close();
    }

    @Test
    public void shouldAddSynapseNodeAndUpdateMetaNode() {

        Session session = driver.session();

        // get original meta node roi info for comparison
        String origMetaNodeRoiInfoString = session.readTransaction(tx -> tx.run("MATCH (n:Meta) RETURN n.roiInfo")).single().get(0).asString();
        Gson gson = new Gson();
        Map<String, SynapseCounter> origMetaRoiInfo = gson.fromJson(origMetaNodeRoiInfoString, new TypeToken<Map<String, SynapseCounter>>() {
        }.getType());

        String synapseJson = "{ \"Type\": \"post\", \"Location\": [ 1,2,3 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";

        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", synapseJson, "dataset", "test")));

        int synapseCount = session.readTransaction(tx -> tx.run("WITH point({ x:1, y:2, z:3 }) AS loc MATCH (n:`test-Synapse`:Synapse{location:loc}) RETURN count(n)")).single().get(0).asInt();
        // a single synapse node should exist
        Assert.assertEquals(1, synapseCount);

        Node synapseNode = session.readTransaction(tx -> tx.run("WITH point({ x:1, y:2, z:3 }) AS loc MATCH (n:`test-Synapse`:Synapse{location:loc}) RETURN n")).single().get(0).asNode();
        Map<String, Object> synapseNodeAsMap = synapseNode.asMap();

        // synapse has properties and rois expected
        Assert.assertEquals("post", synapseNodeAsMap.get("type"));
        Assert.assertEquals(.88, (double) synapseNodeAsMap.get("confidence"), 0.001);
        Assert.assertTrue(synapseNodeAsMap.containsKey("test1"));
        Assert.assertTrue(synapseNodeAsMap.containsKey("test2"));

        // synapse has :PostSyn label (would be :PreSyn if a pre synapse)
        Assert.assertTrue(synapseNode.hasLabel("PostSyn"));
        Assert.assertTrue(synapseNode.hasLabel("test-PostSyn"));
        Assert.assertFalse(synapseNode.hasLabel("PreSyn"));
        Assert.assertFalse(synapseNode.hasLabel("test-PreSyn"));

        String synapseJson2 = "{ \"Type\": \"pre\", \"Location\": [ 8,50,9 ], \"Confidence\": .88, \"rois\": [ \"roiA\", \"test2\" ] }";

        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", synapseJson2, "dataset", "test")));

        // meta node total pre and post count should match database
        long preSynapseCount = session.readTransaction(tx -> tx.run("MATCH (n:PreSyn) RETURN count(n)")).single().get(0).asLong();
        long postSynapseCount = session.readTransaction(tx -> tx.run("MATCH (n:PostSyn) RETURN count(n)")).single().get(0).asLong();
        Map<String, Object> metaNodeProps = session.readTransaction(tx -> tx.run("MATCH (n:Meta) RETURN n.totalPreCount, n.totalPostCount, n.roiInfo")).single().asMap();

        Assert.assertEquals(preSynapseCount, metaNodeProps.get("n.totalPreCount"));
        Assert.assertEquals(postSynapseCount, metaNodeProps.get("n.totalPostCount"));

        Map<String, SynapseCounter> metaRoiInfo = gson.fromJson((String) metaNodeProps.get("n.roiInfo"), new TypeToken<Map<String, SynapseCounter>>() {
        }.getType());

        Assert.assertEquals(origMetaRoiInfo.get("roiA").getPre() + 1, metaRoiInfo.get("roiA").getPre());
        Assert.assertEquals(origMetaRoiInfo.get("roiA").getPost(), metaRoiInfo.get("roiA").getPost());
        if (origMetaRoiInfo.containsKey("test1")) {
            Assert.assertEquals(origMetaRoiInfo.get("test1").getPre(), metaRoiInfo.get("test1").getPre());
            Assert.assertEquals(origMetaRoiInfo.get("test1").getPost() + 1, metaRoiInfo.get("test1").getPost());
        } else {
            Assert.assertEquals(1, metaRoiInfo.get("test1").getPost());
            Assert.assertEquals(0, metaRoiInfo.get("test1").getPre());
        }
        if (origMetaRoiInfo.containsKey("test2")) {
            Assert.assertEquals(origMetaRoiInfo.get("test2").getPre() + 1, metaRoiInfo.get("test2").getPre());
            Assert.assertEquals(origMetaRoiInfo.get("test2").getPost() + 1, metaRoiInfo.get("test2").getPost());
        } else {
            Assert.assertEquals(1, metaRoiInfo.get("test2").getPre());
            Assert.assertEquals(1, metaRoiInfo.get("test2").getPost());
        }

    }

    @Test(expected = org.neo4j.driver.v1.exceptions.ClientException.class)
    public void shouldThrowExceptionIfSynapseAtLocationAlreadyExists() {

        Session session = driver.session();

        String synapseJson = "{ \"Type\": \"post\", \"Location\": [ 4301, 2276, 1535 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";

        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", synapseJson, "dataset", "test")));
    }

    @Test(expected = org.neo4j.driver.v1.exceptions.ClientException.class)
    public void shouldThrowExceptionIfSynapseDoesNotHaveType() {

        Session session = driver.session();

        String synapseJson = "{ \"Location\": [ 0,1,2 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";

        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", synapseJson, "dataset", "test")));
    }

    @Test(expected = org.neo4j.driver.v1.exceptions.ClientException.class)
    public void shouldThrowExceptionIfSynapseHasTypeOtherThanPreOrPost() {

        Session session = driver.session();

        String synapseJson = "{ \"Type\": \"other\", \"Location\": [ 5,6,7 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";

        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", synapseJson, "dataset", "test")));
    }

    @Test
    public void confidenceShouldBe0IfNotSpecified() {

        Session session = driver.session();

        String synapseJson = "{ \"Type\": \"pre\", \"Location\": [ 5,6,7], \"rois\": [ \"test1\", \"test2\" ] }";

        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", synapseJson, "dataset", "test")));

        Node synapseNode = session.readTransaction(tx -> tx.run("WITH point({ x:5, y:6, z:7 }) AS loc MATCH (n:`test-Synapse`:Synapse{location:loc}) RETURN n")).single().get(0).asNode();
        Map<String, Object> synapseNodeAsMap = synapseNode.asMap();

        Assert.assertEquals(0.0, (double) synapseNodeAsMap.get("confidence"), 0.0001);

    }

    @Test
    public void shouldAddSynapseConnection() {

        Session session = driver.session();

        String postSynapseJson = "{ \"Type\": \"post\", \"Location\": [ 88,89,90 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";
        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", postSynapseJson, "dataset", "test")));

        String preSynapseJson = "{ \"Type\": \"pre\", \"Location\": [ 98,99,100 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";
        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", preSynapseJson, "dataset", "test")));

        session.writeTransaction(tx -> tx.run("CALL proofreader.addConnectionBetweenSynapseNodes(98,99,100,88,89,90,\"test\")"));

        Map<String, Object> resultMap = session.readTransaction(tx -> tx.run("WITH point({ x:98, y:99, z:100 }) AS loc MATCH (n:`test-Synapse`:Synapse:PreSyn{location:loc})-[:SynapsesTo]->(m) RETURN m.location.x,m.location.y,m.location.z")).single().asMap();

        Assert.assertEquals(88.0D, (double) resultMap.get("m.location.x"), .0001);
        Assert.assertEquals(89.0D, (double) resultMap.get("m.location.y"), .0001);
        Assert.assertEquals(90.0D, (double) resultMap.get("m.location.z"), .0001);

    }

    @Test(expected = org.neo4j.driver.v1.exceptions.ClientException.class)
    public void shouldErrorIfPreSynapseDoesNotExist() {

        Session session = driver.session();

        String postSynapseJson = "{ \"Type\": \"post\", \"Location\": [ 87,88,89 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";
        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", postSynapseJson, "dataset", "test")));

        session.writeTransaction(tx -> tx.run("CALL proofreader.addConnectionBetweenSynapseNodes(8,9,8,87,88,89,\"test\")"));

    }

    @Test(expected = org.neo4j.driver.v1.exceptions.ClientException.class)
    public void shouldErrorIfPostSynapseDoesNotExist() {

        Session session = driver.session();
        String preSynapseJson = "{ \"Type\": \"pre\", \"Location\": [ 97,98,99 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";
        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", preSynapseJson, "dataset", "test")));

        session.writeTransaction(tx -> tx.run("CALL proofreader.addConnectionBetweenSynapseNodes(97,98,99,8,9,8,\"test\")"));

    }

    @Test(expected = org.neo4j.driver.v1.exceptions.ClientException.class)
    public void shouldErrorIfFirstLocationIsNotPre() {

        Session session = driver.session();

        String postSynapseJson = "{ \"Type\": \"post\", \"Location\": [ 10,9,8 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";
        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", postSynapseJson, "dataset", "test")));

        String postSynapseJson2 = "{ \"Type\": \"post\", \"Location\": [ 11,10,9 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";
        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", postSynapseJson2, "dataset", "test")));

        session.writeTransaction(tx -> tx.run("CALL proofreader.addConnectionBetweenSynapseNodes(10,9,8,11,10,9,\"test\")"));

    }

    @Test(expected = org.neo4j.driver.v1.exceptions.ClientException.class)
    public void shouldErrorIfSecondLocationIsNotPost() {

        Session session = driver.session();

        String preSynapseJson = "{ \"Type\": \"pre\", \"Location\": [ 5,5,5 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";
        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", preSynapseJson, "dataset", "test")));

        String preSynapseJson2 = "{ \"Type\": \"pre\", \"Location\": [ 6,6,6 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";
        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", preSynapseJson2, "dataset", "test")));

        session.writeTransaction(tx -> tx.run("CALL proofreader.addConnectionBetweenSynapseNodes(5,5,5,6,6,6,\"test\")"));

    }

    @Test(expected = org.neo4j.driver.v1.exceptions.ClientException.class)
    public void shouldErrorIfPreSynapseBelongsToBody() {

        Session session = driver.session();

        String postSynapseJson = "{ \"Type\": \"post\", \"Location\": [ 0,1,2 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";
        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", postSynapseJson, "dataset", "test")));

        session.writeTransaction(tx -> tx.run("CALL proofreader.addConnectionBetweenSynapseNodes(4300,2000,1500,0,1,2,\"test\")"));

    }

    @Test(expected = org.neo4j.driver.v1.exceptions.ClientException.class)
    public void shouldErrorIfPostSynapseBelongsToBody() {

        Session session = driver.session();

        String preSynapseJson = "{ \"Type\": \"pre\", \"Location\": [ 0,1,3 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";
        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", preSynapseJson, "dataset", "test")));

        session.writeTransaction(tx -> tx.run("CALL proofreader.addConnectionBetweenSynapseNodes(0,1,3,4301,2276,1535,\"test\")"));

    }

    @Test
    public void shouldDeleteOrphanSynapses() {

        Session session = driver.session();

        // get original meta node roi info for comparison
        String origMetaNodeRoiInfoString = session.readTransaction(tx -> tx.run("MATCH (n:Meta) RETURN n.roiInfo")).single().get(0).asString();
        Gson gson = new Gson();
        Map<String, SynapseCounter> origMetaRoiInfo = gson.fromJson(origMetaNodeRoiInfoString, new TypeToken<Map<String, SynapseCounter>>() {
        }.getType());

        String preSynapseJson = "{ \"Type\": \"pre\", \"Location\": [ 2,22,222 ], \"Confidence\": .88, \"rois\": [ \"test1\", \"test2\" ] }";
        session.writeTransaction(tx -> tx.run("CALL proofreader.addSynapse($synapseJson,$dataset)", parameters("synapseJson", preSynapseJson, "dataset", "test")));

        session.writeTransaction(tx -> tx.run("CALL proofreader.deleteSynapse($x,$y,$z,$dataset)", parameters("x", 2, "y", 22, "z", 222, "dataset", "test")));

        // meta node total pre and post count should match database
        long preSynapseCount = session.readTransaction(tx -> tx.run("MATCH (n:PreSyn) RETURN count(n)")).single().get(0).asLong();
        long postSynapseCount = session.readTransaction(tx -> tx.run("MATCH (n:PostSyn) RETURN count(n)")).single().get(0).asLong();
        Map<String, Object> metaNodeProps = session.readTransaction(tx -> tx.run("MATCH (n:Meta) RETURN n.totalPreCount, n.totalPostCount, n.roiInfo")).single().asMap();

        Assert.assertEquals(preSynapseCount, metaNodeProps.get("n.totalPreCount"));
        Assert.assertEquals(postSynapseCount, metaNodeProps.get("n.totalPostCount"));

        Map<String, SynapseCounter> metaRoiInfo = gson.fromJson((String) metaNodeProps.get("n.roiInfo"), new TypeToken<Map<String, SynapseCounter>>() {
        }.getType());

        if (origMetaRoiInfo.containsKey("test1")) {
            Assert.assertEquals(origMetaRoiInfo.get("test1").getPre(), metaRoiInfo.get("test1").getPre());
            Assert.assertEquals(origMetaRoiInfo.get("test1").getPost(), metaRoiInfo.get("test1").getPost());
        } else {
            Assert.assertFalse(metaRoiInfo.containsKey("test1"));
        }
        if (origMetaRoiInfo.containsKey("test2")) {
            Assert.assertEquals(origMetaRoiInfo.get("test2").getPre(), metaRoiInfo.get("test2").getPre());
            Assert.assertEquals(origMetaRoiInfo.get("test2").getPost() , metaRoiInfo.get("test2").getPost());
        } else {
            Assert.assertFalse(metaRoiInfo.containsKey("test2"));
        }


        // synapse is deleted
        int synapseCount = session.readTransaction(tx -> tx.run("WITH point({ x:2, y:22, z:222 }) AS loc MATCH (n:`test-Synapse`{location:loc}) RETURN count(n)")).single().get(0).asInt();
        Assert.assertEquals(0, synapseCount);


        // should continue quietly if synapse doesn't exist
        session.writeTransaction(tx -> tx.run("CALL proofreader.deleteSynapse($x,$y,$z,$dataset)", parameters("x", 3, "y", 33, "z", 333, "dataset", "test")));


    }

    @Test(expected = org.neo4j.driver.v1.exceptions.ClientException.class)
    public void shouldErrorIfSynapseNotOrphaned() {

        Session session = driver.session();

        session.writeTransaction(tx -> tx.run("CALL proofreader.deleteSynapse($x,$y,$z,$dataset)", parameters("x", 4301, "y", 2276, "z", 1535, "dataset", "test")));

    }

}
