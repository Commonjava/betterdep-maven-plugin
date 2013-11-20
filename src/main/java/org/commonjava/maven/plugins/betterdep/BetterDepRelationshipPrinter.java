package org.commonjava.maven.plugins.betterdep;

import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.commonjava.maven.atlas.graph.rel.DependencyRelationship;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.graph.rel.RelationshipType;
import org.commonjava.maven.atlas.graph.traverse.print.StructureRelationshipPrinter;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;

public class BetterDepRelationshipPrinter
    implements StructureRelationshipPrinter
{

    private final Set<ProjectVersionRef> missing;

    public BetterDepRelationshipPrinter( final Set<ProjectVersionRef> missing )
    {
        this.missing = missing;
    }

    @Override
    public void print( final ProjectRelationship<?> relationship, final ProjectVersionRef selectedTarget, final StringBuilder builder,
                       final Map<String, Set<ProjectVersionRef>> labels, final int depth, final String indent )
    {
        indent( builder, depth, indent );

        final RelationshipType type = relationship.getType();

        final ProjectVersionRef originalTarget = relationship.getTarget()
                                                             .asProjectVersionRef();

        ProjectVersionRef target = null;
        ArtifactRef targetArtifact = relationship.getTargetArtifact();

        if ( selectedTarget == null )
        {
            target = originalTarget;
        }
        else
        {
            target = selectedTarget;
            targetArtifact = selectedTarget.asArtifactRef( targetArtifact.getTypeAndClassifier() );
        }

        builder.append( targetArtifact );

        final Set<String> localLabels = new HashSet<String>();

        if ( type == RelationshipType.DEPENDENCY )
        {
            final DependencyRelationship dr = (DependencyRelationship) relationship;
            builder.append( ':' )
                   .append( dr.getScope()
                              .name() );

            if ( dr.getTargetArtifact()
                   .isOptional() )
            {
                localLabels.add( "OPTIONAL" );
            }

            //            builder.append( " [idx: " )
            //                   .append( relationship.getIndex() )
            //                   .append( ']' );
        }
        else
        {
            localLabels.add( type.name() );
        }

        boolean hasLabel = false;
        if ( !localLabels.isEmpty() )
        {
            hasLabel = true;
            builder.append( " (" );

            boolean first = true;
            for ( final String label : localLabels )
            {
                if ( first )
                {
                    first = false;
                }
                else
                {
                    builder.append( ", " );
                }

                builder.append( label );
            }
        }

        for ( final Entry<String, Set<ProjectVersionRef>> entry : labels.entrySet() )
        {
            final String label = entry.getKey();
            final Set<ProjectVersionRef> refs = entry.getValue();

            if ( refs.contains( target ) )
            {
                if ( !hasLabel )
                {
                    hasLabel = true;
                    builder.append( " (" );
                }
                else
                {
                    builder.append( ", " );
                }

                builder.append( label );
            }

        }

        if ( hasLabel )
        {
            builder.append( ')' );
        }

        if ( !target.equals( originalTarget ) )
        {
            builder.append( " [was: " )
                   .append( originalTarget )
                   .append( "]" );
        }

        if ( missing.contains( target ) )
        {
            builder.append( '\n' );
            indent( builder, depth + 1, indent );
            builder.append( "???" );
        }
    }

    private void indent( final StringBuilder builder, final int depth, final String indent )
    {
        for ( int i = 0; i < depth; i++ )
        {
            builder.append( indent );
        }
    }
}
