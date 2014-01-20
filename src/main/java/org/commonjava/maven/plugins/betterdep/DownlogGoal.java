package org.commonjava.maven.plugins.betterdep;

import static org.apache.commons.lang.StringUtils.join;
import static org.commonjava.maven.galley.util.UrlUtils.buildUrl;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Level;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.cartographer.data.CartoDataException;
import org.commonjava.maven.cartographer.dto.ExtraCT;
import org.commonjava.maven.cartographer.dto.GraphComposition;
import org.commonjava.maven.cartographer.dto.GraphDescription;
import org.commonjava.maven.cartographer.dto.RepositoryContentRecipe;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.SimpleLocation;
import org.commonjava.maven.plugins.betterdep.impl.MavenLocationExpander;
import org.commonjava.util.logging.Log4jUtil;

@Mojo( name = "downlog", requiresProject = false, aggregator = true, threadSafe = true )
public class DownlogGoal
    extends AbstractDepgraphGoal
{

    private static final Set<String> DEFAULT_METAS;

    private static final Set<ExtraCT> DEFAULT_EXTRAS;

    private static boolean HAS_RUN = false;

    static
    {
        final Set<String> m = new HashSet<String>();

        m.add( "sha1" );
        m.add( "md5" );
        m.add( "asc" );
        m.add( "asc.sha1" );
        m.add( "asc.md5" );

        DEFAULT_METAS = m;

        final Set<ExtraCT> es = new HashSet<ExtraCT>();

        ExtraCT e = new ExtraCT();
        e.setClassifier( "javadoc" );
        e.setType( "jar" );

        es.add( e );

        e = new ExtraCT();
        e.setClassifier( "sources" );
        e.setType( "jar" );

        es.add( e );

        DEFAULT_EXTRAS = es;
    }

    @Parameter( property = "output" )
    private File output;

    @Parameter
    private final Set<String> metas = DEFAULT_METAS;

    @Parameter
    private final Set<ExtraCT> extras = DEFAULT_EXTRAS;

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

        Log4jUtil.configure( getLog().isDebugEnabled() ? Level.INFO : Level.WARN, "%-5p [%t]: %m%n" );

        initDepgraph( false );
        final Set<String> downLog = new HashSet<String>();
        final RepositoryContentRecipe recipe = new RepositoryContentRecipe();
        recipe.setGraphComposition( new GraphComposition( null, Collections.singletonList( new GraphDescription( filter, roots ) ) ) );
        recipe.setMetas( metas );
        recipe.setExtras( extras );
        recipe.setResolve( true );
        recipe.setWorkspaceId( WORKSPACE_ID );
        recipe.setSourceLocation( new SimpleLocation( MavenLocationExpander.EXPANSION_TARGET ) );

        Map<ProjectVersionRef, Map<ArtifactRef, ConcreteResource>> contents;
        try
        {
            contents = carto.getResolver()
                            .resolveRepositoryContents( recipe );
        }
        catch ( final CartoDataException e )
        {
            throw new MojoExecutionException( String.format( "Failed to resolve repository contents for: %s. Reason: %s", recipe, e.getMessage() ), e );
        }

        final List<ProjectVersionRef> refs = new ArrayList<ProjectVersionRef>( contents.keySet() );
        Collections.sort( refs );

        boolean errors = false;
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

        try
        {
            if ( output == null )
            {
                getLog().info( out );
            }
            else
            {
                FileUtils.write( output, out );
            }
        }
        catch ( final IOException e )
        {
            throw new MojoExecutionException( "Failed to render download log to: " + output + ". Reason: " + e.getMessage(), e );
        }

        if ( errors )
        {
            throw new MojoFailureException( "One or more items failed to render. See output above." );
        }
    }

    private String formatDownlogEntry( final ConcreteResource item )
        throws MalformedURLException
    {
        return "Downloading: " + buildUrl( item.getLocation()
                                               .getUri(), item.getPath() );
    }

}
