/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ccr.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.xpack.CcrSingleNodeTestCase;
import org.elasticsearch.xpack.core.ccr.action.FollowStatsAction;
import org.elasticsearch.xpack.core.ccr.action.PutFollowAction;

import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.singletonMap;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.xpack.ccr.LocalIndexFollowingIT.getIndexSettings;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.collection.IsEmptyCollection.empty;

/*
 * Test scope is important to ensure that other tests added to this suite do not interfere with the expectation in
 * testStatsWhenNoPersistentTasksMetaDataExists that the cluster state does not contain any persistent tasks metadata.
 */
public class FollowStatsIT extends CcrSingleNodeTestCase {

    /**
     * Previously we would throw a NullPointerException when there was no persistent tasks metadata in the cluster state. This tests
     * maintains that we do not make this mistake again.
     *
     * @throws InterruptedException if we are interrupted waiting on the latch to countdown
     */
    public void testStatsWhenNoPersistentTasksMetaDataExists() throws InterruptedException {
        final ClusterStateResponse response = client().admin().cluster().state(new ClusterStateRequest()).actionGet();
        assertNull(response.getState().metaData().custom(PersistentTasksCustomMetaData.TYPE));
        final AtomicBoolean onResponse = new AtomicBoolean();
        final CountDownLatch latch = new CountDownLatch(1);
        client().execute(
                FollowStatsAction.INSTANCE,
                new FollowStatsAction.StatsRequest(),
                new ActionListener<FollowStatsAction.StatsResponses>() {
                    @Override
                    public void onResponse(final FollowStatsAction.StatsResponses statsResponses) {
                        try {
                            assertThat(statsResponses.getTaskFailures(), empty());
                            assertThat(statsResponses.getNodeFailures(), empty());
                            onResponse.set(true);
                        } finally {
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onFailure(final Exception e) {
                        try {
                            fail(e.toString());
                        } finally {
                            latch.countDown();
                        }
                    }
                });
        latch.await();
        assertTrue(onResponse.get());
    }

    public void testFollowStatsApiFollowerIndexFiltering() throws Exception {
        final String leaderIndexSettings = getIndexSettings(1, 0,
            singletonMap(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), "true"));
        assertAcked(client().admin().indices().prepareCreate("leader1").setSource(leaderIndexSettings, XContentType.JSON));
        ensureGreen("leader1");
        assertAcked(client().admin().indices().prepareCreate("leader2").setSource(leaderIndexSettings, XContentType.JSON));
        ensureGreen("leader2");

        PutFollowAction.Request followRequest = getPutFollowRequest("leader1", "follower1");
        client().execute(PutFollowAction.INSTANCE, followRequest).get();

        followRequest = getPutFollowRequest("leader2", "follower2");
        client().execute(PutFollowAction.INSTANCE, followRequest).get();

        FollowStatsAction.StatsRequest statsRequest = new FollowStatsAction.StatsRequest();
        statsRequest.setIndices(new String[] {"follower1"});
        FollowStatsAction.StatsResponses response = client().execute(FollowStatsAction.INSTANCE, statsRequest).actionGet();
        assertThat(response.getStatsResponses().size(), equalTo(1));
        assertThat(response.getStatsResponses().get(0).status().followerIndex(), equalTo("follower1"));

        statsRequest = new FollowStatsAction.StatsRequest();
        statsRequest.setIndices(new String[] {"follower2"});
        response = client().execute(FollowStatsAction.INSTANCE, statsRequest).actionGet();
        assertThat(response.getStatsResponses().size(), equalTo(1));
        assertThat(response.getStatsResponses().get(0).status().followerIndex(), equalTo("follower2"));

        response = client().execute(FollowStatsAction.INSTANCE,  new FollowStatsAction.StatsRequest()).actionGet();
        assertThat(response.getStatsResponses().size(), equalTo(2));
        response.getStatsResponses().sort(Comparator.comparing(o -> o.status().followerIndex()));
        assertThat(response.getStatsResponses().get(0).status().followerIndex(), equalTo("follower1"));
        assertThat(response.getStatsResponses().get(1).status().followerIndex(), equalTo("follower2"));
    }

}
