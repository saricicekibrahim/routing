import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.MultiLineString;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.*;

public class WriteOsmXml {
    static Map<String, String> wayTypeMap = new HashMap<>();
    static Map<String, String> oneWayMap = new HashMap<>();
    static String wayTypeColumn;
    static String nameColumn;
    static String laneColumn;
    static String oneWayColumn;

    static List<Node> nodes = new ArrayList<>();
    static List<Way> ways = new ArrayList<>();

    static int indexn = 1;
    static int indexw = 1;


    public static void main(String[] args) throws IOException, ParserConfigurationException, TransformerException {
        getWayCustomTypesFromJson();

        String folderPath = "testRoutingData"; // Replace with your actual folder path

        // Create a filter to accept only files with the .shp extension
        FilenameFilter filter = (dir, name) -> name.endsWith(".shp");

        // Get all files matching the filter
        File[] shpFiles = new File(folderPath).listFiles(filter);

        // Check if any files were found
        if (shpFiles.length == 0) {
            System.out.println("No .shp files found in the folder.");
        } else {
            // Print the names of all .shp files

            for (File file : shpFiles) {
                System.out.println("Processing file: " + file.getName());
                Map<String, Object> map = new HashMap<>();
                map.put("url", file.toURI().toURL());

                DataStore dataStore = DataStoreFinder.getDataStore(map);
                String typeName = dataStore.getTypeNames()[0];

                FeatureSource<SimpleFeatureType, SimpleFeature> source =
                        dataStore.getFeatureSource(typeName);
                //Filter filter = Filter.INCLUDE; // ECQL.toFilter("BBOX(THE_GEOM, 10,20,30,40)")

                FeatureCollection<SimpleFeatureType, SimpleFeature> collection = source.getFeatures();

                createNodesWays(collection);
            }
        }

        writeOsmXml(nodes, ways);
    }

    private static void getWayCustomTypesFromJson() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        File jsonFile = new File("sampleRouteTarget.json");

        // Read JSON file into JsonNode
        JsonNode jsonNode = objectMapper.readTree(jsonFile);

        // Access JSON data using jsonNode
        ArrayNode wayTypes = (ArrayNode) jsonNode.get("wayTypes");
        ArrayNode oneWayTypes = (ArrayNode) jsonNode.get("oneWayTypes");
        wayTypeColumn = jsonNode.get("wayTypeColumn").asText();
        nameColumn = jsonNode.get("nameColumn").asText();
        laneColumn = jsonNode.get("laneColumn").asText();
        oneWayColumn = jsonNode.get("oneWayColumn").asText();

        for (JsonNode wayType : wayTypes) {
            wayTypeMap.put(wayType.get("orjType").asText(), wayType.get("osmType").asText());
        }

        for (JsonNode oneWayType : oneWayTypes) {
            oneWayMap.put(oneWayType.get("orjType").asText(), oneWayType.get("osmType").asText());
        }
    }

    private static void createNodesWays(FeatureCollection<SimpleFeatureType, SimpleFeature> collection) throws ParserConfigurationException, TransformerException {
        Set<Coordinate> nodesUnique = new HashSet<>();
        Map<Coordinate, Node> coordinateNodeMap = new HashMap<>();

        try (FeatureIterator<SimpleFeature> features = collection.features()) {
            while (features.hasNext()) {
                SimpleFeature feature = features.next();
                MultiLineString line = (MultiLineString) feature.getDefaultGeometry();
                Coordinate[] coordinates = line.getCoordinates();
                List<Integer> lineNodes = new ArrayList<>();
                Way way = new Way();

                for (Coordinate coordinate : coordinates) {
                    Node node = nodesUnique.add(coordinate) ? createNewNode(coordinate, indexn++, nodes, coordinateNodeMap) : coordinateNodeMap.get(coordinate);
                    lineNodes.add(node.getId());
                }

                way.setId(indexw++);
                way.setNodes(lineNodes);
                way.setHighway(setWayType(String.valueOf(feature.getAttribute(wayTypeColumn))));
                way.setName((String) feature.getAttribute(nameColumn));
                way.setOneway(setOneWay(feature.getAttribute(oneWayColumn)));
                way.setLanes((long) (feature.getAttribute(laneColumn) != null ? ((Long) feature.getAttribute(laneColumn) == 0 ? 1 : ((Long) feature.getAttribute(laneColumn)).intValue()) : 1));
                //way.surface = "";
                //way.maxSpeed = 0;
                ways.add(way);
            }
        }
    }

    private static String setWayType(String customRoadType) {
        return Arrays.stream(OsmWayTypeEnum.values())
                .filter(osmwaytypeenum -> osmwaytypeenum.getValue().equals(wayTypeMap.get(customRoadType)))
                .findFirst()
                .map(OsmWayTypeEnum::getValue)
                .orElse(OsmWayTypeEnum.UNCLASSIFIED.getValue());
    }

    private static String setOneWay(Object customOneWay) {
        if (customOneWay != null) {
            return Arrays.stream(OsmOneWayEnum.values())
                    .filter(osmonewayenum -> osmonewayenum.getValue().equals(oneWayMap.get(customOneWay.toString())))
                    .findFirst()
                    .map(OsmOneWayEnum::getValue)
                    .orElse(OsmOneWayEnum.NO.getValue());
        } else {
            return OsmOneWayEnum.NO.getValue();
        }
    }

    private static Node createNewNode(Coordinate coordinate, int index, List<Node> nodes, Map<Coordinate, Node> coordinateNodeMap) {
        Node node = new Node();
        node.setLat(coordinate.getY());
        node.setLon(coordinate.getX());
        node.setId(index);
        nodes.add(node);
        coordinateNodeMap.put(coordinate, node);
        return node;
    }

    public static void writeOsmXml(List<Node> nodes, List<Way> ways) throws ParserConfigurationException, TransformerException {
        System.out.println("--> creating osm file started");
        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

        // Create the root element and set attributes
        Document doc = docBuilder.newDocument();
        Element osmElement = doc.createElement("osm");
        osmElement.setAttribute("version", "0.6");
        osmElement.setAttribute("generator", "isaricicek");
        doc.appendChild(osmElement);

        // Add nodes
        for (Node node : nodes) {
            Element nodeElement = doc.createElement("node");
            nodeElement.setAttribute("visible", "true");
            nodeElement.setAttribute("id", String.valueOf(node.getId()));
            nodeElement.setAttribute("lat", node.getLat().toString().replace(",", "."));
            nodeElement.setAttribute("lon", node.getLon().toString().replace(",", "."));
            osmElement.appendChild(nodeElement);
            osmElement.appendChild(doc.createTextNode("\n"));
        }

        // Add ways
        for (Way way : ways) {
            Element wayElement = doc.createElement("way");
            wayElement.setAttribute("visible", "true");
            wayElement.setAttribute("id", String.valueOf(way.getId()));

            for (Integer id : way.getNodes()) {
                Element ndElement = doc.createElement("nd");
                ndElement.setAttribute("ref", id.toString());
                wayElement.appendChild(ndElement);
            }

            // Add tags
            addTag(doc, wayElement, "highway", way.getHighway());
            addTag(doc, wayElement, "oneway", way.getOneway());
            addTag(doc, wayElement, "name", way.getName());
            addTag(doc, wayElement, "lanes", String.valueOf(way.getLanes()));

            // Add other tags...

            osmElement.appendChild(wayElement);
            osmElement.appendChild(doc.createTextNode("\n"));
        }

        // Transform the Document to XML and write to file
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(new File("osmFileToCreateGraph.osm"));
        transformer.transform(source, result);
        System.out.println("--> osmFileToCreateGraph.osm file created. Now you can create graph from file.");
    }

    private static void addTag(Document doc, Element parentElement, String key, String value) {
        Element tagElement = doc.createElement("tag");
        tagElement.setAttribute("k", key);
        tagElement.setAttribute("v", value);
        parentElement.appendChild(tagElement);
    }
}
