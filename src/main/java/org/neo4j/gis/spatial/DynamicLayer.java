/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gis.spatial;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.neo4j.gis.spatial.RTreeIndex.RecordCounter;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.gis.spatial.geotools.data.Neo4jFeatureBuilder;
import org.opengis.filter.Filter;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.feature.simple.SimpleFeature;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.filter.text.ecql.ECQL;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.GeometryFactory;

/**
 * <p>
 * The DynamicLayer class extends a Layer to be able to express itself as
 * several Layers. Each of these 'sub-layers' is defined by adding filters to
 * the original layer. The filters are configured in the LayerConfig class, on a
 * set of nodes related to the original dynamic layer node by LAYER_CONFIG
 * relationships. One key example of where this type of capability is very
 * valuable is for example when a layer contains geometries of multiple types,
 * but geotools can only express one type in each layer. Then we can use
 * DynamicLayer to expose each of the different geometry types as a different
 * layer to the consuming application (desktop or web application).
 * </p>
 * <p>
 * DynamicLayer extends EdiableLayerImpl, and is therefore editable. Not that
 * this support is dependant on the correct working of the appropriate
 * GeometryEncoder, and also does not extend to the sub-layers provided. Those
 * are read-only views.
 * </p>
 * 
 * @author craig
 * @since 1.0.0
 */
public class DynamicLayer extends EditableLayerImpl {

	private LinkedHashMap<String, Layer> layers;

	/**
	 * This class wraps a SpatialIndexReader instance, passing through all calls
	 * transparently. This is a class that should not be used as it, as it
	 * provides not additional functionality. However extending this class
	 * allows for wrapping an existing index and modifying its behaviour through
	 * overriding only specific methods. For example, override the
	 * excecuteSearch method with a modification to the search parameter.
	 * 
	 * @author craig
	 */
	public static class SpatialIndexReaderWrapper implements SpatialIndexReader {
		protected SpatialTreeIndex index;

		public SpatialIndexReaderWrapper(SpatialTreeIndex index) {
			this.index = index;
		}

		public int count() {
			return index.count();
		}

		public void executeSearch(Search search) {
			index.executeSearch(search);
		}

		public SpatialDatabaseRecord get(Long geomNodeId) {
			return index.get(geomNodeId);
		}

		public List<SpatialDatabaseRecord> get(Set<Long> geomNodeIds) {
			return index.get(geomNodeIds);
		}

		public Envelope getLayerBoundingBox() {
			return index.getLayerBoundingBox();
		}

		public boolean isEmpty() {
			return index.isEmpty();
		}

		public Iterable<Node> getAllGeometryNodes() {
	        return index.getAllGeometryNodes();
        }

	}

    public class CQLIndexReader extends SpatialIndexReaderWrapper {
        private final Filter filter;
        private final Neo4jFeatureBuilder builder;

        public CQLIndexReader(SpatialTreeIndex index, String query) throws CQLException, IOException {
            super(index);
            this.filter = ECQL.toFilter(query);
            this.builder = new Neo4jFeatureBuilder(DynamicLayer.this);
        }

        private class Counter extends RecordCounter {
            public boolean needsToVisit(Node indexNode) {
                return queryIndexNode(indexNode);
            }

            public void onIndexReference(Node geomNode) {
                if (queryLeafNode(geomNode)) {
                    super.onIndexReference(geomNode);
                }
            }
        }

        private class FilteredSearch implements Search {
            private Search delegate;
            public FilteredSearch(Search delegate) {
                this.delegate = delegate;
            }

            public List<SpatialDatabaseRecord> getResults() {
                return delegate.getResults();
            }

            public void setLayer(Layer layer) {
                delegate.setLayer(layer);
            }

            public boolean needsToVisit(Node indexNode) {
                return delegate.needsToVisit(indexNode);
            }

            public void onIndexReference(Node geomNode) {
                if (queryLeafNode(geomNode)) {
                    delegate.onIndexReference(geomNode);
                }
            }
        }

        private boolean queryIndexNode(Node indexNode) {
            return true;
        }

        private boolean queryLeafNode(Node indexNode) {
            SpatialDatabaseRecord dbRecord = 
                new SpatialDatabaseRecord(DynamicLayer.this, indexNode); 
            SimpleFeature feature = builder.buildFeature(dbRecord);
            return filter.evaluate(feature);
        }

   		public int count() {
			Counter counter = new Counter();
			index.visit(counter, index.getIndexRoot());
			return counter.getResult();
		}

		public void executeSearch(final Search search) {
			index.executeSearch(new FilteredSearch(search));
		}
    }

	// public class DynamicIndexReader extends SpatialIndexReaderWrapper {
	// 	private JSONObject query;

	// 	private class DynamicRecordCounter extends RecordCounter {
	// 		public boolean needsToVisit(Node indexNode) {
	// 			return queryIndexNode(indexNode);
	// 		}

	// 		public void onIndexReference(Node geomNode) {
	// 			if (queryLeafNode(geomNode)) {
	// 				super.onIndexReference(geomNode);
	// 			}
	// 		}
	// 	}

	// 	public DynamicIndexReader(SpatialTreeIndex index, String query) {
	// 		super(index);
	// 		this.query = (JSONObject)JSONValue.parse(query);
	// 	}

	// 	private boolean queryIndexNode(Node indexNode) {
	// 		// TODO: Support making the query on each index node for performance
	// 		return true;
	// 	}

	// 	/**
	// 	 * Supports querying the geometry node for certain characteristics
	// 	 * defined by the original JSON query string. Initially this is only the
	// 	 * existence of certain properties and values, as well as the ability to
	// 	 * step through single relationships, testing nodes properties along the
	// 	 * way. For example:
	// 	 * 
	// 	 * <pre>
	// 	 * { "properties": {"type": "geometry"},
	// 	 *   "step": {"type": "GEOM", "direction": "INCOMING"
	// 	 *     "step": {"type": "TAGS", "direction": "OUTGOING"
	// 	 *       "properties": {"highway": "residential"}
	// 	 *     }
	// 	 *   }
	// 	 * }
	// 	 * </pre>
	// 	 * 
	// 	 * This will work with OSM datasets, traversing from the geometry node
	// 	 * to the way node and then to the tags node to test if the way is a
	// 	 * residential street.
	// 	 * 
	// 	 * @param geomNode
	// 	 * @return true if the node matches the query string, or the query
	// 	 *         string is empty
	// 	 */
	// 	private boolean queryLeafNode(Node geomNode) {
	// 		// TODO: Extend support for more complex queries
	// 		JSONObject properties = (JSONObject)query.get("properties");
	// 		JSONObject step = (JSONObject)query.get("step");
	// 		return queryNodeProperties(geomNode,properties) && stepAndQuery(geomNode,step);
	// 	}
	// 	
	// 	private boolean stepAndQuery(Node source, JSONObject step) {
	// 		if (step != null) {
	// 			JSONObject properties = (JSONObject) step.get("properties");
	// 			Relationship rel = source.getSingleRelationship(DynamicRelationshipType.withName(step.get("type").toString()), Direction
	// 			        .valueOf(step.get("direction").toString()));
	// 			if (rel != null) {
	// 				Node node = rel.getOtherNode(source);
	// 				step = (JSONObject) step.get("step");
	// 				return queryNodeProperties(node, properties) && stepAndQuery(node, step);
	// 			} else {
	// 				return false;
	// 			}
	// 		} else {
	// 			return true;
	// 		}
	// 	}

	// 	private boolean queryNodeProperties(Node node, JSONObject properties) {
	// 		if (properties != null) {
	// 			if(properties.containsKey("geometry")){
	// 				System.out.println("Unexpected 'geometry' in query string");
	// 				properties.remove("geometry");
	// 			}
	// 			for (Object key : properties.keySet()) {
	// 				Object value = node.getProperty(key.toString(), null);
	// 				Object match = properties.get(key);
	// 				//TODO: Find a better way to solve minor type mismatches (Long!=Integer) than the string conversion below
	// 				if (value == null || (match != null && !value.equals(match) && !value.toString().equals(match.toString()))) {
	// 					return false;
	// 				}
	// 			}
	// 		}
	// 		return true;
	// 	}

	// 	public int count() {
	// 		DynamicRecordCounter counter = new DynamicRecordCounter();
	// 		index.visit(counter, index.getIndexRoot());
	// 		return counter.getResult();
	// 	}

	// 	public void executeSearch(final Search search) {
	// 		index.executeSearch(new Search() {

	// 			public List<SpatialDatabaseRecord> getResults() {
	// 				return search.getResults();
	// 			}

	// 			public void setLayer(Layer layer) {
	// 				search.setLayer(layer);
	// 			}

	// 			public boolean needsToVisit(Node indexNode) {
	// 				return search.needsToVisit(indexNode);
	// 			}

	// 			public void onIndexReference(Node geomNode) {
	// 				if (queryLeafNode(geomNode)) {
	// 					search.onIndexReference(geomNode);
	// 				}
	// 			}
	// 		});
	// 	}

	// }

	/**
	 * <p>
	 * The LayerConfig class exposes the rules encoded in the layer config node
	 * for a custom layer expressed from a single layer node. This configuration
	 * can be a set of properties to match in order to be considered part of the
	 * layer, or it can be a more complex custom traverser.
	 * </p>
	 * 
	 * @author craig
	 * @since 1.0.0
	 */
	public class LayerConfig implements Layer {
		private Node configNode;

		/**
		 * Construct the layer config instance on existing config information in
		 * the database.
		 * 
		 * @param configNode
		 */
		public LayerConfig(Node configNode) {
			this.configNode = configNode;
		}

		/**
		 * Construct a new layer config by building the database structure to
		 * support the necessary configuration
		 * 
		 * @param name
		 *            of the new dynamic layer
		 * @param geometryType
		 *            the geometry this layer supports
		 * @param query
		 *            formated query string for this dynamic layer
		 */
		public LayerConfig(String name, int geometryType, String query) {
			GraphDatabaseService database = DynamicLayer.this.getSpatialDatabase().getDatabase();
			Transaction tx = database.beginTx();
			try {
				Node node = database.createNode();
				node.setProperty(PROP_LAYER, name);
				node.setProperty(PROP_TYPE, geometryType);
				node.setProperty(PROP_QUERY, query);
				DynamicLayer.this.getLayerNode().createRelationshipTo(node, SpatialRelationshipTypes.LAYER_CONFIG);
				tx.success();
				configNode = node;
			} finally {
				tx.finish();
			}
		}

		public String getName() {
			return (String) configNode.getProperty(PROP_LAYER);
		}

		public String getQuery() {
			return (String) configNode.getProperty(PROP_QUERY);
		}

		public SpatialDatabaseRecord add(Node geomNode) {
			throw new SpatialDatabaseException("Cannot add nodes to dynamic layers, add the node to the base layer instead");
		}

		public void delete(Listener monitor) {
			throw new SpatialDatabaseException("Cannot delete dynamic layers, delete the base layer instead");
		}

		public CoordinateReferenceSystem getCoordinateReferenceSystem() {
			return DynamicLayer.this.getCoordinateReferenceSystem();
		}

		public SpatialDataset getDataset() {
			return DynamicLayer.this.getDataset();
		}

		public String[] getExtraPropertyNames() {
			return DynamicLayer.this.getExtraPropertyNames();
		}

		public GeometryEncoder getGeometryEncoder() {
			return DynamicLayer.this.getGeometryEncoder();
		}

		public GeometryFactory getGeometryFactory() {
			return DynamicLayer.this.getGeometryFactory();
		}

		public Integer getGeometryType() {
			return (Integer) configNode.getProperty(PROP_TYPE);
		}

		public SpatialIndexReader getIndex() {
			if (index instanceof SpatialTreeIndex) {
                try {
                    return new CQLIndexReader((SpatialTreeIndex) index, getQuery());
                } catch (IOException e) {
                    throw new SpatialDatabaseException("Error while creating DynamicLayer", e);
                } catch (CQLException e) {
                    throw new SpatialDatabaseException("Error while creating DynamicLayer", e);
                }
			} else {
				throw new SpatialDatabaseException("Cannot make a DynamicLayer from a non-SpatialTreeIndex Layer");
			}
		}

		public Node getLayerNode() {
			// TODO: Make sure that the mismatch between the name on the dynamic
			// layer node and the dynamic layer translates into the correct
			// object being returned
			return DynamicLayer.this.getLayerNode();
		}

		public SpatialDatabaseService getSpatialDatabase() {
			return DynamicLayer.this.getSpatialDatabase();
		}

		public void initialize(SpatialDatabaseService spatialDatabase, String name, Node layerNode) {
			throw new SpatialDatabaseException("Cannot initialize the layer config, initialize only the dynamic layer node");
		}

		public Object getStyle() {
			Object style = DynamicLayer.this.getStyle();
			if (style != null && style instanceof File) {
				File parent = ((File) style).getParentFile();
				File newStyle = new File(parent, getName() + ".sld");
				if (newStyle.canRead()) {
					style = newStyle;
				}
			}
			return style;
		}
		
		public Layer getParent() {
			return DynamicLayer.this;
		}
		
		public String toString() {
			return getName();
		}
	}

	private synchronized Map<String, Layer> getLayerMap() {
		if (layers == null) {
			layers = new LinkedHashMap<String, Layer>();
			layers.put(getName(), this);
			for (Relationship rel : layerNode.getRelationships(SpatialRelationshipTypes.LAYER_CONFIG, Direction.OUTGOING)) {
				LayerConfig config = new LayerConfig(rel.getEndNode());
				layers.put(config.getName(), config);
			}
		}
		return layers;
	}
	
	protected boolean removeLayerConfig(String name) {
		Layer layer = getLayerMap().get(name);
		if (layer != null && layer instanceof LayerConfig) {
			synchronized (this) {
				LayerConfig config = (LayerConfig)layer;
				layers = null; // force recalculation of layers cache
				Transaction tx = config.configNode.getGraphDatabase().beginTx();
				try {
					config.configNode.getSingleRelationship(SpatialRelationshipTypes.LAYER_CONFIG, Direction.INCOMING).delete();
					config.configNode.delete();
					tx.success();
				} finally {
					tx.finish();
				}
				return true;
			}
		} else if (layer == null) {
			System.out.println("Dynamic layer not found: " + name);
			return false;
		} else {
			System.out.println("Layer is not dynamic and cannot be deleted: " + name);
			return false;
		}
	}

	protected LayerConfig addLayerConfig(String name, int type, String query) {
        try {
            ECQL.toFilter(query);
        } catch (CQLException e) {
            throw new SpatialDatabaseException("tried to create layer with invalid query (" + query + ")", e);
        }

		Layer layer = getLayerMap().get(name);
		if (layer != null) {
			if (layer instanceof LayerConfig) {
				LayerConfig config = (LayerConfig) layer;
				if (config.getGeometryType() != type || !config.getQuery().equals(query)) {
					System.err.println("Existing LayerConfig with different geometry type or query: " + config);
					return null;
				} else {
					return config;
				}
			} else {
				System.err.println("Existing Layer has same name as requested LayerConfig: " + layer);
				return null;
			}
		} else synchronized (this) {
			LayerConfig config = new LayerConfig(name, type, query);
			layers = null;	// force recalculation of layers cache
			return config;
		}
	}

	public List<String> getLayerNames() {
		return new ArrayList<String>(getLayerMap().keySet());
	}

	public List<Layer> getLayers() {
		return new ArrayList<Layer>(getLayerMap().values());
	}

	public Layer getLayer(String name) {
		return getLayerMap().get(name);
	}
}
