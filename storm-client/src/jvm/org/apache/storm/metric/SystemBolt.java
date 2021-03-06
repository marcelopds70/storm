/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.  The ASF licenses this file to you under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package org.apache.storm.metric;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.storm.Config;
import org.apache.storm.metric.api.IMetric;
import org.apache.storm.task.IBolt;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.utils.ObjectReader;
import org.apache.storm.utils.ReflectionUtils;


// There is one task inside one executor for each worker of the topology.
// TaskID is always -1, therefore you can only send-unanchored tuples to co-located SystemBolt.
// This bolt was conceived to export worker stats via metrics api.
public class SystemBolt implements IBolt {
    private static boolean _prepareWasCalled = false;

    @SuppressWarnings({ "unchecked" })
    @Override
    public void prepare(final Map<String, Object> topoConf, TopologyContext context, OutputCollector collector) {
        if (_prepareWasCalled && !"local".equals(topoConf.get(Config.STORM_CLUSTER_MODE))) {
            throw new RuntimeException("A single worker should have 1 SystemBolt instance.");
        }
        _prepareWasCalled = true;

        int bucketSize = ObjectReader.getInt(topoConf.get(Config.TOPOLOGY_BUILTIN_METRICS_BUCKET_SIZE_SECS));

        final RuntimeMXBean jvmRT = ManagementFactory.getRuntimeMXBean();
        context.registerMetric("uptimeSecs", () -> jvmRT.getUptime() / 1000.0, bucketSize);
        context.registerMetric("startTimeSecs", () -> jvmRT.getStartTime() / 1000.0, bucketSize);

        final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        context.registerMetric("threadCount", threadBean::getThreadCount, bucketSize);

        context.registerMetric("newWorkerEvent", new IMetric() {
            boolean doEvent = true;

            @Override
            public Object getValueAndReset() {
                if (doEvent) {
                    doEvent = false;
                    return 1;
                } else {
                    return 0;
                }
            }
        }, bucketSize);

        final MemoryMXBean jvmMemRT = ManagementFactory.getMemoryMXBean();

        context.registerMetric("memory/heap", new MemoryUsageMetric(jvmMemRT::getHeapMemoryUsage), bucketSize);
        context.registerMetric("memory/nonHeap", new MemoryUsageMetric(jvmMemRT::getNonHeapMemoryUsage), bucketSize);

        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            context.registerMetric("GC/" + b.getName().replaceAll("\\W", ""), new GarbageCollectorMetric(b), bucketSize);
        }

        registerMetrics(context, (Map<String, String>) topoConf.get(Config.WORKER_METRICS), bucketSize, topoConf);
        registerMetrics(context, (Map<String, String>) topoConf.get(Config.TOPOLOGY_WORKER_METRICS), bucketSize, topoConf);
    }

    private void registerMetrics(TopologyContext context, Map<String, String> metrics, int bucketSize, Map<String, Object> conf) {
        if (metrics == null) {
            return;
        }
        for (Map.Entry<String, String> metric : metrics.entrySet()) {
            try {
                context.registerMetric(metric.getKey(), (IMetric) ReflectionUtils.newInstance(metric.getValue(), conf), bucketSize);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void execute(Tuple input) {
        throw new RuntimeException("Non-system tuples should never be sent to __system bolt.");
    }

    @Override
    public void cleanup() {
    }

    private static class MemoryUsageMetric implements IMetric {
        Supplier<MemoryUsage> _getUsage;

        public MemoryUsageMetric(Supplier<MemoryUsage> getUsage) {
            _getUsage = getUsage;
        }

        @Override
        public Object getValueAndReset() {
            MemoryUsage memUsage = _getUsage.get();
            HashMap<String, Object> m = new HashMap<>();
            m.put("maxBytes", memUsage.getMax());
            m.put("committedBytes", memUsage.getCommitted());
            m.put("initBytes", memUsage.getInit());
            m.put("usedBytes", memUsage.getUsed());
            m.put("virtualFreeBytes", memUsage.getMax() - memUsage.getUsed());
            m.put("unusedBytes", memUsage.getCommitted() - memUsage.getUsed());
            return m;
        }
    }

    // canonically the metrics data exported is time bucketed when doing counts.
    // convert the absolute values here into time buckets.
    private static class GarbageCollectorMetric implements IMetric {
        GarbageCollectorMXBean _gcBean;
        Long _collectionCount;
        Long _collectionTime;

        public GarbageCollectorMetric(GarbageCollectorMXBean gcBean) {
            _gcBean = gcBean;
        }

        @Override
        public Object getValueAndReset() {
            Long collectionCountP = _gcBean.getCollectionCount();
            Long collectionTimeP = _gcBean.getCollectionTime();

            Map<String, Object> ret = null;
            if (_collectionCount != null && _collectionTime != null) {
                ret = new HashMap<>();
                ret.put("count", collectionCountP - _collectionCount);
                ret.put("timeMs", collectionTimeP - _collectionTime);
            }

            _collectionCount = collectionCountP;
            _collectionTime = collectionTimeP;
            return ret;
        }
    }
}
