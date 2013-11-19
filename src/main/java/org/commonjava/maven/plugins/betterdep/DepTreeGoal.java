package org.commonjava.maven.plugins.betterdep;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.log4j.Level;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.monitor.logging.DefaultLog;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.console.ConsoleLogger;
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
import org.commonjava.maven.cartographer.preset.MavenRuntimeFilter;
import org.commonjava.maven.galley.model.SimpleLocation;
import org.commonjava.util.logging.Log4jUtil;

@Mojo( name = "tree", requiresProject = true )
public class DepTreeGoal
    implements org.apache.maven.plugin.Mojo
{

    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    private MavenProject project;

    @Parameter( defaultValue = "${project.build.directory}/dep/resolved", readonly = true, required = true )
    private File resolverDir;

    @Parameter( defaultValue = "${project.build.directory}/dep/db", readonly = true, required = true )
    private File dbDir;

    private Log log;

    @Parameter( defaultValue = "runtime", required = true, property = "dep.scope" )
    private DependencyScope scope;

    @Parameter( defaultValue = "true", required = true, property = "dep.collapseTransitives" )
    private boolean collapseTransitives;

    @Parameter( defaultValue = "${session}", readonly = true, required = true )
    private MavenSession session;

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        Log4jUtil.configure( getLog().isDebugEnabled() ? Level.INFO : Level.WARN );

        final ProjectVersionRef projectRef = new ProjectVersionRef( project.getGroupId(), project.getArtifactId(), project.getVersion() );

        final List<Dependency> deps = project.getDependencies();
        final List<ProjectRelationship<?>> rels = new ArrayList<ProjectRelationship<?>>( deps.size() );
        final List<ProjectVersionRef> roots = new ArrayList<ProjectVersionRef>( deps.size() );

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

            roots.add( depRef );

            final List<Exclusion> exclusions = dep.getExclusions();
            final List<ProjectRef> excludes = new ArrayList<ProjectRef>();
            if ( exclusions != null && !exclusions.isEmpty() )
            {
                for ( final Exclusion exclusion : exclusions )
                {
                    excludes.add( new ProjectRef( exclusion.getGroupId(), exclusion.getArtifactId() ) );
                }
            }

            rels.add( new DependencyRelationship( localUri, projectRef,
                                                  new ArtifactRef( depRef, dep.getType(), dep.getClassifier(), dep.isOptional() ),
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

        final String wsid = project.getArtifactId();

        getLog().info( "Starting cartographer..." );
        Cartographer carto;
        try
        {
            resolverDir.mkdirs();
            dbDir.mkdirs();

            /* @formatter:off */
            // TODO: Create a proper cache provider that works with the maven local repository format.
            
            final MavenLocationExpander mavenLocations = new MavenLocationExpander( project, session.getLocalRepository() );
            
            carto =
                new CartographerBuilder( wsid, resolverDir, 10, new FileNeo4jWorkspaceFactory( dbDir, true ) )
                                            .withLocationExpander( mavenLocations )
                                            .withSourceManager( mavenLocations )
                                            .build();
            /* @formatter:on */

            carto.getDatabase()
                 .setCurrentWorkspace( wsid );
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

        final ProjectRelationshipFilter filter = new MavenRuntimeFilter();

        final GraphDescription graphDesc = new GraphDescription( filter, projectRef );
        final GraphComposition comp = new GraphComposition( null, Collections.singletonList( graphDesc ) );
        final ResolverRecipe recipe = new ResolverRecipe();
        recipe.setGraphComposition( comp );
        recipe.setResolve( true );
        recipe.setWorkspaceId( wsid );
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

        getLog().info( "Printing deptree for: " + project.getId() + "..." );
        try
        {
            final String depTree = carto.getRenderer()
                                        .depTree( projectRef, filter, scope, collapseTransitives );

            // TODO: file option
            getLog().info( depTree );
        }
        catch ( final CartoDataException e )
        {
            throw new MojoExecutionException( "Failed to render dependency tree: " + e.getMessage(), e );
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
