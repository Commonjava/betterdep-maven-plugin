/*******************************************************************************
 * Copyright (C) 2014 John Casey.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package org.commonjava.maven.plugins.betterdep.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.commonjava.maven.atlas.graph.filter.OrFilter;
import org.commonjava.maven.atlas.graph.filter.ProjectRelationshipFilter;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.graph.traverse.AbstractTraversal;
import org.commonjava.maven.atlas.graph.traverse.ProjectNetTraversal;
import org.commonjava.maven.atlas.ident.DependencyScope;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;

/**
 * Dependency graph traversal ({@link ProjectNetTraversal}) implementation used
 * to determine the paths between two GAVs or sets of GAVs in a graph.
 * 
 * @author jdcasey
 */
public class PathsTraversal
    extends AbstractTraversal
{

    private final BetterDepFilter rootFilter;

    private final Set<ProjectRef> to;

    private final Map<ProjectRef, OrFilter> cache = new HashMap<ProjectRef, OrFilter>();

    private final Set<List<ProjectRelationship<?>>> paths = new HashSet<List<ProjectRelationship<?>>>();

    public PathsTraversal( final DependencyScope scope, final Set<ProjectRef> toGas )
    {
        this.rootFilter = new BetterDepFilter( scope );
        this.to = toGas;
    }

    @Override
    public boolean preCheck( final ProjectRelationship<?> relationship, final List<ProjectRelationship<?>> path, final int pass )
    {
        final ProjectRef dRef = relationship.getDeclaring()
                                            .asProjectRef();

        ProjectRelationshipFilter filter = cache.get( dRef );
        if ( filter == null )
        {
            filter = rootFilter;
        }

        if ( filter.accept( relationship ) )
        {
            final ProjectRef tRef = relationship.getTarget()
                                                .asProjectRef();

            if ( to.contains( tRef ) )
            {
                final List<ProjectRelationship<?>> realPath = new ArrayList<ProjectRelationship<?>>( path );
                realPath.add( relationship );
                paths.add( realPath );
                return false;
            }
            else
            {
                final ProjectRelationshipFilter child = filter.getChildFilter( relationship );
                final OrFilter f = cache.get( tRef );
                if ( f == null )
                {
                    cache.put( tRef, new OrFilter( child ) );
                }
                else
                {
                    final List<ProjectRelationshipFilter> filters = new ArrayList<ProjectRelationshipFilter>( f.getFilters() );
                    filters.add( child );
                    cache.put( tRef, new OrFilter( filters ) );
                }

                return true;
            }
        }

        return false;
    }

    public Set<List<ProjectRelationship<?>>> getDiscoveredPaths()
    {
        return paths;
    }

}
