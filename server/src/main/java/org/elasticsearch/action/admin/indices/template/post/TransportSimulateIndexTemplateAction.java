/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.indices.template.post;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeReadAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.AliasMetadata;
import org.elasticsearch.cluster.metadata.AliasValidator;
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.MetadataCreateIndexService;
import org.elasticsearch.cluster.metadata.MetadataIndexTemplateService;
import org.elasticsearch.cluster.metadata.Template;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.shard.IndexSettingProvider;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.SystemIndices;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xcontent.NamedXContentRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.findConflictingV1Templates;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.findConflictingV2Templates;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.findV2Template;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.resolveSettings;

public class TransportSimulateIndexTemplateAction extends TransportMasterNodeReadAction<
    SimulateIndexTemplateRequest,
    SimulateIndexTemplateResponse> {

    private final MetadataIndexTemplateService indexTemplateService;
    private final NamedXContentRegistry xContentRegistry;
    private final IndicesService indicesService;
    private final AliasValidator aliasValidator;
    private final SystemIndices systemIndices;
    private final Set<IndexSettingProvider> indexSettingProviders;

    @Inject
    public TransportSimulateIndexTemplateAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        MetadataIndexTemplateService indexTemplateService,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        NamedXContentRegistry xContentRegistry,
        IndicesService indicesService,
        SystemIndices systemIndices,
        MetadataCreateIndexService metadataCreateIndexService
    ) {
        super(
            SimulateIndexTemplateAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            SimulateIndexTemplateRequest::new,
            indexNameExpressionResolver,
            SimulateIndexTemplateResponse::new,
            ThreadPool.Names.SAME
        );
        this.indexTemplateService = indexTemplateService;
        this.xContentRegistry = xContentRegistry;
        this.indicesService = indicesService;
        this.aliasValidator = new AliasValidator();
        this.systemIndices = systemIndices;
        this.indexSettingProviders = metadataCreateIndexService.getIndexSettingProviders();
    }

    @Override
    protected void masterOperation(
        Task task,
        SimulateIndexTemplateRequest request,
        ClusterState state,
        ActionListener<SimulateIndexTemplateResponse> listener
    ) throws Exception {
        final ClusterState stateWithTemplate;
        if (request.getIndexTemplateRequest() != null) {
            // we'll "locally" add the template defined by the user in the cluster state (as if it existed in the system)
            String simulateTemplateToAdd = "simulate_index_template_" + UUIDs.randomBase64UUID().toLowerCase(Locale.ROOT);
            // Perform validation for things like typos in component template names
            MetadataIndexTemplateService.validateV2TemplateRequest(
                state.metadata(),
                simulateTemplateToAdd,
                request.getIndexTemplateRequest().indexTemplate()
            );
            stateWithTemplate = indexTemplateService.addIndexTemplateV2(
                state,
                request.getIndexTemplateRequest().create(),
                simulateTemplateToAdd,
                request.getIndexTemplateRequest().indexTemplate()
            );
        } else {
            stateWithTemplate = state;
        }

        String matchingTemplate = findV2Template(stateWithTemplate.metadata(), request.getIndexName(), false);
        if (matchingTemplate == null) {
            listener.onResponse(new SimulateIndexTemplateResponse(null, null));
            return;
        }

        final ClusterState tempClusterState = resolveTemporaryState(matchingTemplate, request.getIndexName(), stateWithTemplate);
        ComposableIndexTemplate templateV2 = tempClusterState.metadata().templatesV2().get(matchingTemplate);
        assert templateV2 != null : "the matched template must exist";

        final Template template = resolveTemplate(
            matchingTemplate,
            request.getIndexName(),
            stateWithTemplate,
            xContentRegistry,
            indicesService,
            aliasValidator,
            systemIndices,
            indexSettingProviders
        );

        final Map<String, List<String>> overlapping = new HashMap<>();
        overlapping.putAll(findConflictingV1Templates(tempClusterState, matchingTemplate, templateV2.indexPatterns()));
        overlapping.putAll(findConflictingV2Templates(tempClusterState, matchingTemplate, templateV2.indexPatterns()));

        listener.onResponse(new SimulateIndexTemplateResponse(template, overlapping));
    }

    @Override
    protected ClusterBlockException checkBlock(SimulateIndexTemplateRequest request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_READ);
    }

    /**
     * Return a temporary cluster state with an index that exists using the
     * matched template's settings
     */
    public static ClusterState resolveTemporaryState(
        final String matchingTemplate,
        final String indexName,
        final ClusterState simulatedState
    ) {
        Settings settings = resolveSettings(simulatedState.metadata(), matchingTemplate);

        // create the index with dummy settings in the cluster state so we can parse and validate the aliases
        Settings dummySettings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(settings)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_INDEX_UUID, UUIDs.randomBase64UUID())
            .build();
        final IndexMetadata indexMetadata = IndexMetadata.builder(indexName).settings(dummySettings).build();

        return ClusterState.builder(simulatedState)
            .metadata(Metadata.builder(simulatedState.metadata()).put(indexMetadata, true).build())
            .build();
    }

    /**
     * Take a template and index name as well as state where the template exists, and return a final
     * {@link Template} that represents all the resolved Settings, Mappings, and Aliases
     */
    public static Template resolveTemplate(
        final String matchingTemplate,
        final String indexName,
        final ClusterState simulatedState,
        final NamedXContentRegistry xContentRegistry,
        final IndicesService indicesService,
        final AliasValidator aliasValidator,
        final SystemIndices systemIndices,
        Set<IndexSettingProvider> indexSettingProviders
    ) throws Exception {
        Settings settings = resolveSettings(simulatedState.metadata(), matchingTemplate);

        List<Map<String, AliasMetadata>> resolvedAliases = MetadataIndexTemplateService.resolveAliases(
            simulatedState.metadata(),
            matchingTemplate
        );

        ComposableIndexTemplate template = simulatedState.metadata().templatesV2().get(matchingTemplate);
        // create the index with dummy settings in the cluster state so we can parse and validate the aliases
        Settings.Builder dummySettings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_INDEX_UUID, UUIDs.randomBase64UUID());

        // First apply settings sourced from index settings providers
        for (var provider : indexSettingProviders) {
            dummySettings.put(
                provider.getAdditionalIndexSettings(
                    indexName,
                    template.getDataStreamTemplate() != null ? indexName : null,
                    true,
                    System.currentTimeMillis(),
                    settings
                )
            );
        }
        // Then apply settings resolved from templates:
        dummySettings.put(settings);

        final IndexMetadata indexMetadata = IndexMetadata.builder(indexName).settings(dummySettings).build();

        final ClusterState tempClusterState = ClusterState.builder(simulatedState)
            .metadata(Metadata.builder(simulatedState.metadata()).put(indexMetadata, true).build())
            .build();

        List<AliasMetadata> aliases = indicesService.withTempIndexService(
            indexMetadata,
            tempIndexService -> MetadataCreateIndexService.resolveAndValidateAliases(
                indexName,
                Set.of(),
                resolvedAliases,
                tempClusterState.metadata(),
                aliasValidator,
                xContentRegistry,
                // the context is only used for validation so it's fine to pass fake values for the
                // shard id and the current timestamp
                tempIndexService.newSearchExecutionContext(0, 0, null, () -> 0L, null, emptyMap()),
                tempIndexService.dateMathExpressionResolverAt(),
                systemIndices::isSystemName
            )
        );

        Map<String, AliasMetadata> aliasesByName = aliases.stream().collect(Collectors.toMap(AliasMetadata::getAlias, Function.identity()));

        // empty request mapping as the user can't specify any explicit mappings via the simulate api
        List<Map<String, Object>> mappings = MetadataCreateIndexService.collectV2Mappings(
            "{}",
            simulatedState,
            matchingTemplate,
            xContentRegistry,
            indexName
        );

        CompressedXContent mergedMapping = indicesService.<CompressedXContent, Exception>withTempIndexService(
            indexMetadata,
            tempIndexService -> {
                MapperService mapperService = tempIndexService.mapperService();
                for (Map<String, Object> mapping : mappings) {
                    if (mapping.isEmpty() == false) {
                        mapperService.merge(MapperService.SINGLE_MAPPING_NAME, mapping, MapperService.MergeReason.INDEX_TEMPLATE);
                    }
                }

                DocumentMapper documentMapper = mapperService.documentMapper();
                return documentMapper != null ? documentMapper.mappingSource() : null;
            }
        );

        return new Template(settings, mergedMapping, aliasesByName);
    }
}
