package org.commonjava.maven.plugins.betterdep;

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
