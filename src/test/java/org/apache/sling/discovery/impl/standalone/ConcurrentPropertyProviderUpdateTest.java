/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.discovery.impl.standalone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sling.discovery.PropertyProvider;
import org.apache.sling.settings.SlingSettingsService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Constants;

/**
 * Concurrent {@code updated} callbacks for the same property provider must never leave a stale snapshot behind.
 * This mirrors Sling Event's JobConsumerManager, which calls {@code setProperties} from many threads at once.
 */
public class ConcurrentPropertyProviderUpdateTest {

    private static final String PROPERTY = "org.apache.sling.event.jobs.consumer.topics";
    private static final Class<?>[] CALLBACK = {PropertyProvider.class, Map.class};

    private final AtomicReference<String> value = new AtomicReference<>("initial");
    private final PropertyProvider provider = name -> PROPERTY.equals(name) ? value.get() : null;
    private final Map<String, Object> serviceProps = serviceProps(1L, 0);

    private NoClusterDiscoveryService service;
    private ExecutorService pool;

    @Before
    public void setUp() throws Exception {
        service = newService();
        pool = Executors.newFixedThreadPool(16);
        call("bindPropertyProvider", provider, serviceProps);
    }

    @After
    public void tearDown() {
        pool.shutdownNow();
    }

    @Test
    public void concurrentUpdatesAlwaysPublishTheLatestValue() throws Exception {
        for (int round = 0; round < 300; round++) {
            concurrentUpdates(16, round);
            final String latest = "final-" + round;
            value.set(latest);
            call("updatedPropertyProvider", provider, serviceProps);

            assertEquals("round " + round, latest, published());
            assertEquals("round " + round + ": duplicate provider entries", 1, providerInfos().size());
        }
    }

    @Test
    public void unbindAfterConcurrentUpdatesRemovesAllProperties() throws Exception {
        for (int round = 0; round < 50; round++) {
            concurrentUpdates(16, round);
        }
        call("unbindPropertyProvider", provider, serviceProps);

        assertNull(published());
        assertTrue(providerInfos().isEmpty());
    }

    @Test
    public void lateUpdateAfterUnbindDoesNotResurrectTheProvider() throws Exception {
        call("unbindPropertyProvider", provider, serviceProps);
        value.set("late");
        call("updatedPropertyProvider", provider, serviceProps);

        assertNull(published());
        assertTrue(providerInfos().isEmpty());
    }

    @Test
    public void repeatedBindOfTheSameServiceKeepsOneEntry() throws Exception {
        value.set("rebound");
        call("bindPropertyProvider", provider, serviceProps);

        assertEquals("rebound", published());
        assertEquals(1, providerInfos().size());
    }

    @Test
    public void providerInfoOrderingIsConsistentWithEquals() {
        final ProviderInfo a = new ProviderInfo(provider, serviceProps);
        final ProviderInfo b = new ProviderInfo(provider, serviceProps);

        assertEquals(0, a.compareTo(b));
        assertEquals(0, b.compareTo(a));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void higherRankingProviderStillWins() throws Exception {
        final PropertyProvider other = name -> PROPERTY.equals(name) ? "high" : null;
        call("bindPropertyProvider", other, serviceProps(2L, 10));
        value.set("low");
        call("updatedPropertyProvider", provider, serviceProps);

        assertEquals("high", published());
    }

    private void concurrentUpdates(final int threads, final int round) throws Exception {
        final CyclicBarrier barrier = new CyclicBarrier(threads);
        final List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final String own = round + "-" + t;
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                value.set(own);
                call("updatedPropertyProvider", provider, serviceProps);
                return null;
            }));
        }
        for (final Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
    }

    private String published() {
        return service.getTopology().getLocalInstance().getProperty(PROPERTY);
    }

    @SuppressWarnings("unchecked")
    private List<ProviderInfo> providerInfos() throws Exception {
        final Field field = NoClusterDiscoveryService.class.getDeclaredField("providerInfos");
        field.setAccessible(true);
        final Field lock = NoClusterDiscoveryService.class.getDeclaredField("lock");
        lock.setAccessible(true);
        synchronized (lock.get(service)) {
            return new ArrayList<>((List<ProviderInfo>) field.get(service));
        }
    }

    private void call(final String name, final PropertyProvider p, final Map<String, Object> props) throws Exception {
        final Method method = NoClusterDiscoveryService.class.getDeclaredMethod(name, CALLBACK);
        method.setAccessible(true);
        try {
            method.invoke(service, p, props);
        } catch (final InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static Map<String, Object> serviceProps(final long serviceId, final int ranking) {
        final Map<String, Object> props = new HashMap<>();
        props.put(Constants.SERVICE_ID, serviceId);
        props.put(Constants.SERVICE_RANKING, ranking);
        props.put(PropertyProvider.PROPERTY_PROPERTIES, PROPERTY);
        return props;
    }

    private static NoClusterDiscoveryService newService() {
        return new NoClusterDiscoveryService(
                new SlingSettingsService() {
                    @Override
                    public String getSlingId() {
                        return "test-instance";
                    }

                    @Override
                    public String getSlingHomePath() {
                        return null;
                    }

                    @Override
                    public URL getSlingHome() {
                        return null;
                    }

                    @Override
                    public Set<String> getRunModes() {
                        return null;
                    }

                    @Override
                    public String getAbsolutePathWithinSlingHome(final String relativePath) {
                        return null;
                    }
                },
                new Config() {
                    @Override
                    public boolean always_offline() {
                        return false;
                    }

                    @Override
                    public Class<? extends java.lang.annotation.Annotation> annotationType() {
                        return Config.class;
                    }
                });
    }
}
