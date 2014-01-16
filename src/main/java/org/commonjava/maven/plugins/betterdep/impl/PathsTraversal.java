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
import org.commonjava.maven.atlas.ident.DependencyScope;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;

public class PathsTraversal
    extends AbstractTraversal
{

    private final BetterDepFilter rootFilter;

    private final Set<ProjectVersionRef> to;

    private final Map<ProjectVersionRef, OrFilter> cache = new HashMap<ProjectVersionRef, OrFilter>();

    private final Set<List<ProjectRelationship<?>>> paths = new HashSet<List<ProjectRelationship<?>>>();

    public PathsTraversal( final DependencyScope scope, final Set<ProjectVersionRef> to )
    {
        this.rootFilter = new BetterDepFilter( scope );
        this.to = to;
    }

    @Override
    public boolean preCheck( final ProjectRelationship<?> relationship, final List<ProjectRelationship<?>> path, final int pass )
    {
        final ProjectVersionRef dRef = relationship.getDeclaring();
        ProjectRelationshipFilter filter = cache.get( dRef );
        if ( filter == null )
        {
            filter = rootFilter;
        }

        if ( filter.accept( relationship ) )
        {
            final ProjectVersionRef tRef = relationship.getTarget()
                                                       .asProjectVersionRef();

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
