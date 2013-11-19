package org.commonjava.maven.plugins.betterdep;

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

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.project.MavenProject;
import org.commonjava.maven.atlas.graph.workspace.GraphWorkspace;
import org.commonjava.maven.cartographer.data.CartoDataException;
import org.commonjava.maven.cartographer.discover.DiscoverySourceManager;
import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.Resource;
import org.commonjava.maven.galley.model.SimpleLocation;
import org.commonjava.maven.galley.model.VirtualResource;
import org.commonjava.maven.galley.spi.transport.LocationExpander;
import org.commonjava.maven.galley.transport.htcli.model.SimpleHttpLocation;
import org.commonjava.util.logging.Logger;

public class MavenLocationExpander
    implements LocationExpander, DiscoverySourceManager
{

    public static final String EXPANSION_TARGET = "maven:repositories";

    public static final String LOCAL_URI = "file:maven:local-or-preresolved";

    private final List<Location> locations;

    private final List<URI> locationUris;

    private final Logger logger = new Logger( getClass() );

    public MavenLocationExpander( final List<MavenProject> projects, final ArtifactRepository localRepository )
        throws MalformedURLException, URISyntaxException
    {
        final Set<Location> locs = new LinkedHashSet<Location>();
        final Set<URI> uris = new HashSet<URI>();

        for ( final MavenProject project : projects )
        {
            final List<ArtifactRepository> repositories = project.getRemoteArtifactRepositories();
            for ( final ArtifactRepository repo : repositories )
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
                    locs.add( new SimpleHttpLocation( url, url, snapshots == null ? false : snapshots.isEnabled(), releases == null ? true
                                    : releases.isEnabled(), true, false, -1, null ) );
                }
            }
        }

        locs.add( new SimpleLocation( new File( localRepository.getBasedir() ).toURI()
                                                                              .toString() ) );

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
            logger.info( "Expanding: %s", loc );
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
            logger.info( "Expanding: %s", loc );
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
        final List<Location> result = new ArrayList<Location>();
        if ( resource instanceof ConcreteResource )
        {
            final Location loc = ( (ConcreteResource) resource ).getLocation();
            logger.info( "Expanding: %s", loc );
            if ( EXPANSION_TARGET.equals( loc.getUri() ) )
            {
                result.addAll( this.locations );
            }
            else
            {
                result.add( loc );
            }
        }
        else
        {
            for ( final Location loc : ( (VirtualResource) resource ).getLocations() )
            {
                logger.info( "Expanding: %s", loc );
                if ( EXPANSION_TARGET.equals( loc.getUri() ) )
                {
                    result.addAll( this.locations );
                }
                else
                {
                    result.add( loc );
                }
            }
        }

        return new VirtualResource( result, resource.getPath() );
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
            logger.info( "Expanding: %s", src );
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
    public void activateWorkspaceSources( final GraphWorkspace ws, final String... sources )
        throws CartoDataException
    {
        for ( final String loc : sources )
        {
            if ( EXPANSION_TARGET.equals( loc ) )
            {
                try
                {
                    ws.addActiveSource( new URI( LOCAL_URI ) );
                }
                catch ( final URISyntaxException e )
                {
                    throw new CartoDataException( "Failed to construct URI from: '%s'. Reason: %s", e, LOCAL_URI, e.getMessage() );
                }

                ws.addActiveSources( locationUris );
            }
            else
            {
                try
                {
                    ws.addActiveSource( new URI( loc ) );
                }
                catch ( final URISyntaxException e )
                {
                    throw new CartoDataException( "Failed to construct URI from: '%s'. Reason: %s", e, loc, e.getMessage() );
                }
            }
        }
    }

    @Override
    public String getFormatHint()
    {
        return EXPANSION_TARGET + " or a valid http/file URL";
    }

}
