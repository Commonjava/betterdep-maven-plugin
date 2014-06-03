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

import java.util.Map;

import org.commonjava.atservice.annotation.Service;
import org.commonjava.maven.atlas.graph.filter.ProjectRelationshipFilter;
import org.commonjava.maven.atlas.ident.DependencyScope;
import org.commonjava.maven.cartographer.preset.CommonPresetParameters;
import org.commonjava.maven.cartographer.preset.PresetFactory;
import org.commonjava.maven.cartographer.preset.PresetSelector;

/**
 * Filter factory used to integrate the {@link BetterDepFilter} into the 
 * {@link PresetSelector} component used to translate -Dpreset=betterdep into
 * a filter instance with appropriate parameters set.
 * 
 * @author jdcasey
 */
@Service( PresetFactory.class )
public class BetterDepFilterFactory
    implements PresetFactory
{

    private static final String[] IDS = { "betterdep-scope", "betterdep" };

    @Override
    public String[] getPresetIds()
    {
        return IDS;
    }

    @Override
    public ProjectRelationshipFilter newFilter( final String presetId, final Map<String, Object> parameters )
    {
        return new BetterDepFilter( (DependencyScope) parameters.get( CommonPresetParameters.SCOPE ) );
    }

}
