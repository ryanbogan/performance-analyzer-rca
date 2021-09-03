/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.opensearch.performanceanalyzer.rca.store.rca.admisioncontrol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import org.opensearch.performanceanalyzer.metrics.AllMetrics;
import org.opensearch.performanceanalyzer.metricsdb.MetricsDB;
import org.opensearch.performanceanalyzer.rca.framework.api.Metric;
import org.opensearch.performanceanalyzer.rca.framework.api.contexts.ResourceContext;
import org.opensearch.performanceanalyzer.rca.framework.api.flow_units.MetricFlowUnit;
import org.opensearch.performanceanalyzer.rca.framework.api.flow_units.ResourceFlowUnit;
import org.opensearch.performanceanalyzer.rca.framework.api.metrics.MetricTestHelper;
import org.opensearch.performanceanalyzer.rca.framework.api.summaries.HotNodeSummary;
import org.opensearch.performanceanalyzer.rca.store.rca.admissioncontrol.AdmissionControlRca;
import org.opensearch.performanceanalyzer.util.range.Range;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public class AdmissionControlRcaTest {

    @Mock private Metric mockHeapUsedValue;
    @Mock private Metric mockHeapMaxValue;

    private static final int PERIOD = 5;
    private AdmissionControlRca rca;
    private MetricTestHelper metricTestHelper;

    private final List<String> heapTableColumns =
        Arrays.asList(
            AllMetrics.HeapDimension.MEM_TYPE.toString(),
            MetricsDB.SUM,
            MetricsDB.AVG,
            MetricsDB.MIN,
            MetricsDB.MAX);

    @Before
    public void setup() throws Exception {
        initMocks(this);
        this.metricTestHelper = new MetricTestHelper(PERIOD);
        this.rca = new AdmissionControlRca(PERIOD, mockHeapUsedValue, mockHeapMaxValue);
    }

    @Test
    public void testAdmissionControlRcaRangeChange() {
        setupMockHeapMetric(mockHeapMaxValue, 100);
        setupMockHeapMetric(mockHeapUsedValue, 70);
        IntStream.range(0, PERIOD - 1).forEach(i -> rca.operate());

        setupMockHeapMetric(mockHeapUsedValue, 80);
        ResourceFlowUnit<HotNodeSummary> flowUnit = rca.operate();

        assertFalse(flowUnit.isEmpty());
        ResourceContext context = flowUnit.getResourceContext();
        assertTrue(context.isUnhealthy());
    }

    @Test
    public void testAdmissionControlRcaNoRangeChange() {
        setupMockHeapMetric(mockHeapMaxValue, 100);
        setupMockHeapMetric(mockHeapUsedValue, 70);
        IntStream.range(0, PERIOD).forEach(i -> rca.operate());

        setupMockHeapMetric(mockHeapUsedValue, 74);
        ResourceFlowUnit<HotNodeSummary> flowUnit = rca.operate();

        assertFalse(flowUnit.isEmpty());
        ResourceContext context = flowUnit.getResourceContext();
        assertTrue(context.isHealthy());
    }

    @Test
    public void testAdmissionControlRcaRangeGapConfigured() {
        rca.getRequestSizeHeapRange()
            .setRangeConfiguration(
                Arrays.asList(
                    new Range(0, 75, 15),
                    // Simulating configuration gap from 75% to 85%
                    new Range(85, 100, 10)));

        setupMockHeapMetric(mockHeapMaxValue, 100);
        setupMockHeapMetric(mockHeapUsedValue, 70);
        IntStream.range(0, PERIOD).forEach(i -> rca.operate());

        setupMockHeapMetric(mockHeapUsedValue, 80);
        ResourceFlowUnit<HotNodeSummary> flowUnit = rca.operate();

        assertFalse(flowUnit.isEmpty());
        ResourceContext context = flowUnit.getResourceContext();
        assertTrue(context.isHealthy());
    }

    @Test
    public void testAdmissionControlRcaInvalidMaxHeap() {
        setupMockHeapMetric(mockHeapMaxValue, 0);
        setupMockHeapMetric(mockHeapUsedValue, 0);
        IntStream.range(0, PERIOD).forEach(i -> rca.operate());
        ResourceFlowUnit<HotNodeSummary> flowUnit = rca.operate();

        assertFalse(flowUnit.isEmpty());
        ResourceContext context = flowUnit.getResourceContext();
        assertTrue(context.isHealthy());
    }

    private void setupMockHeapMetric(final Metric metric, final double value) {
        String valueString = Double.toString(value);
        List<String> data =
            Arrays.asList(
                AllMetrics.GCType.HEAP.toString(),
                valueString,
                valueString,
                valueString,
                valueString);
        when(metric.getFlowUnits())
            .thenReturn(
                Collections.singletonList(
                    new MetricFlowUnit(
                        0,
                        metricTestHelper.createTestResult(
                            heapTableColumns, data))));
    }
}
