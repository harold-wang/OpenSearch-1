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

package org.opensearch.search.suggest.completion;

import org.opensearch.common.text.Text;
import org.opensearch.search.suggest.Suggest;
import org.opensearch.test.OpenSearchTestCase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.opensearch.search.suggest.Suggest.COMPARATOR;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class CompletionSuggestionTests extends OpenSearchTestCase {

    public void testReduce() {
        List<Suggest.Suggestion<CompletionSuggestion.Entry>> shardSuggestions = new ArrayList<>();
        int nShards = randomIntBetween(1, 10);
        String name = randomAlphaOfLength(10);
        int size = randomIntBetween(3, 5);
        for (int i = 0; i < nShards; i++) {
            CompletionSuggestion suggestion = new CompletionSuggestion(name, size, false);
            suggestion.addTerm(new CompletionSuggestion.Entry(new Text(""), 0, 0));
            shardSuggestions.add(suggestion);
        }
        int totalResults = randomIntBetween(0, 5) * nShards;
        float maxScore = randomIntBetween(totalResults, totalResults*2);
        for (int i = 0; i < totalResults; i++) {
            Suggest.Suggestion<CompletionSuggestion.Entry> suggestion = randomFrom(shardSuggestions);
            CompletionSuggestion.Entry entry = suggestion.getEntries().get(0);
            if (entry.getOptions().size() < size) {
                CompletionSuggestion.Entry.Option option = new CompletionSuggestion.Entry.Option(i, new Text(""),
                    maxScore - i, Collections.emptyMap());
                option.setShardIndex(randomIntBetween(0, Integer.MAX_VALUE));
                entry.addOption(option);
            }
        }
        CompletionSuggestion reducedSuggestion = (CompletionSuggestion) shardSuggestions.get(0).reduce(shardSuggestions);
        assertNotNull(reducedSuggestion);
        assertThat(reducedSuggestion.getOptions().size(), lessThanOrEqualTo(size));
        int count = 0;
        for (CompletionSuggestion.Entry.Option option : reducedSuggestion.getOptions()) {
            assertThat(option.getDoc().doc, equalTo(count));
            count++;
        }
    }

    public void testReduceWithDuplicates() {
        List<Suggest.Suggestion<CompletionSuggestion.Entry>> shardSuggestions = new ArrayList<>();
        int nShards = randomIntBetween(2, 10);
        String name = randomAlphaOfLength(10);
        int size = randomIntBetween(10, 100);
        int totalResults = size * nShards;
        int numSurfaceForms = randomIntBetween(1, size);
        String[] surfaceForms = new String[numSurfaceForms];
        for (int i = 0; i < numSurfaceForms; i++) {
            surfaceForms[i] = randomAlphaOfLength(20);
        }
        List<CompletionSuggestion.Entry.Option> options = new ArrayList<>();
        for (int i = 0; i < nShards; i++) {
            CompletionSuggestion suggestion = new CompletionSuggestion(name, size, true);
            CompletionSuggestion.Entry entry = new CompletionSuggestion.Entry(new Text(""), 0, 0);
            suggestion.addTerm(entry);
            int maxScore = randomIntBetween(totalResults, totalResults*2);
            for (int j = 0; j < size; j++) {
                String surfaceForm = randomFrom(surfaceForms);
                CompletionSuggestion.Entry.Option newOption =
                    new CompletionSuggestion.Entry.Option(j, new Text(surfaceForm), maxScore - j, Collections.emptyMap());
                newOption.setShardIndex(0);
                entry.addOption(newOption);
                options.add(newOption);
            }
            shardSuggestions.add(suggestion);
        }
        List<CompletionSuggestion.Entry.Option> expected = options.stream()
            .sorted(COMPARATOR)
            .distinct()
            .limit(size)
            .collect(Collectors.toList());
        CompletionSuggestion reducedSuggestion = (CompletionSuggestion) shardSuggestions.get(0).reduce(shardSuggestions);
        assertNotNull(reducedSuggestion);
        assertThat(reducedSuggestion.getOptions().size(), lessThanOrEqualTo(size));
        assertEquals(expected, reducedSuggestion.getOptions());
    }

    public void testReduceTiebreak() {
        List<Suggest.Suggestion<CompletionSuggestion.Entry>> shardSuggestions = new ArrayList<>();
        Text surfaceForm = new Text(randomAlphaOfLengthBetween(5, 10));
        float score = randomFloat();
        int numResponses = randomIntBetween(2, 10);
        String name = randomAlphaOfLength(10);
        int size = randomIntBetween(10, 100);
        for (int i = 0; i < numResponses; i++) {
            CompletionSuggestion suggestion = new CompletionSuggestion(name, size, false);
            CompletionSuggestion.Entry entry = new CompletionSuggestion.Entry(new Text(""), 0, 0);
            suggestion.addTerm(entry);
            int shardIndex = 0;
            for (int j = 0; j < size; j++) {
                CompletionSuggestion.Entry.Option newOption =
                    new CompletionSuggestion.Entry.Option((j + 1) * (i + 1), surfaceForm, score, Collections.emptyMap());
                newOption.setShardIndex(shardIndex++);
                entry.addOption(newOption);
            }
            shardSuggestions.add(suggestion);
        }
        CompletionSuggestion reducedSuggestion = (CompletionSuggestion) shardSuggestions.get(0).reduce(shardSuggestions);
        assertNotNull(reducedSuggestion);
        List<CompletionSuggestion.Entry.Option> options = reducedSuggestion.getOptions();
        assertThat(options.size(), lessThanOrEqualTo(size));
        int shardIndex = 0;
        int docId = -1;
        for (CompletionSuggestion.Entry.Option option : options) {
            assertThat(option.getDoc().shardIndex, greaterThanOrEqualTo(shardIndex));
            if (option.getDoc().shardIndex == shardIndex) {
                assertThat(option.getDoc().doc, greaterThan(docId));
            } else {
                assertThat(option.getDoc().shardIndex, equalTo(shardIndex + 1));
                shardIndex = option.getDoc().shardIndex;
            }
            docId = option.getDoc().doc;
        }
    }
}
