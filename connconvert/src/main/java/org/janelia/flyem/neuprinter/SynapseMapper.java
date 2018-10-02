package org.janelia.flyem.neuprinter;

import com.google.common.base.Stopwatch;
import org.janelia.flyem.neuprinter.model.BodyWithSynapses;
import org.janelia.flyem.neuprinter.model.ConnectionSetMap;
import org.janelia.flyem.neuprinter.model.Synapse;
import org.janelia.flyem.neuprinter.model.SynapseLocationToBodyIdMap;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Coordinates parsing and mapping JSON formatted connected body synapse data into an in-memory object model.
 */

public class SynapseMapper {

    private final SynapseLocationToBodyIdMap synapseLocationToBodyIdMap;
    private final HashMap<String, Set<String>> preToPostMap = new HashMap<>();
    private final ConnectionSetMap connectionSetMap = new ConnectionSetMap();

    /**
     * Class constructor.
     */
    public SynapseMapper() {
        this.synapseLocationToBodyIdMap = new SynapseLocationToBodyIdMap();
    }

    /**
     * @return map of synaptic density locations to bodyIds
     */
    public SynapseLocationToBodyIdMap getSynapseLocationToBodyIdMap() {
        return this.synapseLocationToBodyIdMap;
    }

    /**
     * @return map of presynaptic density locations to postsynaptic density locations
     */
    public HashMap<String, Set<String>> getPreToPostMap() {
        return this.preToPostMap;
    }

    /**
     * @return map of ConnectionSet nodes to be added to database
     */
    public ConnectionSetMap getConnectionSetMap() {
        return this.connectionSetMap;
    }

    @Override
    public String toString() {
        return "{ numberOfMappedLocations: " + this.synapseLocationToBodyIdMap.size() + " }";
    }

    /**
     * Loads bodies from the specified JSON file and then maps their relational data.
     *
     * @param filepath to synapse JSON file
     * @param dataset
     * @return list of loaded bodies with mapped data.
     */
    public List<BodyWithSynapses> loadAndMapBodies(final String filepath, final String dataset) {

        Stopwatch timer = Stopwatch.createStarted();

        try (BufferedReader reader = new BufferedReader(new FileReader(filepath))) {
            final List<BodyWithSynapses> bodyList = BodyWithSynapses.fromJson(reader);
            mapBodies(bodyList, dataset);
            timer.reset();
            return bodyList;

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
            return new ArrayList<>();
        }

    }

    /**
     * Maps relational data for all bodies in the specified list.
     * This method has been extracted as an independent method to facilitate testing.
     *
     * @param bodyList list of BodyWithSynapses
     */
    private void mapBodies(final List<BodyWithSynapses> bodyList, String dataset) {

        for (final BodyWithSynapses body : bodyList) {
            body.addSynapsesToBodyIdMapAndSetSynapseCounts("post", synapseLocationToBodyIdMap);
        }

        for (final BodyWithSynapses body : bodyList) {
            body.setConnectsTo(this.synapseLocationToBodyIdMap);
            body.addSynapsesToPreToPostMap(this.preToPostMap);

            long presynapticBodyId = body.getBodyId();
            for (final Synapse synapse : body.getSynapseSet()) {
                if (synapse.getType().equals("pre")) {
                    final String presynapticLocationString = synapse.getLocationString();
                    final Set<String> connectionLocationStrings = synapse.getConnectionLocationStrings();
                    for (final String postsynapticLocationString : connectionLocationStrings) {
                        //deal with problematic synapses from mb6 dataset
                        if (!(isMb6ProblematicSynapse(postsynapticLocationString)) || !(dataset.equals("mb6v2") || dataset.equals("mb6"))) {
                            long postsynapticBodyId = this.synapseLocationToBodyIdMap.getBodyId(postsynapticLocationString);
                            this.connectionSetMap.addConnection(presynapticBodyId, postsynapticBodyId, presynapticLocationString, postsynapticLocationString);
                        }
                    }
                }
            }
        }

    }

    private boolean isMb6ProblematicSynapse(String locationString) {
        return locationString.equals("3936:4764:9333") || locationString.equals("4042:5135:9887");
    }

    private static final Logger LOG = Logger.getLogger("SynapseMapper.class");

}
