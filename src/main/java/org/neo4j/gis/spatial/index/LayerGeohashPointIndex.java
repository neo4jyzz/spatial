/*
 * Copyright (c) 2010-2013 "Neo Technology,"
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
package org.neo4j.gis.spatial.index;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;
import org.apache.lucene.spatial.util.GeoHashUtils;
import org.neo4j.gis.spatial.Layer;
import org.neo4j.gis.spatial.filter.SearchRecords;
import org.neo4j.gis.spatial.rtree.Envelope;
import org.neo4j.gis.spatial.rtree.EnvelopeDecoder;
import org.neo4j.gis.spatial.rtree.Listener;
import org.neo4j.gis.spatial.rtree.TreeMonitor;
import org.neo4j.gis.spatial.rtree.filter.AbstractSearchEnvelopeIntersection;
import org.neo4j.gis.spatial.rtree.filter.SearchFilter;
import org.neo4j.gis.spatial.rtree.filter.SearchResults;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.index.Index;
import org.neo4j.helpers.collection.Iterables;

import java.util.List;
import java.util.Map;

public class LayerGeohashPointIndex implements LayerIndexReader, SpatialIndexWriter {

    private Layer layer;
    private Index<Node> index;
    private GraphDatabaseService graph;
    private static final String GEOHASH_KEY = "geohash";

    @Override
    public void init(Layer layer) {
        this.layer = layer;
        String indexName = "_Spatial_Geohash_Index_" + layer.getName();
        graph = layer.getSpatialDatabase().getDatabase();
        try (Transaction tx = graph.beginTx()) {
            index = graph.index().forNodes(indexName);
            tx.success();
        }
    }

    @Override
    public Layer getLayer() {
        return layer;
    }

    @Override
    public SearchRecords search(SearchFilter filter) {
        return new SearchRecords(layer, searchIndex(filter));
    }

    @Override
    public void add(Node geomNode) {
        index.add(geomNode, GEOHASH_KEY, getGeohash(geomNode));
    }

    private String getGeohash(Node geomNode) {
        //TODO: Make this code projection aware - currently it assumes lat/lon
        Geometry geom = layer.getGeometryEncoder().decodeGeometry(geomNode);
        Point point = geom.getCentroid();   // Other code is ensuring only point layers use this, but just in case we encode the centroid
        return GeoHashUtils.stringEncode(point.getX(), point.getY());
    }

    @Override
    public void add(List<Node> geomNodes) {
        for (Node node : geomNodes) {
            add(node);
        }
    }

    @Override
    public void remove(long geomNodeId, boolean deleteGeomNode, boolean throwExceptionIfNotFound) {
        try (Transaction tx = graph.beginTx()) {
            try {
                Node geomNode = graph.getNodeById(geomNodeId);
                if (geomNode != null) {
                    index.remove(geomNode);
                    if (deleteGeomNode) {
                        geomNode.delete();
                    }
                }
            } catch (NotFoundException nfe) {
                if (throwExceptionIfNotFound) {
                    throw nfe;
                }
            }
            tx.success();
        }
    }

    @Override
    public void removeAll(boolean deleteGeomNodes, Listener monitor) {
        throw new UnsupportedOperationException("Not implemented: removeAll(...)");
    }

    @Override
    public void clear(Listener monitor) {
        throw new UnsupportedOperationException("Not implemented: removeAll(...)");
    }

    @Override
    public EnvelopeDecoder getEnvelopeDecoder() {
        return layer.getGeometryEncoder();
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public int count() {
        return 0;
    }

    @Override
    public Envelope getBoundingBox() {
        return null;
    }

    @Override
    public boolean isNodeIndexed(Long nodeId) {
        return false;
    }

    @Override
    public Iterable<Node> getAllIndexedNodes() {
        return Iterables.empty();
    }

    @Override
    public SearchResults searchIndex(SearchFilter filter) {
        return new SearchResults(index.query(GEOHASH_KEY, determineGeohashPrefix(filter)));
    }

    private String greatestCommonPrefix(String a, String b) {
        int minLength = Math.min(a.length(), b.length());
        for (int i = 0; i < minLength; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return a.substring(0, i);
            }
        }
        return a.substring(0, minLength);
    }

    private String determineGeohashPrefix(SearchFilter filter) {
        if (filter instanceof AbstractSearchEnvelopeIntersection) {
            Envelope referenceEnvelope = ((AbstractSearchEnvelopeIntersection) filter).getReferenceEnvelope();
            String maxHash = GeoHashUtils.stringEncode(referenceEnvelope.getMaxX(), referenceEnvelope.getMaxY());
            String minHash = GeoHashUtils.stringEncode(referenceEnvelope.getMinX(), referenceEnvelope.getMinY());
            return greatestCommonPrefix(minHash, maxHash) + "*";
        } else {
            throw new UnsupportedOperationException("Geohash Index only supports searches based on AbstractSearchEnvelopeIntersection, not " + filter.getClass().getCanonicalName());
        }
    }

    @Override
    public void addMonitor(TreeMonitor monitor) {

    }

    @Override
    public void configure(Map<String, Object> config) {

    }

}
