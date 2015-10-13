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
package org.commonjava.maven.plugins.betterdep;

import static org.apache.commons.lang.StringUtils.join;
import static org.commonjava.maven.atlas.ident.util.IdentityUtils.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.commonjava.cartographer.CartoDataException;
import org.commonjava.cartographer.CartoRequestException;
import org.commonjava.cartographer.request.GraphComposition;
import org.commonjava.cartographer.request.PathsRequest;
import org.commonjava.cartographer.request.build.GraphCompositionBuilder;
import org.commonjava.cartographer.request.build.GraphDescriptionBuilder;
import org.commonjava.cartographer.request.build.PathsRequestBuilder;
import org.commonjava.cartographer.result.ProjectPath;
import org.commonjava.cartographer.result.ProjectPathsResult;
import org.commonjava.maven.atlas.graph.RelationshipGraphException;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.graph.rel.RelationshipPathComparator;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.plugins.betterdep.impl.MavenLocationExpander;
import org.commonjava.maven.plugins.betterdep.impl.PathsTraversal;

/**
 * Generates a list of the paths within the dependency graph from the "root" projects
 * to some other GAV or set of GAVs. This is a distillation of what a lot of people
 * are really after when they use the 'tree' goal.
 *
 * The key parameter here is the -Dto=GAV[,GAV]* parameter, which specifies the 
 * list of artifacts to search for in the dependency graph.
 *
 * If this goal is run using the -Dfrom=GAV[,GAV]* parameter,
 * those GAVs will be treated as the "roots" of the dependency graph (origins of traversal).
 * Otherwise, the current set of projects will be used.
 *
 * @author jdcasey
 */
@Mojo( name = "paths", requiresProject = false, aggregator = true, threadSafe = true )
public class PathsGoal
        extends AbstractDepgraphGoal
{

    private static final String INDENT = "  ";

    private static boolean HAS_RUN = false;

    @Parameter( property = "to", required = true )
    private String toProjects;

    private Set<ProjectRef> toGas;

    @Override
    public void execute()
            throws MojoExecutionException, MojoFailureException
    {
        if ( HAS_RUN )
        {
            getLog().info( "Dependency paths goal has already run. Skipping." );
            return;
        }

        HAS_RUN = true;

        initDepgraph( true );

        final String[] rawGavs = toProjects.split( "\\s*,\\s*" );
        toGas = new HashSet<ProjectRef>( rawGavs.length );
        for ( final String rawGav : rawGavs )
        {
            toGas.add( project( rawGav ) );
        }

        GraphComposition comp = GraphCompositionBuilder.newGraphCompositionBuilder()
                                                       .withGraph( GraphDescriptionBuilder.newGraphDescriptionBuilder()
                                                                                          .withFilter( filter )
                                                                                          .withRoots( roots )
                                                                                          .build() )
                                                       .build();

        PathsRequest request = PathsRequestBuilder.newPathsRecipeBuilder()
                                                  .withResolve( true )
                                                  .withWorkspaceId( WORKSPACE_ID )
                                                  .withSource( MavenLocationExpander.EXPANSION_TARGET )
                                                  .withGraphs( comp )
                                                  .withTargets( toGas )
                                                  .build();

        getLog().info( "Resolving paths to:\n\n  " + join( toGas, "\n  " ) + "\n\nIn scope: " + scope + "\n" );

        ProjectPathsResult result;
        try
        {
            result = carto.getGrapher().getPaths( request );
        }
        catch ( CartoDataException e )
        {
            throw new MojoExecutionException(
                    "Failed to traverse '" + roots + "' looking for paths to: " + toGas + ". Reason: "
                            + e.getMessage(), e );
        }
        catch ( CartoRequestException e )
        {
            throw new MojoExecutionException(
                    "Failed to traverse '" + roots + "' looking for paths to: " + toGas + ". Reason: "
                            + e.getMessage(), e );
        }

        final StringBuilder sb = new StringBuilder();
        if ( result != null && result.getProjects() != null )
        {
            AtomicInteger count = new AtomicInteger( 0 );
            result.getProjects().forEach( ( gav, pathSet ) -> {
                if ( pathSet.getPaths() != null )
                {
                    for ( ProjectPath path : pathSet.getPaths() )
                    {
                        sb.append( count.getAndIncrement() ).append( ". " );
                        printPath( sb, path );
                    }

                    sb.append( "\n\n" );
                }
            } );

            sb.append("\n\n").append( count.incrementAndGet() ).append( " paths found.\n\n");
        }

        if ( sb.length() < 1 )
        {
            sb.append( "\n\nNo paths found!\n\n" );
        }

        write( sb );
    }

    private void printPath( final StringBuilder result, final ProjectPath projectPath )
    {
        List<ProjectRelationship<?, ?>> path = projectPath.getPathParts();
        result.append( "\n" ).append( path.get( 0 ).getDeclaring() ).append( "\n" );

        int indent = 1;
        for ( final ProjectRelationship<?, ?> rel : path )
        {
            for ( int i = 0; i < indent; i++ )
            {
                result.append( INDENT );
            }

            result.append( rel.getTargetArtifact() ).append( "\n" );

            indent++;
        }
    }

}
