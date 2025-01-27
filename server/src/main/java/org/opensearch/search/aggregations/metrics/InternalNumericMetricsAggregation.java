/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.search.aggregations.metrics;

import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.support.AggregationPath;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class InternalNumericMetricsAggregation extends InternalAggregation {

    private static final DocValueFormat DEFAULT_FORMAT = DocValueFormat.RAW;

    protected DocValueFormat format = DEFAULT_FORMAT;

    public abstract static class SingleValue extends InternalNumericMetricsAggregation implements NumericMetricsAggregation.SingleValue {
        protected SingleValue(String name, Map<String, Object> metadata) {
            super(name, metadata);
        }

        /**
         * Read from a stream.
         */
        protected SingleValue(StreamInput in) throws IOException {
            super(in);
        }

        @Override
        public String getValueAsString() {
            return format.format(value()).toString();
        }

        @Override
        public Object getProperty(List<String> path) {
            if (path.isEmpty()) {
                return this;
            } else if (path.size() == 1 && "value".equals(path.get(0))) {
                return value();
            } else {
                throw new IllegalArgumentException("path not supported for [" + getName() + "]: " + path);
            }
        }

        @Override
        public final double sortValue(String key) {
            if (key != null && false == key.equals("value")) {
                throw new IllegalArgumentException(
                        "Unknown value key [" + key + "] for single-value metric aggregation [" + getName() +
                        "]. Either use [value] as key or drop the key all together");
            }
            return value();
        }
    }

    public abstract static class MultiValue extends InternalNumericMetricsAggregation implements NumericMetricsAggregation.MultiValue {
        protected MultiValue(String name, Map<String, Object> metadata) {
            super(name, metadata);
        }

        /**
         * Read from a stream.
         */
        protected MultiValue(StreamInput in) throws IOException {
            super(in);
        }

        public abstract double value(String name);

        public String valueAsString(String name) {
            return format.format(value(name)).toString();
        }

        @Override
        public Object getProperty(List<String> path) {
            if (path.isEmpty()) {
                return this;
            } else if (path.size() == 1) {
                return value(path.get(0));
            } else {
                throw new IllegalArgumentException("path not supported for [" + getName() + "]: " + path);
            }
        }

        @Override
        public final double sortValue(String key) {
            if (key == null) {
                throw new IllegalArgumentException("Missing value key in [" + key + "] which refers to a multi-value metric aggregation");
            }
            return value(key);
        }
    }

    private InternalNumericMetricsAggregation(String name, Map<String, Object> metadata) {
        super(name, metadata);
    }

    /**
     * Read from a stream.
     */
    protected InternalNumericMetricsAggregation(StreamInput in) throws IOException {
        super(in);
    }

    @Override
    public final double sortValue(AggregationPath.PathElement head, Iterator<AggregationPath.PathElement> tail) {
        throw new IllegalArgumentException("Metrics aggregations cannot have sub-aggregations (at [>" + head + "]");
    }

    @Override
    protected boolean mustReduceOnSingleInternalAgg() {
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), format);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        if (super.equals(obj) == false) return false;

        InternalNumericMetricsAggregation other = (InternalNumericMetricsAggregation) obj;
        return Objects.equals(format, other.format);
    }
}
