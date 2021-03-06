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

import java.io.File;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.commonjava.cartographer.CartoDataException;
import org.commonjava.cartographer.CartoRequestException;
import org.commonjava.cartographer.request.RepositoryContentRequest;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.plugins.betterdep.impl.BetterDepRelationshipPrinter;

/**
 * Generates a tree-style listing of the artifacts contained within the dependency graph for
 * a project or set of projects. Output also includes parent POMs and BOMs referenced from
 * these artifacts by default. This output style is intended to show <em>how</em> different artifacts
 * were included in dependency graph, not just the fact of their inclusion.
 * 
 * If this goal is run using the -Dfrom=GAV[,GAV]* parameter,
 * those GAVs will be treated as the "roots" of the dependency graph (origins of traversal).
 * Otherwise, the current set of projects will be used.
 *  
 * @author jdcasey
 */
@Mojo( name = "tree", requiresProject = false, aggregator = true, threadSafe = true )
public class DepTreeGoal
    extends AbstractRepoGoal
{

    private static boolean HAS_RUN = false;

    @Parameter( defaultValue = "true", property = "collapseTransitives" )
    private boolean collapseTransitives;

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

        if ( output == null )
        {
            output = new File( "target/deptree.txt" );
        }

        Writer writer = null;
        try
        {
            writer = getWriter();
            final PrintWriter pw = new PrintWriter( writer );

            RepositoryContentRequest request = repoContentRequest();
            carto.getRenderer().depTree( request, collapseTransitives, pw );

            getLog().info( "Dependency tree(s) written to: " + output );
        }
        catch ( final CartoDataException | CartoRequestException e )
        {
            throw new MojoExecutionException( "Failed to render dependency tree: " + e.getMessage(), e );
        }
        finally
        {
            IOUtils.closeQuietly( writer );
        }
    }
}
