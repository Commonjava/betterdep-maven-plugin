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
import static org.commonjava.maven.galley.util.UrlUtils.buildUrl;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.galley.model.ConcreteResource;

/**
 * Generates a list of URLs for artifact and their related files for each artifact
 * in the dependency graph. Optionally, each line can be prefixed with 'Downloading: '
 * to mimic a distillation of the console output from a Maven build.
 * 
 * Also optionally, other extra files like checksums, signatures, and attached 
 * artifacts may be included.
 * 
 * This goal is useful to quickly generate a listing of URLs that would need to 
 * be imported to a cleanroom build environment in order to build the given projects.
 * 
 * If this goal is run using the -Dfrom=GAV[,GAV]* parameter,
 * those GAVs will be treated as the "roots" of the dependency graph (origins of traversal).
 * Otherwise, the current set of projects will be used.
 *  
 * @author jdcasey
 */
@Mojo( name = "downlog", requiresProject = false, aggregator = true, threadSafe = true )
public class DownlogGoal
    extends AbstractRepoGoal
{
    private static boolean HAS_RUN = false;

    @Parameter( property = "usePrefix", defaultValue = "false" )
    private boolean usePrefix;

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( HAS_RUN )
        {
            getLog().info( "Download log has already run. Skipping." );
            return;
        }

        HAS_RUN = true;

        final Map<ProjectVersionRef, Map<ArtifactRef, ConcreteResource>> contents = resolveRepoContents();

        final List<ProjectVersionRef> refs = new ArrayList<ProjectVersionRef>( contents.keySet() );
        Collections.sort( refs );

        boolean errors = false;
        final Set<String> downLog = new HashSet<String>();
        for ( final ProjectVersionRef ref : refs )
        {
            final Map<ArtifactRef, ConcreteResource> items = contents.get( ref );
            for ( final ConcreteResource item : items.values() )
            {
                getLog().info( "Adding: " + item );
                try
                {
                    downLog.add( formatDownlogEntry( item ) );
                }
                catch ( final MalformedURLException e )
                {
                    getLog().error( "Failed to format URL for: " + item + ". Reason: " + e.getMessage(), e );
                    errors = true;
                }
            }
        }

        final List<String> sorted = new ArrayList<String>( downLog );
        Collections.sort( sorted );

        final String out = join( sorted, "\n" );

        write( out );

        if ( errors )
        {
            throw new MojoFailureException( "One or more items failed to render. See output above." );
        }
    }

    private String formatDownlogEntry( final ConcreteResource item )
        throws MalformedURLException
    {
        final String url = buildUrl( item.getLocation()
                                         .getUri(), item.getPath() );

        if ( usePrefix )
        {
            return "Downloaded: " + url;
        }

        return url;
    }

}
