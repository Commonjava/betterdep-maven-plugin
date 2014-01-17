package org.commonjava.maven.plugins.betterdep;

import static org.commonjava.maven.atlas.ident.util.IdentityUtils.projectVersion;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Profile;
import org.apache.maven.monitor.logging.DefaultLog;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.commonjava.maven.atlas.graph.filter.ProjectRelationshipFilter;
import org.commonjava.maven.atlas.graph.rel.DependencyRelationship;
import org.commonjava.maven.atlas.graph.rel.ParentRelationship;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.graph.spi.neo4j.FileNeo4jWorkspaceFactory;
import org.commonjava.maven.atlas.graph.util.RelationshipUtils;
import org.commonjava.maven.atlas.graph.workspace.GraphWorkspace;
import org.commonjava.maven.atlas.ident.DependencyScope;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.cartographer.Cartographer;
import org.commonjava.maven.cartographer.CartographerBuilder;
import org.commonjava.maven.cartographer.data.CartoDataException;
import org.commonjava.maven.cartographer.discover.DefaultDiscoveryConfig;
import org.commonjava.maven.cartographer.discover.DiscoveryResult;
import org.commonjava.maven.cartographer.discover.ProjectRelationshipDiscoverer;
import org.commonjava.maven.cartographer.discover.post.patch.DepgraphPatcher;
import org.commonjava.maven.cartographer.dto.GraphComposition;
import org.commonjava.maven.cartographer.dto.GraphDescription;
import org.commonjava.maven.cartographer.dto.ResolverRecipe;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.SimpleLocation;
import org.commonjava.maven.plugins.betterdep.impl.BetterDepFilter;
import org.commonjava.maven.plugins.betterdep.impl.MavenLocationExpander;

public abstract class AbstractDepgraphGoal
    implements Mojo
{

    public static final String WORKSPACE_ID = "betterdep";

    @Parameter( defaultValue = "${reactorProjects}", readonly = true, required = true )
    private List<MavenProject> projects;

    @Parameter( property = "from" )
    private String fromProjects;

    @Parameter( property = "in" )
    private String inRepos;

    @Parameter( defaultValue = "${project.build.directory}/dep/resolved", readonly = true, required = true )
    private File resolverDir;

    @Parameter( defaultValue = "${project.build.directory}/dep/db", readonly = true, required = true )
    private File dbDir;

    private Log log;

    @Parameter( defaultValue = "runtime", required = true, property = "dep.scope" )
    protected DependencyScope scope;

    @Parameter( defaultValue = "true", required = true, property = "dep.dedupe" )
    protected boolean dedupe;

    @Parameter( defaultValue = "${session}", readonly = true, required = true )
    private MavenSession session;

    protected Set<ProjectVersionRef> roots;

    protected ProjectRelationshipFilter filter;

    private Set<URI> profiles;

    protected GraphWorkspace workspace;

    private List<ProjectRelationship<?>> rootRels;

    private ProjectRelationshipDiscoverer discoverer;

    private List<Location> customLocations;

    private List<ArtifactRepository> artifactRepositories;

    protected static CartographerBuilder cartoBuilder;

    protected static Cartographer carto;

    public AbstractDepgraphGoal()
    {
        super();
    }

    protected void initDepgraph( final boolean useLocalRepo )
        throws MojoExecutionException
    {
        rootRels = new ArrayList<ProjectRelationship<?>>();
        roots = new LinkedHashSet<ProjectVersionRef>();
        profiles = new HashSet<URI>();

        if ( inRepos != null )
        {
            final String[] repos = inRepos.split( "\\s*,\\s*" );
            customLocations = new ArrayList<Location>( repos.length );
            for ( final String repo : repos )
            {
                customLocations.add( new SimpleLocation( repo, repo ) );
            }
        }

        artifactRepositories = session.getRequest()
                                      .getRemoteRepositories();

        final List<String> activeProfiles = session.getRequest()
                                                   .getActiveProfiles();
        if ( activeProfiles != null )
        {
            for ( final String activeProfile : activeProfiles )
            {
                profiles.add( RelationshipUtils.profileLocation( activeProfile ) );
            }
        }

        if ( carto == null )
        {
            startCartographer( useLocalRepo );
        }

        if ( fromProjects != null )
        {
            readFromGAVs();
        }
        else
        {
            readFromReactorProjects();
        }

        if ( profiles != null && !profiles.isEmpty() )
        {
            workspace.addActivePomLocations( profiles );
        }

        getLog().info( "Storing direct relationships..." );
        try
        {
            final Set<ProjectRelationship<?>> rejected = carto.getDatabase()
                                                              .storeRelationships( rootRels );

            getLog().info( "The following direct relationships were rejected: " + rejected );
        }
        catch ( final CartoDataException e )
        {
            throw new MojoExecutionException( "Failed to store direct project relationships in depgraph database: " + e.getMessage(), e );
        }

        //        final ProjectVersionRef projectRef = new ProjectVersionRef( project.getGroupId(), project.getArtifactId(), project.getVersion() );

        //        filter = new MavenRuntimeFilter();
        filter = new BetterDepFilter( scope );
        //        filter = new DependencyFilter( scope );
    }

    protected void readFromGAVs()
        throws MojoExecutionException
    {
        final String[] rawGavs = fromProjects.split( "\\s*,\\s*" );

        final Collection<DepgraphPatcher> patchers = cartoBuilder.getDepgraphPatchers();
        final Set<String> patcherIds = new HashSet<String>();
        if ( patchers != null )
        {
            for ( final DepgraphPatcher depgraphPatcher : patchers )
            {
                patcherIds.add( depgraphPatcher.getId() );
            }
        }

        final DefaultDiscoveryConfig config;
        try
        {
            config = new DefaultDiscoveryConfig( MavenLocationExpander.EXPANSION_TARGET );
            config.setEnabledPatchers( patcherIds );
        }
        catch ( final URISyntaxException e )
        {
            throw new MojoExecutionException( "Cannot configure discovery for: " + fromProjects + ". Try -X for more information." );
        }

        for ( final String rawGav : rawGavs )
        {
            final ProjectVersionRef projectRef = projectVersion( rawGav );
            roots.add( projectRef );

            if ( discoverer == null )
            {
                discoverer = cartoBuilder.getDiscoverer();
            }

            DiscoveryResult result;
            try
            {
                result = discoverer.discoverRelationships( projectRef, config, false );
            }
            catch ( final CartoDataException e )
            {
                throw new MojoExecutionException( "Cannot discover direct relationships for: " + rawGav + ": " + e.getMessage(), e );
            }

            if ( result == null )
            {
                throw new MojoExecutionException( "Cannot discover direct relationships for: " + rawGav + ". Try -X for more information." );
            }

            rootRels.addAll( result.getAcceptedRelationships() );
        }
    }

    protected void readFromReactorProjects()
        throws MojoExecutionException
    {
        for ( final MavenProject project : projects )
        {
            final List<Profile> activeProfiles = project.getActiveProfiles();
            if ( activeProfiles != null )
            {
                for ( final Profile profile : activeProfiles )
                {
                    profiles.add( RelationshipUtils.profileLocation( profile.getId() ) );
                }
            }

            final ProjectVersionRef projectRef = new ProjectVersionRef( project.getGroupId(), project.getArtifactId(), project.getVersion() );
            roots.add( projectRef );

            final List<Dependency> deps = project.getDependencies();
            //            final List<ProjectVersionRef> roots = new ArrayList<ProjectVersionRef>( deps.size() );

            URI localUri;
            try
            {
                localUri = new URI( MavenLocationExpander.LOCAL_URI );
            }
            catch ( final URISyntaxException e )
            {
                throw new MojoExecutionException( "Failed to construct dummy local URI to use as the source of the current project in the depgraph: "
                    + e.getMessage(), e );
            }

            final int index = 0;
            for ( final Dependency dep : deps )
            {
                final ProjectVersionRef depRef = new ProjectVersionRef( dep.getGroupId(), dep.getArtifactId(), dep.getVersion() );

                //                roots.add( depRef );

                final List<Exclusion> exclusions = dep.getExclusions();
                final List<ProjectRef> excludes = new ArrayList<ProjectRef>();
                if ( exclusions != null && !exclusions.isEmpty() )
                {
                    for ( final Exclusion exclusion : exclusions )
                    {
                        excludes.add( new ProjectRef( exclusion.getGroupId(), exclusion.getArtifactId() ) );
                    }
                }

                rootRels.add( new DependencyRelationship( localUri, projectRef, new ArtifactRef( depRef, dep.getType(), dep.getClassifier(),
                                                                                                 dep.isOptional() ),
                                                          DependencyScope.getScope( dep.getScope() ), index, false,
                                                          excludes.toArray( new ProjectRef[excludes.size()] ) ) );
            }

            ProjectVersionRef lastParent = projectRef;
            MavenProject parent = project.getParent();
            while ( parent != null )
            {
                final ProjectVersionRef parentRef = new ProjectVersionRef( parent.getGroupId(), parent.getArtifactId(), parent.getVersion() );

                rootRels.add( new ParentRelationship( localUri, projectRef, parentRef ) );

                lastParent = parentRef;
                parent = parent.getParent();
            }

            rootRels.add( new ParentRelationship( localUri, lastParent ) );
        }

    }

    protected void resolveDepgraph()
        throws MojoExecutionException
    {
        final GraphDescription graphDesc = new GraphDescription( filter, roots );
        final GraphComposition comp = new GraphComposition( null, Collections.singletonList( graphDesc ) );
        final ResolverRecipe recipe = new ResolverRecipe();
        recipe.setGraphComposition( comp );
        recipe.setResolve( true );
        recipe.setWorkspaceId( WORKSPACE_ID );
        recipe.setSourceLocation( new SimpleLocation( MavenLocationExpander.EXPANSION_TARGET ) );

        getLog().info( "Resolving depgraph(s)..." );
        try
        {
            carto.getResolver()
                 .resolve( recipe );
        }
        catch ( final CartoDataException e )
        {
            throw new MojoExecutionException( "Failed to resolve graph: " + e.getMessage(), e );
        }
    }

    protected Map<String, Set<ProjectVersionRef>> getLabelsMap()
        throws CartoDataException
    {
        final Map<String, Set<ProjectVersionRef>> labels = new HashMap<String, Set<ProjectVersionRef>>();
        labels.put( "ROOT", roots );

        labels.put( "NOT-RESOLVED", carto.getDatabase()
                                         .getAllIncompleteSubgraphs() );

        labels.put( "VARIABLE", carto.getDatabase()
                                     .getAllVariableSubgraphs() );

        return labels;
    }

    private void startCartographer( final boolean useLocalRepo )
        throws MojoExecutionException
    {
        getLog().info( "Starting cartographer..." );
        try
        {
            resolverDir.mkdirs();
            dbDir.mkdirs();

            /* @formatter:off */
            // TODO: Create a proper cache provider that works with the maven local repository format.
            
            final MavenLocationExpander mavenLocations = new MavenLocationExpander( customLocations, 
                                                                                    artifactRepositories, 
                                                                                    useLocalRepo ? session.getLocalRepository() : null );

            cartoBuilder = new CartographerBuilder( WORKSPACE_ID, resolverDir, 4, new FileNeo4jWorkspaceFactory( dbDir, true ) )
                                .withLocationExpander( mavenLocations )
                                .withSourceManager( mavenLocations )
                                .withDefaultTransports();

            carto = cartoBuilder.build();
            /* @formatter:on */

            carto.getDatabase()
                 .setCurrentWorkspace( WORKSPACE_ID );

            workspace = carto.getDatabase()
                             .getCurrentWorkspace();

        }
        catch ( final CartoDataException e )
        {
            throw new MojoExecutionException( "Failed to start cartographer: " + e.getMessage(), e );
        }
        catch ( final MalformedURLException e )
        {
            throw new MojoExecutionException( "Failed to start cartographer: " + e.getMessage(), e );
        }
        catch ( final URISyntaxException e )
        {
            throw new MojoExecutionException( "Failed to start cartographer: " + e.getMessage(), e );
        }
    }

    @Override
    public Log getLog()
    {
        if ( log == null )
        {
            log = new DefaultLog( new ConsoleLogger() );
        }

        return log;
    }

    @Override
    public void setLog( final Log log )
    {
        this.log = log;
    }

}