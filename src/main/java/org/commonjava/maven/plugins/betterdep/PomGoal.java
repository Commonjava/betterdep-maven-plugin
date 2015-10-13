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

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.commonjava.cartographer.CartoDataException;
import org.commonjava.cartographer.CartoRequestException;
import org.commonjava.cartographer.request.GraphComposition;
import org.commonjava.cartographer.request.GraphDescription;
import org.commonjava.cartographer.request.PathsRequest;
import org.commonjava.cartographer.request.PomRequest;
import org.commonjava.cartographer.request.build.GraphCompositionBuilder;
import org.commonjava.cartographer.request.build.GraphDescriptionBuilder;
import org.commonjava.cartographer.request.build.PathsRequestBuilder;
import org.commonjava.cartographer.request.build.PomRequestBuilder;
import org.commonjava.cartographer.result.ProjectPath;
import org.commonjava.cartographer.result.ProjectPathsResult;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.plugins.betterdep.impl.MavenLocationExpander;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.commons.lang.StringUtils.join;
import static org.commonjava.maven.atlas.ident.util.IdentityUtils.project;

/**
 * Generates a POM containing all the artifacts in the dependency graph for the current project(s), using the
 * preset to determine which relationships are included. Based on the value of the bomOutput option (defaults to true),
 * it may generate a normal POM (with deps for the included artifacts) or a BOM (with managed deps).
 *
 * @author jdcasey
 */
@Mojo( name = "pom", requiresProject = false, aggregator = true, threadSafe = true )
public class PomGoal
        extends AbstractDepgraphGoal
{

    private static final String INDENT = "  ";

    private static boolean HAS_RUN = false;

    @Parameter( property = "preset", defaultValue = "runtime" )
    private String preset;

    @Parameter( property = "presetParams" )
    private String presetParams;

    @Parameter( property = "bomOutput", defaultValue = "true" )
    private boolean bomOutput;

    @Parameter( property = "g", defaultValue = "${project.groupId}" )
    private String groupId;

    @Parameter( property = "a", defaultValue = "bom" )
    private String artifactId;

    @Parameter( property = "v", defaultValue = "${project.version}" )
    private String version;

    @Parameter( property = "output", defaultValue = "${project.build.directory}/flat-pom.xml" )
    private File output;

    @Override
    public void execute()
            throws MojoExecutionException, MojoFailureException
    {
        if ( HAS_RUN )
        {
            getLog().info( "Goal flat-pom has already been run. Skipping." );
            return;
        }

        HAS_RUN = true;

        initDepgraph( true );

        Map<String, String> params = new HashMap<>();
        if ( presetParams != null )
        {
            String[] kvs = presetParams.split( "\\s*,\\s*" );
            for ( String kv : kvs )
            {
                String[] parts = kv.split( "=" );
                if ( parts.length > 1 )
                {
                    params.put( parts[0], parts[1] );
                }
            }
        }

        GraphComposition comp = GraphCompositionBuilder.newGraphCompositionBuilder()
                                                       .withGraph(
                                                               new GraphDescription( preset, MUTATOR, params, roots ) )
                                                       .build();

        PomRequest request = PomRequestBuilder.newPomRequestBuilder()
                                              .withGraphToManagedDeps( bomOutput )
                                              .withWorkspaceId( WORKSPACE_ID )
                                              .withSource( MavenLocationExpander.EXPANSION_TARGET )
                                              .withGraphs( comp )
                                              .build();

        try(FileWriter writer = new FileWriter( output ))
        {
            Model pom = carto.getRenderer().generatePOM( request );

            new MavenXpp3Writer().write( writer, pom );
        }
        catch ( CartoDataException | CartoRequestException e )
        {
            throw new MojoExecutionException(
                    "Failed to generate BOM/POM from '" + roots + "'. Reason: " + e.getMessage(), e );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException(
                    "Failed to write generated BOM/POM to: " + output + ". Reason: " + e.getMessage(), e );
        }

    }

}
