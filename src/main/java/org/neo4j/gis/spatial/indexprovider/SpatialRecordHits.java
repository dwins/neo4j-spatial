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
package org.neo4j.gis.spatial.indexprovider;

import java.util.List;

import org.neo4j.gis.spatial.SpatialDatabaseRecord;
import org.neo4j.graphdb.Node;
import org.neo4j.index.impl.lucene.AbstractIndexHits;

public class SpatialRecordHits extends AbstractIndexHits<Node>
{
    private final int size;
    private final List<SpatialDatabaseRecord> hits;
    private int index;
    
    public SpatialRecordHits( List<SpatialDatabaseRecord> hits )
    {
        this.size = hits.size();
        this.hits = hits;
    }

    @Override
    protected Node fetchNextOrNull()
    {
        int i = index++;
        return i < size() ? hits.get( i ).getGeomNode() : null;
    }
    
    public int size()
    {
        return this.size;
    }

    public float currentScore()
    {
        return 0;
    }
}

