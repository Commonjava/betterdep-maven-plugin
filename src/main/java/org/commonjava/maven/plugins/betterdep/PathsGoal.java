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
package org.commonjava.maven.plugins.betterdep;

import static org.apache.commons.lang.StringUtils.join;
import static org.commonjava.maven.atlas.ident.util.IdentityUtils.project;

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
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.cartographer.data.CartoDataException;
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
        resolveFromDepgraph();

        final String[] rawGavs = toProjects.split( "\\s*,\\s*" );
        toGas = new HashSet<ProjectRef>( rawGavs.length );
        for ( final String rawGav : rawGavs )
        {
            toGas.add( project( rawGav ) );
        }

        getLog().info( "Resolving paths to:\n\n  " + join( toGas, "\n  " ) + "\n\nIn scope: " + scope + "\n" );

        try
        {
            final EProjectWeb web = carto.getDatabase()
                                         .getProjectWeb( roots.toArray( new ProjectVersionRef[roots.size()] ) );

            final PathsTraversal paths = new PathsTraversal( scope, toGas );
            for ( final ProjectVersionRef root : roots )
            {
                try
                {
                    web.traverse( root, paths );
                }
                catch ( final GraphDriverException e )
                {
                    throw new MojoExecutionException( "Failed to traverse '" + root + "' looking for paths to: " + toGas + ". Reason: "
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

            write( result );
        }
        catch ( final CartoDataException e )
        {
            throw new MojoExecutionException( "Failed to retrieve depgraph for roots: " + roots + ". Reason: " + e.getMessage(), e );
        }
    }

    private void printPath( final StringBuilder result, final List<ProjectRelationship<?>> path )
    {
        result.append( "\n" )
              .append( path.get( 0 )
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
