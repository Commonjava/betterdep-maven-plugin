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

public class BetterDepFilter
    implements ProjectRelationshipFilter
{

    private final DependencyScope scope;

    private final Set<ProjectRef> excludes;

    public BetterDepFilter( final DependencyScope scope )
    {
        this.scope = scope;
        this.excludes = null;
    }

    public BetterDepFilter( final DependencyScope scope, final Set<ProjectRef> inheritedExcludes, final DependencyRelationship parentRel )
    {
        this.scope = ScopeTransitivity.maven.getChildFor( scope );
        if ( ( inheritedExcludes != null && !inheritedExcludes.isEmpty() )
            || ( parentRel != null && parentRel.getExcludes() != null && !parentRel.getExcludes()
                                                                                   .isEmpty() ) )
        {
            excludes = new HashSet<ProjectRef>();
            if ( inheritedExcludes != null )
            {
                excludes.addAll( inheritedExcludes );
            }

            if ( parentRel != null && parentRel.getExcludes() != null )
            {
                for ( final ProjectRef pr : parentRel.getExcludes() )
                {
                    excludes.add( pr.asProjectRef() );
                }
            }
        }
        else
        {
            excludes = null;
        }
    }

    @SuppressWarnings( "incomplete-switch" )
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
        return new BetterDepFilter( scope, excludes, ( ( parent instanceof DependencyRelationship ) ? (DependencyRelationship) parent : null ) );
    }

    @Override
    public void render( final StringBuilder sb )
    {
        sb.append( "PARENTS || BOMS || DEPENDENCIES[scope: " )
          .append( scope.realName() )
          .append( ']' );
    }

}
