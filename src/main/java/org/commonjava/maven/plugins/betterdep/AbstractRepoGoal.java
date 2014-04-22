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

import static org.apache.commons.lang.StringUtils.isEmpty;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
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

/**
 * Abstract goal that takes care of resolving repository contents given a list of
 * root GAVs and a filter (among other options). The logic here builds on the
 * {@link AbstractDepgraphGoal}'s process for resolving the dependency graph itself.
 * 
 * @author jdcasey
 */
public abstract class AbstractRepoGoal
    extends AbstractDepgraphGoal
{

    private static final Set<String> DEFAULT_METAS;

    private static final Set<ExtraCT> DEFAULT_EXTRAS;

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

    /**
     * Comma-delimited list of meta-file extensions to look for and include. If
     * not specified, this will include:
     * 
     * <ul>
     *   <li>sha1</li>
     *   <li>md5</li>
     *   <li>asc</li>
     *   <li>asc.sha1</li>
     *   <li>asc.md5</li>
     * </ul>
     */
    @Parameter( property = "metas" )
    private String metas;

    /**
     * Comma-delimited list of 'type[:classifier]' specs for extra attached 
     * artifacts to include. If not specified, this list will include 'javadoc:jar' 
     * and 'sources:jar'.
     * 
     * <p><b>NOTE:</b> It's also possible to use -Dextras=*:*.</p>  
     */
    @Parameter( property = "extras" )
    private String extras;

    protected Map<ProjectVersionRef, Map<ArtifactRef, ConcreteResource>> resolveRepoContents()
        throws MojoExecutionException
    {
        initDepgraph( false );
        final RepositoryContentRecipe recipe = new RepositoryContentRecipe();

        // TODO: What about more complex graph compositions, like subtractions or unions with different filters?
        recipe.setGraphComposition( new GraphComposition( null, Collections.singletonList( new GraphDescription( filter, roots ) ) ) );
        recipe.setMetas( getMetas() );
        recipe.setExtras( getExtras() );
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

        return contents;
    }

    private Set<ExtraCT> getExtras()
    {
        if ( extras == null )
        {
            return DEFAULT_EXTRAS;
        }

        final Set<ExtraCT> result = new HashSet<ExtraCT>();
        final String[] entries = extras.split( "\\s*,\\s*" );
        for ( final String entry : entries )
        {
            if ( entry.indexOf( ':' ) > 0 )
            {
                final String[] parts = entry.split( ":" );
                result.add( new ExtraCT( parts[0], parts[1] ) );
            }
            else
            {
                result.add( new ExtraCT( entry ) );
            }
        }

        return result;
    }

    private Set<String> getMetas()
    {
        if ( isEmpty( metas ) )
        {
            return DEFAULT_METAS;
        }

        return new HashSet<String>( Arrays.asList( metas.split( "\\s*,\\s*" ) ) );
    }

}
