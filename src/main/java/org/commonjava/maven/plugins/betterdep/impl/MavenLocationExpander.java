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
package org.commonjava.maven.plugins.betterdep.impl;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.commonjava.cartographer.CartoDataException;
import org.commonjava.cartographer.spi.graph.discover.DiscoverySourceManager;
import org.commonjava.maven.atlas.graph.ViewParams;
import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.Resource;
import org.commonjava.maven.galley.model.SimpleLocation;
import org.commonjava.maven.galley.model.VirtualResource;
import org.commonjava.maven.galley.spi.transport.LocationExpander;
import org.commonjava.maven.galley.transport.htcli.model.SimpleHttpLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Galley {@link LocationExpander} implementation that expands a shorthand URI
 * given in the betterdep goals into the actual list of locations to check for
 * artifacts.
 * 
 * @author jdcasey
 */
public class MavenLocationExpander
    implements LocationExpander, DiscoverySourceManager
{

    public static final String EXPANSION_TARGET = "maven:repositories";

    public static final String LOCAL_URI = "file:maven:local-or-preresolved";

    private final List<Location> locations;

    private final List<URI> locationUris;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    public MavenLocationExpander( final List<Location> customLocations,
                                  final List<ArtifactRepository> artifactRepositories,
                                  final ArtifactRepository localRepository )
        throws MalformedURLException, URISyntaxException
    {
        final Set<Location> locs = new LinkedHashSet<Location>();
        final Set<URI> uris = new HashSet<URI>();

        if ( localRepository != null )
        {
            locs.add( new SimpleLocation( new File( localRepository.getBasedir() ).toURI()
                                                                                  .toString() ) );
        }

        if ( customLocations != null )
        {
            locs.addAll( customLocations );
        }

        for ( final ArtifactRepository repo : artifactRepositories )
        {
            // TODO: Authentication via memory password manager.
            final String url = repo.getUrl();
            uris.add( new URI( url ) );

            if ( url.startsWith( "file:" ) )
            {
                locs.add( new SimpleLocation( url ) );
            }
            else
            {
                final ArtifactRepositoryPolicy releases = repo.getReleases();
                final ArtifactRepositoryPolicy snapshots = repo.getSnapshots();
                locs.add( new SimpleHttpLocation( url, url, snapshots != null && snapshots.isEnabled(),
                                                  releases == null || releases.isEnabled(), true, false, null ) );
            }
        }

        this.locationUris = new ArrayList<URI>( uris );
        this.locations = new ArrayList<Location>( locs );
    }

    @Override
    public List<Location> expand( final Location... locations )
        throws TransferException
    {
        final List<Location> result = new ArrayList<Location>();
        for ( final Location loc : locations )
        {
            logger.info( "Expanding: {}", loc );
            if ( EXPANSION_TARGET.equals( loc.getUri() ) )
            {
                result.addAll( this.locations );
            }
            else
            {
                result.add( loc );
            }
        }

        return result;
    }

    @Override
    public <T extends Location> List<Location> expand( final Collection<T> locations )
        throws TransferException
    {
        final List<Location> result = new ArrayList<Location>();
        for ( final Location loc : locations )
        {
            logger.info( "Expanding: {}", loc );
            if ( EXPANSION_TARGET.equals( loc.getUri() ) )
            {
                result.addAll( this.locations );
            }
            else
            {
                result.add( loc );
            }
        }

        return result;
    }

    @Override
    public VirtualResource expand( final Resource resource )
        throws TransferException
    {
        if ( resource instanceof ConcreteResource )
        {
            final ConcreteResource cr = (ConcreteResource) resource;
            final Location loc = cr.getLocation();

            logger.info( "Expanding: {}", loc );

            final List<Location> result = new ArrayList<Location>();
            if ( EXPANSION_TARGET.equals( loc.getUri() ) )
            {
                result.addAll( this.locations );
            }
            else
            {
                result.add( loc );
            }

            return new VirtualResource( result, cr.getPath() );
        }
        else
        {
            final List<ConcreteResource> expanded = new ArrayList<ConcreteResource>();
            for ( final ConcreteResource cr : (VirtualResource) resource )
            {
                final Location loc = cr.getLocation();
                logger.info( "Expanding: {}", loc );

                final List<Location> locations = new ArrayList<Location>();
                if ( EXPANSION_TARGET.equals( loc.getUri() ) )
                {
                    locations.addAll( this.locations );
                }
                else
                {
                    locations.add( loc );
                }

                for ( final Location location : locations )
                {
                    expanded.add( new ConcreteResource( location, cr.getPath() ) );
                }
            }

            return new VirtualResource( expanded );
        }
    }

    @Override
    public Location createLocation( final Object source )
    {
        return new SimpleLocation( source.toString() );
    }

    @Override
    public List<? extends Location> createLocations( final Object... sources )
    {
        final List<Location> result = new ArrayList<Location>();
        for ( final Object src : sources )
        {
            logger.info( "Expanding: {}", src );
            if ( EXPANSION_TARGET.equals( src ) )
            {
                result.addAll( this.locations );
            }
            else
            {
                result.add( new SimpleLocation( src.toString() ) );
            }
        }

        return result;
    }

    @Override
    public List<? extends Location> createLocations( final Collection<Object> sources )
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public URI createSourceURI( final String source )
    {
        try
        {
            return new URI( source );
        }
        catch ( final URISyntaxException e )
        {
            throw new RuntimeException( "Failed to construct URI from: '" + source + "'. Reason: " + e.getMessage(), e );
        }
    }

    @Override
    public boolean activateWorkspaceSources( final ViewParams params, final String... sources )
        throws CartoDataException
    {
        final int initialCount = params.getActiveSources()
                                       .size();

        for ( final String loc : sources )
        {
            if ( EXPANSION_TARGET.equals( loc ) )
            {
                try
                {
                    params.addActiveSource( new URI( LOCAL_URI ) );
                }
                catch ( final URISyntaxException e )
                {
                    throw new CartoDataException( "Failed to construct URI from: '%s'. Reason: %s", e, LOCAL_URI,
                                                  e.getMessage() );
                }

                try
                {
                    params.addActiveSource( new URI( EXPANSION_TARGET ) );
                }
                catch ( final URISyntaxException e )
                {
                    throw new CartoDataException( "Failed to construct URI from: '%s'. Reason: %s", e,
                                                  EXPANSION_TARGET, e.getMessage() );
                }

                params.addActiveSources( locationUris );
            }
            else
            {
                try
                {
                    params.addActiveSource( new URI( loc ) );
                }
                catch ( final URISyntaxException e )
                {
                    throw new CartoDataException( "Failed to construct URI from: '%s'. Reason: %s", e, loc,
                                                  e.getMessage() );
                }
            }
        }

        return params.getActiveSources()
                     .size() > initialCount;
    }

    @Override
    public String getFormatHint()
    {
        return EXPANSION_TARGET + " or a valid http/file URL";
    }

    @Override
    public boolean activateWorkspaceSources( final ViewParams params, final Collection<? extends Location> locations )
        throws CartoDataException
    {
        final int initialCount = params.getActiveSources()
                                       .size();

        for ( final Location location : locations )
        {
            final String loc = location.getUri();

            if ( EXPANSION_TARGET.equals( loc ) )
            {
                try
                {
                    params.addActiveSource( new URI( LOCAL_URI ) );
                }
                catch ( final URISyntaxException e )
                {
                    throw new CartoDataException( "Failed to construct URI from: '%s'. Reason: %s", e, LOCAL_URI,
                                                  e.getMessage() );
                }

                try
                {
                    params.addActiveSource( new URI( EXPANSION_TARGET ) );
                }
                catch ( final URISyntaxException e )
                {
                    throw new CartoDataException( "Failed to construct URI from: '%s'. Reason: %s", e,
                                                  EXPANSION_TARGET, e.getMessage() );
                }

                params.addActiveSources( locationUris );
            }
            else
            {
                try
                {
                    params.addActiveSource( new URI( loc ) );
                }
                catch ( final URISyntaxException e )
                {
                    throw new CartoDataException( "Failed to construct URI from: '%s'. Reason: %s", e, loc,
                                                  e.getMessage() );
                }
            }
        }

        return params.getActiveSources()
                     .size() > initialCount;
    }

}
