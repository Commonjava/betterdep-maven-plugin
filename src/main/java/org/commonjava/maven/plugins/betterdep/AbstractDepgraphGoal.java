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

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Profile;
import org.apache.maven.monitor.logging.DefaultLog;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.commonjava.cartographer.CartoDataException;
import org.commonjava.cartographer.Cartographer;
import org.commonjava.cartographer.CartographerCoreBuilder;
import org.commonjava.cartographer.graph.discover.DiscoveryConfig;
import org.commonjava.cartographer.graph.discover.DiscoveryResult;
import org.commonjava.cartographer.graph.discover.patch.DepgraphPatcher;
import org.commonjava.cartographer.graph.preset.CommonPresetParameters;
import org.commonjava.cartographer.graph.preset.PresetSelector;
import org.commonjava.cartographer.request.GraphComposition;
import org.commonjava.cartographer.request.GraphDescription;
import org.commonjava.cartographer.spi.graph.discover.ProjectRelationshipDiscoverer;
import org.commonjava.maven.atlas.graph.RelationshipGraph;
import org.commonjava.maven.atlas.graph.RelationshipGraphException;
import org.commonjava.maven.atlas.graph.RelationshipGraphFactory;
import org.commonjava.maven.atlas.graph.ViewParams;
import org.commonjava.maven.atlas.graph.filter.ProjectRelationshipFilter;
import org.commonjava.maven.atlas.graph.mutate.ManagedDependencyMutator;
import org.commonjava.maven.atlas.graph.rel.BomRelationship;
import org.commonjava.maven.atlas.graph.rel.DependencyRelationship;
import org.commonjava.maven.atlas.graph.rel.ParentRelationship;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.graph.rel.SimpleBomRelationship;
import org.commonjava.maven.atlas.graph.rel.SimpleDependencyRelationship;
import org.commonjava.maven.atlas.graph.rel.SimpleParentRelationship;
import org.commonjava.maven.atlas.graph.spi.RelationshipGraphConnectionFactory;
import org.commonjava.maven.atlas.graph.spi.neo4j.FileNeo4jConnectionFactory;
import org.commonjava.maven.atlas.graph.util.RelationshipUtils;
import org.commonjava.maven.atlas.ident.DependencyScope;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.ref.SimpleArtifactRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectVersionRef;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.SimpleLocation;
import org.commonjava.maven.plugins.betterdep.impl.MavenLocationExpander;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
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

import static org.apache.commons.lang.StringUtils.join;
import static org.commonjava.maven.atlas.ident.util.IdentityUtils.projectVersion;

/**
 * Abstract goal that takes care of setting up {@link Cartographer} and associated
 * instances/configuration, plus resolving dependency graphs, etc. Basic infrastructure
 * for all betterdep goals.
 * 
 * @author jdcasey
 */
public abstract class AbstractDepgraphGoal
    implements Mojo
{

    public static final String WORKSPACE_ID = "betterdep";

    public static final String MUTATOR = "managed-dependency";

    /**
     * Write generated output to this file. Usually optional (except for 'repozip' 
     * goal, where it will default to 'target/repo.zip').
     */
    @Parameter( property = "output" )
    protected File output;

    /**
     * List of projects currently being built by Maven. This list is optional.
     * 
     * If present, these projects will form the roots for the resolved dependency graph.
     * If empty, the -Dfrom=GAV[,GAV]* ({@link AbstractDepgraphGoal#fromProjects}) 
     * parameter will furnish the dependency graph roots instead.
     */
    @Parameter( defaultValue = "${reactorProjects}", readonly = true )
    private List<MavenProject> projects;

    /**
     * Use these GAVs as the roots of the dependency graph. If unspecified, and
     * this goal is run in the presence of actual on-disk projects, those will 
     * be used as the graph roots.
     */
    @Parameter( property = "from" )
    private String fromProjects;

    /**
     * Specify a list of URLs from which to graph the dependency graph and any
     * artifacts needed to generate the goal's output.
     */
    @Parameter( property = "in" )
    private String inRepos;

    /**
     * Temporary directory that will hold resolved POMs and other artifacts during
     * graph resolution and other activities.
     */
    // FIXME Explicit use of 'target/' is bad, but without a project available ${project.build.directory} doesn't graph.
    @Parameter( defaultValue = "target/dep/resolved", readonly = true, required = true )
    private File resolverDir;

    /**
     * Temporary directory used to store the resolved dependency graph. This can
     * speed up successive calls to betterdep goals if it is not erased between
     * invocations.
     */
    // FIXME Explicit use of 'target/' is bad, but without a project available ${project.build.directory} doesn't graph.
    @Parameter( defaultValue = "target/dep/db", readonly = true, required = true )
    private File dbDir;

    private Log log;

    /**
     * Scope for inclusion in the dependency graph.
     */
    @Parameter( defaultValue = "runtime", required = true, property = "scope" )
    protected DependencyScope scope;

    /**
     * Whether to include managed dependencies in the output (other than BOMs, 
     * which are almost always included).
     */
    @Parameter( defaultValue = "false", property = "managed" )
    protected boolean includeManaged;

    /**
     * Preset filter type to use when resolving the dependency graph and generating
     * output. Not normally used.
     */
    @Parameter( defaultValue = "betterdep", property = "preset" )
    protected String preset;

    @Parameter( defaultValue = "${session}", readonly = true, required = true )
    private MavenSession session;

    /**
     * Whether to provide verbose output related to dependency graph resolution.
     */
    @Parameter( defaultValue = "false", property = "trace" )
    protected boolean trace;

    protected Set<ProjectVersionRef> roots;

    protected ProjectRelationshipFilter filter;

    private Set<URI> profiles;

    protected RelationshipGraph graph;

    private Set<ProjectRelationship<?, ?>> rootRels;

    private ProjectRelationshipDiscoverer discoverer;

    private List<Location> customLocations;

    private List<ArtifactRepository> artifactRepositories;

    protected static CartographerCoreBuilder cartoBuilder;

    protected static Cartographer carto;

    protected static PresetSelector presets;

    private RelationshipGraphFactory graphFactory;

    public AbstractDepgraphGoal()
    {
        super();
    }

    protected void initDepgraph( final boolean useLocalRepo )
        throws MojoExecutionException
    {
        rootRels = new HashSet<ProjectRelationship<?, ?>>();
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

        final Map<String, Object> presetParams = new HashMap<String, Object>();
        presetParams.put( CommonPresetParameters.SCOPE, scope );
        presetParams.put( CommonPresetParameters.MANAGED, Boolean.valueOf( includeManaged ) );

        filter = presets.getPresetFilter( preset, "betterdep", presetParams );

        if ( fromProjects != null )
        {
            roots = toRefs( fromProjects );
            setupGraph();
            readFromGAVs();
        }
        else
        {
            roots = new LinkedHashSet<ProjectVersionRef>();
            readFromReactorProjects();
            setupGraph();
        }

        getLog().info( "Got relationships:\n\n  " + join( rootRels, "\n  " ) + "\n" );

        if ( profiles != null && !profiles.isEmpty() )
        {
            getLog().info( "Activating pom locations:\n\n  " + join( profiles, "\n  " ) + "\n" );

            graph.getParams()
                 .addActivePomLocations( profiles );
        }

        storeRels( rootRels );
    }

    private void setupGraph()
            throws MojoExecutionException
    {
        try
        {
            graph = graphFactory.open( new ViewParams( WORKSPACE_ID, filter, ManagedDependencyMutator.INSTANCE, roots ), true );
        }
        catch ( RelationshipGraphException e )
        {
            throw new MojoExecutionException( "Failed to open base graph: " + e.getMessage(), e );
        }
    }

    protected void storeRels( final Set<ProjectRelationship<?, ?>> rels )
        throws MojoExecutionException
    {
        getLog().info( "Storing direct relationships..." );
        try
        {
            final Set<ProjectRelationship<?, ?>> rejected = graph.storeRelationships( rels );

            getLog().info( "The following direct relationships were rejected:\n\n  " + join( rejected, "\n  " )
                               + "\n\n(" + ( rels.size() - rejected.size() ) + " were accepted)" );
        }
        catch ( final RelationshipGraphException e )
        {
            throw new MojoExecutionException( "Failed to store direct project relationships in depgraph database: "
                + e.getMessage(), e );
        }
    }

    protected Writer getWriter()
        throws MojoExecutionException
    {
        if ( output == null )
        {
            throw new MojoExecutionException( "No output file specified. Cannot open output-file writer!" );
        }

        output.getParentFile()
              .mkdirs();
        try
        {
            return new FileWriter( output );
        }
        catch ( final IOException e )
        {
            throw new MojoExecutionException( "Failed to open output file: " + e.getMessage(), e );
        }
    }

    protected void write( final CharSequence cs )
        throws MojoExecutionException
    {
        if ( output == null )
        {
            getLog().info( cs.toString() );
        }
        else
        {
            try
            {
                FileUtils.write( output, cs.toString() );
            }
            catch ( final IOException e )
            {
                throw new MojoExecutionException( "Failed to write output to file: " + output + ". Reason: "
                    + e.getMessage(), e );
            }
        }
    }

    protected Set<ProjectVersionRef> toRefs( final String gavs )
    {
        final String[] rawGavs = gavs.split( "\\s*,\\s*" );
        final Set<ProjectVersionRef> refs = new HashSet<ProjectVersionRef>( rawGavs.length );
        for ( final String rawGav : rawGavs )
        {
            refs.add( projectVersion( rawGav ) );
        }

        return refs;
    }

    protected void readFromGAVs()
        throws MojoExecutionException
    {
        getLog().info( "Initializing direct graph relationships from projects: " + fromProjects );
        rootRels = getDirectRelsFor( roots );
    }

    protected Set<ProjectRelationship<?, ?>> getDirectRelsFor( final Set<ProjectVersionRef> refs )
        throws MojoExecutionException
    {
        final Collection<DepgraphPatcher> patchers = cartoBuilder.getDepgraphPatchers();
        final Set<String> patcherIds = new HashSet<String>();
        if ( patchers != null )
        {
            for ( final DepgraphPatcher depgraphPatcher : patchers )
            {
                patcherIds.add( depgraphPatcher.getId() );
            }
        }

        final DiscoveryConfig config;
        try
        {
            config = new DiscoveryConfig( MavenLocationExpander.EXPANSION_TARGET );
            config.setEnabledPatchers( patcherIds );
            config.setStoreRelationships( true );
        }
        catch ( final URISyntaxException e )
        {
            throw new MojoExecutionException( "Cannot configure discovery for: " + refs
                + ". Try -X for more information." );
        }

        final Set<ProjectRelationship<?, ?>> rels = new HashSet<ProjectRelationship<?, ?>>();

        for ( final ProjectVersionRef projectRef : refs )
        {
            if ( discoverer == null )
            {
                discoverer = cartoBuilder.getDiscoverer();
            }

            DiscoveryResult result;
            try
            {
                result = discoverer.discoverRelationships( projectRef, graph, config );
            }
            catch ( final CartoDataException e )
            {
                throw new MojoExecutionException( "Cannot discover direct relationships for: " + projectRef + ": "
                    + e.getMessage(), e );
            }

            if ( result == null )
            {
                throw new MojoExecutionException( "Cannot discover direct relationships for: " + projectRef
                    + ". Try -X for more information." );
            }

            for ( final ProjectRelationship<?, ?> rel : result.getAcceptedRelationships() )
            {
                if ( filter.accept( rel ) )
                {
                    rels.add( rel );
                }
            }
        }

        return rels;
    }

    protected void readFromReactorProjects()
        throws MojoExecutionException
    {
        getLog().info( "Initializing direct graph relationships from reactor projects: " + projects );
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

            final ProjectVersionRef projectRef =
                new SimpleProjectVersionRef( project.getGroupId(), project.getArtifactId(), project.getVersion() );
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
                throw new MojoExecutionException(
                                                  "Failed to construct dummy local URI to use as the source of the current project in the depgraph: "
                                                      + e.getMessage(), e );
            }

            final int index = 0;
            for ( final Dependency dep : deps )
            {
                final ProjectVersionRef depRef =
                    new SimpleProjectVersionRef( dep.getGroupId(), dep.getArtifactId(), dep.getVersion() );

                //                roots.add( depRef );

                final List<Exclusion> exclusions = dep.getExclusions();
                final List<ProjectRef> excludes = new ArrayList<ProjectRef>();
                if ( exclusions != null && !exclusions.isEmpty() )
                {
                    for ( final Exclusion exclusion : exclusions )
                    {
                        excludes.add( new SimpleProjectRef( exclusion.getGroupId(), exclusion.getArtifactId() ) );
                    }
                }

                rootRels.add( new SimpleDependencyRelationship( localUri, projectRef, new SimpleArtifactRef( depRef, dep.getType(),
                                                                                                 dep.getClassifier(),
                                                                                                 dep.isOptional() ),
                                                          DependencyScope.getScope( dep.getScope() ), index, false,
                                                          excludes.toArray( new ProjectRef[excludes.size()] ) ) );
            }

            final DependencyManagement dm = project.getDependencyManagement();
            if ( dm != null )
            {
                final List<Dependency> managed = dm.getDependencies();
                int i = 0;
                for ( final Dependency dep : managed )
                {
                    if ( "pom".equals( dep.getType() ) && "import".equals( dep.getScope() ) )
                    {
                        final ProjectVersionRef depRef =
                            new SimpleProjectVersionRef( dep.getGroupId(), dep.getArtifactId(), dep.getVersion() );
                        rootRels.add( new SimpleBomRelationship( localUri, projectRef, depRef, i++ ) );
                    }
                }
            }

            ProjectVersionRef lastParent = projectRef;
            MavenProject parent = project.getParent();
            while ( parent != null )
            {
                final ProjectVersionRef parentRef =
                    new SimpleProjectVersionRef( parent.getGroupId(), parent.getArtifactId(), parent.getVersion() );

                rootRels.add( new SimpleParentRelationship( localUri, projectRef, parentRef ) );

                lastParent = parentRef;
                parent = parent.getParent();
            }

            rootRels.add( new SimpleParentRelationship( lastParent ) );
        }

    }

    protected GraphComposition getComposition()
    {
        return getComposition( filter, roots );
    }

    protected GraphComposition getComposition( ProjectRelationshipFilter filter, Set<ProjectVersionRef> roots )
    {
        final GraphDescription graphDesc = new GraphDescription( filter, MUTATOR, roots );
        return new GraphComposition( null, Collections.singletonList( graphDesc ) );
    }

    protected Map<String, Set<ProjectVersionRef>> getLabelsMap()
        throws CartoDataException
    {
        final Map<String, Set<ProjectVersionRef>> labels = new HashMap<String, Set<ProjectVersionRef>>();
        labels.put( "ROOT", roots );

        labels.put( "NOT-RESOLVED", graph.getAllIncompleteSubgraphs() );

        labels.put( "VARIABLE", graph.getAllVariableSubgraphs() );

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

//            cartoBuilder = new CartographerBuilder( WORKSPACE_ID, resolverDir, 4, new JungWorkspaceFactory() )
            RelationshipGraphConnectionFactory connFactory = new FileNeo4jConnectionFactory( dbDir, true );
            graphFactory = new RelationshipGraphFactory( connFactory );

            cartoBuilder = new CartographerCoreBuilder( resolverDir, connFactory )
                    .withGraphFactory( graphFactory )
                                .withLocationExpander( mavenLocations )
                                .withSourceManager( mavenLocations )
                                .withDefaultTransports();

            carto = cartoBuilder.build();
            /* @formatter:on */

            presets = new PresetSelector();
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
