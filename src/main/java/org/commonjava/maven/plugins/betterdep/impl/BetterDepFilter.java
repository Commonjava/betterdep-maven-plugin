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

import java.util.HashSet;
import java.util.Set;

import org.commonjava.maven.atlas.graph.filter.ProjectRelationshipFilter;
import org.commonjava.maven.atlas.graph.rel.DependencyRelationship;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.ident.DependencyScope;
import org.commonjava.maven.atlas.ident.ScopeTransitivity;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;

/**
 * Basic scope-oriented dependency graph filter that includes dependencies of 
 * the given scope, associated parent POMs, and any BOMs mentioned by either of
 * these.
 * 
 * Standard Maven scope implications are used for descendant filtering, where
 * test-scope in a direct dependency translates to runtime scope for transitive
 * dependencies, etc.
 * 
 * @author jdcasey
 */
public class BetterDepFilter
    implements ProjectRelationshipFilter
{

    private final DependencyScope scope;

    private final Set<ProjectRef> excludes;

    public BetterDepFilter( final DependencyScope scope )
    {
        this.scope = scope == null ? DependencyScope.runtime : scope;
        this.excludes = null;
    }

    public BetterDepFilter( final DependencyScope scope, final Set<ProjectRef> excludes )
    {
        this.scope = scope;
        this.excludes = excludes;
    }

    @Override
    public boolean accept( final ProjectRelationship<?> rel )
    {
        // ACCEPT all BOMs, parents.
        switch ( rel.getType() )
        {
            case PARENT:
            {
                return true;
            }
            case DEPENDENCY:
            {
                if ( scope == null )
                {
                    return false;
                }

                if ( excludes != null && excludes.contains( rel.getTarget()
                                                               .asProjectRef() ) )
                {
                    return false;
                }

                if ( isBOM( rel ) )
                {
                    return true;
                }
                else if ( !rel.isManaged() && scope.implies( ( (DependencyRelationship) rel ).getScope() ) )
                {
                    return true;
                }
            }
            default:
        }

        return false;
    }

    private boolean isBOM( final ProjectRelationship<?> rel )
    {
        if ( !rel.isManaged() )
        {
            return false;
        }

        if ( !( rel instanceof DependencyRelationship ) )
        {
            return false;
        }

        final DependencyRelationship dr = (DependencyRelationship) rel;
        return ( DependencyScope._import == dr.getScope() && "pom".equals( dr.getTargetArtifact()
                                                                             .getType() ) );
    }

    @Override
    public ProjectRelationshipFilter getChildFilter( final ProjectRelationship<?> parent )
    {
        final DependencyScope nextScope = ScopeTransitivity.maven.getChildFor( scope );
        boolean construct = nextScope != scope;

        final DependencyRelationship dr = (DependencyRelationship) parent;

        Set<ProjectRef> nextExcludes = dr.getExcludes();
        if ( nextExcludes != null && !nextExcludes.isEmpty() )
        {
            construct = true;

            final Set<ProjectRef> ex = new HashSet<ProjectRef>();

            if ( excludes != null )
            {
                ex.addAll( excludes );
            }

            for ( final ProjectRef pr : dr.getExcludes() )
            {
                ex.add( pr.asProjectRef() );
            }

            nextExcludes = ex;
        }

        return construct ? new BetterDepFilter( nextScope, nextExcludes ) : this;
    }

    @Override
    public void render( final StringBuilder sb )
    {
        sb.append( "PARENTS || BOMS || DEPENDENCIES[scope: " )
          .append( scope.realName() )
          .append( ']' );
    }

}
