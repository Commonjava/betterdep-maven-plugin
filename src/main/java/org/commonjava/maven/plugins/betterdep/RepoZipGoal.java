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

import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.commons.io.IOUtils.copy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferBatch;

/**
 * Generates a zip archive containing all the artifacts and other related files
 * for a dependency graph. Optionally, other extra files like checksums, signatures,
 * and attached artifacts may be included. 
 * 
 * If this goal is run using the -Dfrom=GAV[,GAV]* parameter,
 * those GAVs will be treated as the "roots" of the dependency graph (origins of traversal).
 * Otherwise, the current set of projects will be used.
 *  
 * @todo Currently, this goal DOES NOT generate corrected maven-metadata.xml 
 * files for the resulting repository.
 * 
 * @author jdcasey
 */
@Mojo( name = "repozip", requiresProject = false, aggregator = true, threadSafe = true )
public class RepoZipGoal
    extends AbstractRepoGoal
{

    private static boolean HAS_RUN = false;

    @Override
    public void execute()
        throws MojoExecutionException
    {
        if ( HAS_RUN )
        {
            getLog().info( "Download log has already run. Skipping." );
            return;
        }

        HAS_RUN = true;

        final Map<ProjectVersionRef, Map<ArtifactRef, ConcreteResource>> contents = resolveRepoContents();

        constructZip( contents );
    }

    private void constructZip( final Map<ProjectVersionRef, Map<ArtifactRef, ConcreteResource>> contents )
        throws MojoExecutionException
    {
        if ( output == null )
        {
            output = new File( "target/repo.zip" );
        }

        output.getParentFile()
              .mkdirs();

        OutputStream zipStream = null;
        ZipOutputStream stream = null;
        try
        {
            zipStream = new FileOutputStream( output );

            final Set<ConcreteResource> entries = new HashSet<ConcreteResource>();
            final Set<String> seenPaths = new HashSet<String>();

            getLog().info( "Iterating contents with " + contents.size() + " GAVs." );
            for ( final Map<ArtifactRef, ConcreteResource> artifactResources : contents.values() )
            {
                for ( final Entry<ArtifactRef, ConcreteResource> entry : artifactResources.entrySet() )
                {
                    final ArtifactRef ref = entry.getKey();
                    final ConcreteResource resource = entry.getValue();

                    //                        logger.info( "Checking %s (%s) for inclusion...", ref, resource );

                    final String path = resource.getPath();
                    if ( seenPaths.contains( path ) )
                    {
                        getLog().info( "Conflicting path: " + path + ". Skipping " + ref );
                        continue;
                    }

                    seenPaths.add( path );

                    //                        logger.info( "Adding to batch: %s via resource: %s", ref, resource );
                    entries.add( resource );
                }
            }

            getLog().info( "Starting batch retrieval of " + entries.size() + " artifacts." );
            TransferBatch batch = new TransferBatch( entries );
            batch = cartoBuilder.getTransferMgr()
                                .batchRetrieve( batch );

            getLog().info( "Retrieved " + batch.getTransfers()
                                               .size() + " artifacts. Creating zip." );
            stream = new ZipOutputStream( zipStream );

            final List<Transfer> items = new ArrayList<Transfer>( batch.getTransfers()
                                                                       .values() );
            Collections.sort( items, new Comparator<Transfer>()
            {
                @Override
                public int compare( final Transfer f, final Transfer s )
                {
                    return f.getPath()
                            .compareTo( s.getPath() );
                }
            } );

            for ( final Transfer item : items )
            {
                //                    logger.info( "Adding: %s", item );
                final String path = item.getPath();
                if ( item != null )
                {
                    final ZipEntry ze = new ZipEntry( path );
                    stream.putNextEntry( ze );

                    InputStream itemStream = null;
                    try
                    {
                        itemStream = item.openInputStream();
                        copy( itemStream, stream );
                    }
                    finally
                    {
                        closeQuietly( itemStream );
                    }
                }
            }

            getLog().info( "\n\nWrote repository archive to: " + output );
        }
        catch ( final IOException e )
        {
            throw new MojoExecutionException( "Failed to generate runtime repository. Reason: " + e.getMessage(), e );
        }
        catch ( final TransferException e )
        {
            throw new MojoExecutionException( "Failed to generate runtime repository. Reason: " + e.getMessage(), e );
        }
        finally
        {
            closeQuietly( stream );
            closeQuietly( zipStream );
        }
    }

}
