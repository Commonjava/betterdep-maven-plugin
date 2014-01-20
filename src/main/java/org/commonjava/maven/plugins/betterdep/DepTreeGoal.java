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

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.cartographer.data.CartoDataException;
import org.commonjava.maven.plugins.betterdep.impl.BetterDepRelationshipPrinter;

@Mojo( name = "tree", requiresProject = false, aggregator = true, threadSafe = true )
public class DepTreeGoal
    extends AbstractDepgraphGoal
{

    private static boolean HAS_RUN = false;

    @Parameter( property = "output" )
    private File output;

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( HAS_RUN )
        {
            getLog().info( "Dependency tree has already run. Skipping." );
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
                final Set<ProjectVersionRef> missing = carto.getDatabase()
                                                            .getAllIncompleteSubgraphs();

                final BetterDepRelationshipPrinter printer = new BetterDepRelationshipPrinter( missing );

                final String printed = carto.getRenderer()
                                            .depTree( root, filter, scope, dedupe, labels, printer );

                sb.append( "\n\n\nDependency tree for: " )
                  .append( root )
                  .append( ": \n\n" )
                  .append( printed );
            }

            if ( output == null )
            {
                getLog().info( sb.toString() );
            }
            else
            {
                FileUtils.write( output, sb.toString() );
            }
        }
        catch ( final CartoDataException e )
        {
            throw new MojoExecutionException( "Failed to render dependency tree: " + e.getMessage(), e );
        }
        catch ( final IOException e )
        {
            throw new MojoExecutionException( "Failed to render dependency tree to: " + output + ". Reason: " + e.getMessage(), e );
        }
    }
}
