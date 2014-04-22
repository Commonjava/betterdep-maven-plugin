/*******************************************************************************
 * Copyright (c) 2014 Red Hat, Inc..
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.commonjava.maven.plugins.betterdep.impl;

import java.util.HashSet;
import java.util.Set;

import org.apache.commons.codec.digest.DigestUtils;
import org.commonjava.maven.atlas.graph.filter.ProjectRelationshipFilter;
import org.commonjava.maven.atlas.graph.rel.DependencyRelationship;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.graph.rel.RelationshipType;
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

    private static final long serialVersionUID = 1L;

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
            case BOM:
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

                if ( !rel.isManaged() && scope.implies( ( (DependencyRelationship) rel ).getScope() ) )
                {
                    return true;
                }
            }
            default:
        }

        return false;
    }

    @Override
    public ProjectRelationshipFilter getChildFilter( final ProjectRelationship<?> parent )
    {
        final DependencyScope nextScope = ScopeTransitivity.maven.getChildFor( scope );
        boolean construct = nextScope != scope;

        Set<ProjectRef> nextExcludes = excludes;

        if ( parent instanceof DependencyRelationship )
        {
            final DependencyRelationship dr = (DependencyRelationship) parent;

            nextExcludes = dr.getExcludes();
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
        }

        return construct ? new BetterDepFilter( nextScope, nextExcludes ) : this;
    }

    @Override
    public String getLongId()
    {
        final StringBuilder sb = new StringBuilder( "PARENTS || BOMS || DEPENDENCIES[scope:" ).append( scope.realName() );
        if ( excludes != null && !excludes.isEmpty() )
        {
            sb.append( ", excludes:{" );
            boolean first = true;
            for ( final ProjectRef exclude : excludes )
            {
                if ( !first )
                {
                    sb.append( ',' );
                }

                first = false;
                sb.append( exclude );
            }

            sb.append( "}" );
        }

        sb.append( ']' );

        return sb.toString();
    }

    @Override
    public String getCondensedId()
    {
        return DigestUtils.shaHex( getLongId() );
    }

    @Override
    public boolean includeManagedRelationships()
    {
        return false;
    }

    @Override
    public boolean includeConcreteRelationships()
    {
        return true;
    }

    @Override
    public Set<RelationshipType> getAllowedTypes()
    {
        final Set<RelationshipType> result = new HashSet<RelationshipType>();
        result.add( RelationshipType.PARENT );
        result.add( RelationshipType.BOM );
        result.add( RelationshipType.DEPENDENCY );

        return result;
    }

}
