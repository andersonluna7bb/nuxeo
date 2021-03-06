/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *       bdelbosc
 */
package org.nuxeo.runtime.stream;

import static org.nuxeo.runtime.stream.StreamServiceImpl.STREAM_PROCESSING_ENABLED;

import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.RunnerFeature;

/**
 * @since 11.2
 */
public class DisableStreamProcessingFeature implements RunnerFeature {

    protected String flag;

    @Override
    public void initialize(FeaturesRunner runner) {
        flag = System.setProperty(STREAM_PROCESSING_ENABLED, "false");
    }

    @Override
    public void stop(FeaturesRunner runner) {
        if (flag == null) {
            System.clearProperty(STREAM_PROCESSING_ENABLED);
        } else {
            System.setProperty(STREAM_PROCESSING_ENABLED, flag);
        }
    }
}
