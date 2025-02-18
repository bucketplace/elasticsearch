/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.autoscaling;

import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotResponse;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.List;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.snapshots.AbstractSnapshotIntegTestCase;
import org.elasticsearch.snapshots.SnapshotInfo;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.xpack.autoscaling.action.GetAutoscalingCapacityAction;
import org.elasticsearch.xpack.autoscaling.action.PutAutoscalingPolicyAction;
import org.elasticsearch.xpack.autoscaling.capacity.AutoscalingCapacity;
import org.elasticsearch.xpack.autoscaling.shards.LocalStateAutoscalingAndSearchableSnapshots;
import org.elasticsearch.xpack.core.DataTier;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.searchablesnapshots.MountSearchableSnapshotAction;
import org.elasticsearch.xpack.core.searchablesnapshots.MountSearchableSnapshotRequest;
import org.elasticsearch.xpack.searchablesnapshots.cache.shared.FrozenCacheService;
import org.junit.Before;

import java.util.Collection;
import java.util.Locale;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.elasticsearch.index.IndexSettings.INDEX_SOFT_DELETES_SETTING;
import static org.elasticsearch.license.LicenseService.SELF_GENERATED_LICENSE_TYPE;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST)
public abstract class AbstractFrozenAutoscalingIntegTestCase extends AbstractSnapshotIntegTestCase {

    protected final String indexName = "index";
    protected final String restoredIndexName = "restored";
    protected final String fsRepoName = randomAlphaOfLength(10);
    protected final String snapshotName = randomAlphaOfLength(10).toLowerCase(Locale.ROOT);
    protected final String policyName = "frozen";

    @Override
    protected boolean forceSingleDataPath() {
        return true;
    }

    @Override
    protected boolean addMockInternalEngine() {
        return false;
    }

    @Override
    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return List.of(LocalStateAutoscalingAndSearchableSnapshots.class, getTestTransportPlugin());
    }

    @Override
    protected Settings transportClientSettings() {
        final Settings.Builder builder = Settings.builder().put(super.transportClientSettings());
        builder.put(XPackSettings.SECURITY_ENABLED.getKey(), false);
        return builder.build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return org.elasticsearch.common.collect.List.of(LocalStateAutoscalingAndSearchableSnapshots.class);
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        Settings.Builder builder = Settings.builder()
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            .put(SELF_GENERATED_LICENSE_TYPE.getKey(), "trial");
        if (DiscoveryNode.canContainData(otherSettings)) {
            builder.put(FrozenCacheService.SNAPSHOT_CACHE_SIZE_SETTING.getKey(), new ByteSizeValue(10, ByteSizeUnit.MB));
        }
        return builder.build();
    }

    @Before
    public void setupPolicyAndMountedIndex() throws Exception {
        createRepository(fsRepoName, "fs");
        putAutoscalingPolicy();
        assertAcked(prepareCreate(indexName, Settings.builder().put(INDEX_SOFT_DELETES_SETTING.getKey(), true)));

        indexRandom(
            randomBoolean(),
            IntStream.range(0, 10).mapToObj(i -> client().prepareIndex(indexName, "_doc").setSource()).collect(Collectors.toList())
        );

        final SnapshotInfo snapshotInfo = createFullSnapshot(fsRepoName, snapshotName);

        final AutoscalingCapacity.AutoscalingResources total = capacity().results().get("frozen").requiredCapacity().total();
        assertThat(total.memory(), equalTo(ByteSizeValue.ZERO));
        assertThat(total.storage(), equalTo(ByteSizeValue.ZERO));

        final MountSearchableSnapshotRequest req = new MountSearchableSnapshotRequest(
            restoredIndexName,
            fsRepoName,
            snapshotInfo.snapshotId().getName(),
            indexName,
            Settings.EMPTY,
            Strings.EMPTY_ARRAY,
            true,
            MountSearchableSnapshotRequest.Storage.SHARED_CACHE
        );
        final RestoreSnapshotResponse restoreSnapshotResponse = client().execute(MountSearchableSnapshotAction.INSTANCE, req).get();
        assertThat(restoreSnapshotResponse.getRestoreInfo().failedShards(), equalTo(0));
    }

    protected GetAutoscalingCapacityAction.Response capacity() {
        GetAutoscalingCapacityAction.Request request = new GetAutoscalingCapacityAction.Request();
        return client().execute(GetAutoscalingCapacityAction.INSTANCE, request).actionGet();
    }

    private void putAutoscalingPolicy() {
        // randomly set the setting to verify it can be set.
        final Settings settings = randomBoolean() ? Settings.EMPTY : addDeciderSettings(Settings.builder()).build();
        final PutAutoscalingPolicyAction.Request request = new PutAutoscalingPolicyAction.Request(
            policyName,
            new TreeSet<>(org.elasticsearch.common.collect.Set.of(DataTier.DATA_FROZEN)),
            new TreeMap<>(org.elasticsearch.common.collect.Map.of(deciderName(), settings))
        );
        assertAcked(client().execute(PutAutoscalingPolicyAction.INSTANCE, request).actionGet());
    }

    protected abstract String deciderName();

    protected abstract Settings.Builder addDeciderSettings(Settings.Builder builder);
}
