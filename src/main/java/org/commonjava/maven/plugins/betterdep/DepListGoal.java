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

import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.cartographer.data.CartoDataException;
import org.commonjava.maven.plugins.betterdep.impl.BetterDepRelationshipPrinter;

/**
 * Generates a listing of the artifacts contained within the dependency graph for
 * a project or set of projects. Output also includes parent POMs and BOMs referenced from
 * these artifacts by default.
 * 
 * If this goal is run using the -Dfrom=GAV[,GAV]* parameter,
 * those GAVs will be treated as the "roots" of the dependency graph (origins of traversal).
 * Otherwise, the current set of projects will be used.
 *  
 * @author jdcasey
 */
@Mojo( name = "list", requiresProject = false, aggregator = true, threadSafe = true )
public class DepListGoal
    extends AbstractDepgraphGoal
{

    private static boolean HAS_RUN = false;

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( HAS_RUN )
        {
            getLog().info( "Dependency list has already run. Skipping." );
            return;
        }

        HAS_RUN = true;

        initDepgraph( true );
        resolveFromDepgraph();
        try
        {
            final Map<String, Set<ProjectVersionRef>> labels = getLabelsMap();

            final StringBuilder sb = new StringBuilder();
            for ( final ProjectVersionRef root : roots )
            {
                final BetterDepRelationshipPrinter printer = new BetterDepRelationshipPrinter();

                final String printed = carto.getRenderer()
                                            .depList( root, filter, scope, labels, printer );

                sb.append( "\n\n\nDependency list for: " )
                  .append( root )
                  .append( ": \n\n" )
                  .append( printed );
            }

            write( sb );
        }
        catch ( final CartoDataException e )
        {
            throw new MojoExecutionException( "Failed to render dependency list: " + e.getMessage(), e );
        }
    }
}
