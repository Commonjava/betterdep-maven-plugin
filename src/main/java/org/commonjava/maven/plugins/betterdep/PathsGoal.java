package org.commonjava.maven.plugins.betterdep;

import static org.commonjava.maven.atlas.ident.util.IdentityUtils.projectVersion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.commonjava.maven.atlas.graph.model.EProjectWeb;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.graph.rel.RelationshipPathComparator;
import org.commonjava.maven.atlas.graph.spi.GraphDriverException;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.cartographer.data.CartoDataException;
import org.commonjava.maven.plugins.betterdep.impl.PathsTraversal;

@Mojo( name = "paths", requiresProject = false, aggregator = true, threadSafe = true )
public class PathsGoal
    extends AbstractDepgraphGoal
{

    private static final String INDENT = "  ";

    @Parameter( property = "to", required = true )
    private String toProjects;

    private Set<ProjectVersionRef> toGavs;

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        final String[] rawGavs = toProjects.split( "\\s*,\\s*" );
        toGavs = new HashSet<ProjectVersionRef>( rawGavs.length );
        for ( final String rawGav : rawGavs )
        {
            toGavs.add( projectVersion( rawGav ) );
        }

        initDepgraph( true );
        resolveDepgraph();

        try
        {
            final EProjectWeb web = carto.getDatabase()
                                         .getProjectWeb( roots.toArray( new ProjectVersionRef[roots.size()] ) );

            final PathsTraversal paths = new PathsTraversal( scope, toGavs );
            for ( final ProjectVersionRef root : roots )
            {
                try
                {
                    web.traverse( root, paths );
                }
                catch ( final GraphDriverException e )
                {
                    throw new MojoExecutionException( "Failed to traverse '" + root + "' looking for paths to: " + toGavs + ". Reason: "
                        + e.getMessage(), e );
                }
            }

            final List<List<ProjectRelationship<?>>> discoveredPaths = new ArrayList<List<ProjectRelationship<?>>>( paths.getDiscoveredPaths() );
            final StringBuilder result = new StringBuilder();

            if ( discoveredPaths.isEmpty() )
            {
                result.append( "\n\nNo paths found!\n\n" );
            }
            else
            {
                Collections.sort( discoveredPaths, new RelationshipPathComparator() );
                result.append( "Found " )
                      .append( discoveredPaths.size() )
                      .append( " paths:\n\n" );

                for ( final List<ProjectRelationship<?>> path : discoveredPaths )
                {
                    printPath( result, path );
                }
                result.append( "\n\n" );
            }

            getLog().info( result );
        }
        catch ( final CartoDataException e )
        {
            throw new MojoExecutionException( "Failed to retrieve depgraph for roots: " + roots + ". Reason: " + e.getMessage(), e );
        }
    }

    private void printPath( final StringBuilder result, final List<ProjectRelationship<?>> path )
    {
        result.append( path.get( 0 )
                           .getDeclaring() )
              .append( "\n" );

        int indent = 1;
        for ( final ProjectRelationship<?> rel : path )
        {
            for ( int i = 0; i < indent; i++ )
            {
                result.append( INDENT );
            }

            result.append( rel.getTargetArtifact() )
                  .append( "\n" );

            indent++;
        }
    }

}
