package org.commonjava.maven.plugins.betterdep;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Level;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.cartographer.data.CartoDataException;
import org.commonjava.util.logging.Log4jUtil;

@Mojo( name = "list", requiresProject = true, aggregator = true, threadSafe = true )
public class DepListGoal
    extends AbstractDepgraphGoal
{

    private static boolean HAS_RUN = false;

    @Parameter( property = "output" )
    private File output;

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( HAS_RUN )
        {
            getLog().info( "Dependency list has already run. Skipping." );
            return;
        }

        HAS_RUN = true;

        Log4jUtil.configure( getLog().isDebugEnabled() ? Level.INFO : Level.WARN, "%-5p [%t]: %m%n" );

        initDepgraph();
        try
        {
            final Map<String, Set<ProjectVersionRef>> labels = getLabelsMap();

            final StringBuilder sb = new StringBuilder();
            for ( final ProjectVersionRef root : roots )
            {
                final String printed = carto.getRenderer()
                                            .depList( root, filter, scope, labels );

                sb.append( "\n\n\nDependency list for: " )
                  .append( root )
                  .append( ": \n\n" )
                  .append( printed );
            }

            if ( output == null )
            {
                getLog().info( sb.toString() );
            }
            else
            {
                FileUtils.write( output, sb.toString() );
            }
        }
        catch ( final CartoDataException e )
        {
            throw new MojoExecutionException( "Failed to render dependency list: " + e.getMessage(), e );
        }
        catch ( final IOException e )
        {
            throw new MojoExecutionException( "Failed to render dependency list to: " + output + ". Reason: " + e.getMessage(), e );
        }
    }
}
