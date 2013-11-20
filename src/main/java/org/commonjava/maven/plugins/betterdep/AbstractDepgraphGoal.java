package org.commonjava.maven.plugins.betterdep;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.monitor.logging.DefaultLog;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.commonjava.maven.atlas.graph.filter.DependencyFilter;
import org.commonjava.maven.atlas.graph.filter.ProjectRelationshipFilter;
import org.commonjava.maven.atlas.graph.rel.DependencyRelationship;
import org.commonjava.maven.atlas.graph.rel.ParentRelationship;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.graph.spi.neo4j.FileNeo4jWorkspaceFactory;
import org.commonjava.maven.atlas.ident.DependencyScope;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.cartographer.Cartographer;
import org.commonjava.maven.cartographer.CartographerBuilder;
import org.commonjava.maven.cartographer.data.CartoDataException;
import org.commonjava.maven.cartographer.dto.GraphComposition;
import org.commonjava.maven.cartographer.dto.GraphDescription;
import org.commonjava.maven.cartographer.dto.ResolverRecipe;
import org.commonjava.maven.galley.model.SimpleLocation;

public abstract class AbstractDepgraphGoal
    implements Mojo
{

    private static final String WORKSPACE_ID = "betterdep";

    @Parameter( defaultValue = "${reactorProjects}", readonly = true, required = true )
    private List<MavenProject> projects;

    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    protected MavenProject project;

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

    protected static Cartographer carto;

    public AbstractDepgraphGoal()
    {
        super();
    }

    protected void initDepgraph()
        throws MojoExecutionException
    {
        if ( carto == null )
        {
            startCartographer();
        }

        final List<ProjectRelationship<?>> rels = new ArrayList<ProjectRelationship<?>>();
        roots = new LinkedHashSet<ProjectVersionRef>();

        for ( final MavenProject project : projects )
        {
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

                rels.add( new DependencyRelationship( localUri, projectRef, new ArtifactRef( depRef, dep.getType(), dep.getClassifier(),
                                                                                             dep.isOptional() ),
                                                      DependencyScope.getScope( dep.getScope() ), index, false,
                                                      excludes.toArray( new ProjectRef[excludes.size()] ) ) );
            }

            ProjectVersionRef lastParent = projectRef;
            MavenProject parent = project.getParent();
            while ( parent != null )
            {
                final ProjectVersionRef parentRef = new ProjectVersionRef( parent.getGroupId(), parent.getArtifactId(), parent.getVersion() );

                rels.add( new ParentRelationship( localUri, projectRef, parentRef ) );

                lastParent = parentRef;
                parent = parent.getParent();
            }

            rels.add( new ParentRelationship( localUri, lastParent ) );
        }

        getLog().info( "Storing direct relationships for: " + project.getId() + "..." );
        try
        {
            carto.getDatabase()
                 .storeRelationships( rels );
        }
        catch ( final CartoDataException e )
        {
            throw new MojoExecutionException( "Failed to store direct project relationships in depgraph database: " + e.getMessage(), e );
        }

        //        final ProjectVersionRef projectRef = new ProjectVersionRef( project.getGroupId(), project.getArtifactId(), project.getVersion() );

        //        filter = new MavenRuntimeFilter();
        filter = new DependencyFilter( scope );

        final GraphDescription graphDesc = new GraphDescription( filter, roots );
        final GraphComposition comp = new GraphComposition( null, Collections.singletonList( graphDesc ) );
        final ResolverRecipe recipe = new ResolverRecipe();
        recipe.setGraphComposition( comp );
        recipe.setResolve( true );
        recipe.setWorkspaceId( WORKSPACE_ID );
        recipe.setSourceLocation( new SimpleLocation( MavenLocationExpander.EXPANSION_TARGET ) );

        getLog().info( "Resolving depgraph for: " + project.getId() + "..." );
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
        labels.put( "LOCAL", roots );

        labels.put( "NOT-RESOLVED", carto.getDatabase()
                                         .getAllIncompleteSubgraphs() );

        labels.put( "VARIABLE", carto.getDatabase()
                                     .getAllVariableSubgraphs() );

        return labels;
    }

    private void startCartographer()
        throws MojoExecutionException
    {
        getLog().info( "Starting cartographer..." );
        try
        {
            resolverDir.mkdirs();
            dbDir.mkdirs();

            /* @formatter:off */
            // TODO: Create a proper cache provider that works with the maven local repository format.
            
            final MavenLocationExpander mavenLocations = new MavenLocationExpander( projects, session.getLocalRepository() );
            
            carto =
                new CartographerBuilder( WORKSPACE_ID, resolverDir, 4, new FileNeo4jWorkspaceFactory( dbDir, true ) )
                                            .withLocationExpander( mavenLocations )
                                            .withSourceManager( mavenLocations )
                                            .withDefaultTransports()
                                            .build();
            /* @formatter:on */

            carto.getDatabase()
                 .setCurrentWorkspace( WORKSPACE_ID );
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