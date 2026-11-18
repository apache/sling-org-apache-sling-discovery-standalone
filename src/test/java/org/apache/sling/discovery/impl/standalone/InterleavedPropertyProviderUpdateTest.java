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

import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.sling.discovery.PropertyProvider;
import org.apache.sling.settings.SlingSettingsService;
import org.junit.Test;
import org.osgi.framework.Constants;

public class InterleavedPropertyProviderUpdateTest {

    private static final String PROPERTY = "org.apache.sling.event.jobs.consumer.topics";

    private static void invoke(
            final Object target, final String name, final Class<?>[] parameterTypes, final Object... arguments)
            throws Exception {
        final Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        method.invoke(target, arguments);
    }

    @Test
    public void interleavedUpdatesRetainTheLatestPropertyValue() throws Exception {
        final NoClusterDiscoveryService service = new NoClusterDiscoveryService(
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

        final String[] topics = {"test/early"};
        final PropertyProvider provider = name -> PROPERTY.equals(name) ? topics[0] : null;
        final Map<String, Object> properties = new HashMap<>();
        properties.put(Constants.SERVICE_ID, 1L);
        properties.put(PropertyProvider.PROPERTY_PROPERTIES, PROPERTY);

        final Class<?>[] bindParameters = {PropertyProvider.class, Map.class};
        final Class<?>[] unbindParameters = {PropertyProvider.class, Map.class, boolean.class};

        invoke(service, "bindPropertyProvider", bindParameters, provider, properties);

        // Model two update callbacks interleaving between their unbind and bind operations.
        invoke(service, "unbindPropertyProvider", unbindParameters, provider, properties, false);
        invoke(service, "unbindPropertyProvider", unbindParameters, provider, properties, false);
        invoke(service, "bindPropertyProvider", bindParameters, provider, properties);
        invoke(service, "bindPropertyProvider", bindParameters, provider, properties);

        topics[0] = "test/early,test/late";
        invoke(service, "updatedPropertyProvider", bindParameters, provider, properties);

        assertEquals(topics[0], service.getTopology().getLocalInstance().getProperty(PROPERTY));
    }
}
