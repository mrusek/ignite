/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.examples.streaming;

import org.apache.ignite.*;
import org.apache.ignite.examples.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.streamer.*;
import org.apache.ignite.streamer.index.*;
import org.jetbrains.annotations.*;

import java.util.*;

/**
 * Real time streaming popular numbers counter. This example receives a constant stream of
 * random numbers. The gaussian distribution is chosen to make sure that numbers closer
 * to 0 have higher probability. This example will find {@link #POPULAR_NUMBERS_CNT} number
 * of popular numbers over last N number of numbers, where N is specified as streamer
 * window size in {@code examples/config/example-streamer.xml} configuration file and
 * is set to {@code 10,000}.
 * <p>
 * Remote nodes should always be started with special configuration file:
 * {@code 'ignite.{sh|bat} examples/config/example-streamer.xml'}.
 * When starting nodes this way JAR file containing the examples code
 * should be placed to {@code IGNITE_HOME/libs} folder. You can build
 * {@code gridgain-examples.jar} by running {@code mvn package} in
 * {@code IGNITE_HOME/examples} folder. After that {@code gridgain-examples.jar}
 * will be generated by Maven in {@code IGNITE_HOME/examples/target} folder.
 * <p>
 * Alternatively you can run {@link StreamingNodeStartup} in another JVM which will start node
 * with {@code examples/config/example-streamer.xml} configuration.
 */
public class StreamingPopularNumbersExample {
    /** Count of most popular numbers to retrieve from grid. */
    private static final int POPULAR_NUMBERS_CNT = 10;

    /** Random number generator. */
    private static final Random RAND = new Random();

    /** Count of total numbers to generate. */
    private static final int CNT = 10_000_000;

    /** Comparator sorting random number entries by number popularity. */
    private static final Comparator<StreamerIndexEntry<Integer, Integer, Long>> CMP =
        new Comparator<StreamerIndexEntry<Integer, Integer, Long>>() {
            @Override public int compare(StreamerIndexEntry<Integer, Integer, Long> e1,
                StreamerIndexEntry<Integer, Integer, Long> e2) {
                return e2.value().compareTo(e1.value());
            }
        };

    /** Reducer selecting first POPULAR_NUMBERS_CNT values. */
    private static class PopularNumbersReducer implements IgniteReducer<Collection<StreamerIndexEntry<Integer, Integer, Long>>,
            Collection<StreamerIndexEntry<Integer, Integer, Long>>> {
        /** */
        private final List<StreamerIndexEntry<Integer, Integer, Long>> sorted = new ArrayList<>();

        /** {@inheritDoc} */
        @Override public boolean collect(@Nullable Collection<StreamerIndexEntry<Integer, Integer, Long>> col) {
            if (col != null && !col.isEmpty())
                // Add result from remote node to sorted set.
                sorted.addAll(col);

            return true;
        }

        /** {@inheritDoc} */
        @Override public Collection<StreamerIndexEntry<Integer, Integer, Long>> reduce() {
            Collections.sort(sorted, CMP);

            return sorted.subList(0, POPULAR_NUMBERS_CNT < sorted.size() ? POPULAR_NUMBERS_CNT : sorted.size());
        }
    }

    /**
     * Executes example.
     *
     * @param args Command line arguments, none required.
     * @throws IgniteCheckedException If example execution failed.
     */
    public static void main(String[] args) throws Exception {
        Timer popularNumbersQryTimer = new Timer("numbers-query-worker");

        // Start grid.
        final Ignite ignite = Ignition.start("examples/config/example-streamer.xml");

        System.out.println();
        System.out.println(">>> Streaming popular numbers example started.");

        try {
            // Schedule query to find most popular words to run every 3 seconds.
            TimerTask task = scheduleQuery(ignite, popularNumbersQryTimer);

            streamData(ignite);

            // Force one more run to get final counts.
            task.run();

            popularNumbersQryTimer.cancel();

            // Reset all streamers on all nodes to make sure that
            // consecutive executions start from scratch.
            ignite.compute().broadcast(new Runnable() {
                @Override public void run() {
                    if (!ExamplesUtils.hasStreamer(ignite, "popular-numbers"))
                        System.err.println("Default streamer not found (is example-streamer.xml " +
                            "configuration used on all nodes?)");
                    else {
                        IgniteStreamer streamer = ignite.streamer("popular-numbers");

                        System.out.println("Clearing number counters from streamer.");

                        streamer.reset();
                    }
                }
            });
        }
        finally {
            Ignition.stop(true);
        }
    }

    /**
     * Streams random numbers into the system.
     *
     * @param ignite Ignite.
     * @throws IgniteCheckedException If failed.
     */
    private static void streamData(final Ignite ignite) throws IgniteCheckedException {
        final IgniteStreamer streamer = ignite.streamer("popular-numbers");

        // Use gaussian distribution to ensure that
        // numbers closer to 0 have higher probability.
        for (int i = 0; i < CNT; i++)
            streamer.addEvent(((Double)(RAND.nextGaussian() * 10)).intValue());
    }

    /**
     * Schedules our popular numbers query to run every 3 seconds.
     *
     * @param ignite Ignite.
     * @param timer Timer.
     * @return Scheduled task.
     */
    private static TimerTask scheduleQuery(final Ignite ignite, Timer timer) {
        TimerTask task = new TimerTask() {
            @Override public void run() {
                final IgniteStreamer streamer = ignite.streamer("popular-numbers");

                try {
                    // Send reduce query to all 'popular-numbers' streamers
                    // running on local and remote nodes.
                    Collection<StreamerIndexEntry<Integer, Integer, Long>> col = streamer.context().reduce(
                        // This closure will execute on remote nodes.
                        new IgniteClosure<StreamerContext,
                                                                            Collection<StreamerIndexEntry<Integer, Integer, Long>>>() {
                            @Override public Collection<StreamerIndexEntry<Integer, Integer, Long>> apply(
                                StreamerContext ctx) {
                                StreamerIndex<Integer, Integer, Long> view = ctx.<Integer>window().index();

                                return view.entries(-1 * POPULAR_NUMBERS_CNT);
                            }
                        },
                        // The reducer will always execute locally, on the same node
                        // that submitted the query.
                        new PopularNumbersReducer());

                    for (StreamerIndexEntry<Integer, Integer, Long> cntr : col)
                        System.out.printf("%3d=%d\n", cntr.key(), cntr.value());

                    System.out.println("----------------");
                }
                catch (IgniteCheckedException e) {
                    e.printStackTrace();
                }
            }
        };

        timer.schedule(task, 3000, 3000);

        return task;
    }

    /**
     * Sample streamer stage to compute average.
     */
    @SuppressWarnings("PublicInnerClass")
    public static class StreamerStage implements org.apache.ignite.streamer.StreamerStage<Integer> {
        /** {@inheritDoc} */
        @Override public String name() {
            return "exampleStage";
        }

        /** {@inheritDoc} */
        @Nullable @Override public Map<String, Collection<?>> run(StreamerContext ctx, Collection<Integer> nums)
            throws IgniteCheckedException {
            StreamerWindow<Integer> win = ctx.window();

            // Add numbers to window.
            win.enqueueAll(nums);

            // Clear evicted numbers.
            win.clearEvicted();

            // Null means that there are no more stages
            // and that stage pipeline is completed.
            return null;
        }
    }

    /**
     * This class will be set as part of window index configuration.
     */
    private static class IndexUpdater implements StreamerIndexUpdater<Integer, Integer, Long> {
        /** {@inheritDoc} */
        @Override public Integer indexKey(Integer evt) {
            // We use event as index key, so event and key are the same.
            return evt;
        }

        /** {@inheritDoc} */
        @Nullable @Override public Long onAdded(StreamerIndexEntry<Integer, Integer, Long> entry, Integer evt) {
            return entry.value() + 1;
        }

        /** {@inheritDoc} */
        @Nullable @Override public Long onRemoved(StreamerIndexEntry<Integer, Integer, Long> entry, Integer evt) {
            return entry.value() - 1 == 0 ? null : entry.value() - 1;
        }

        /** {@inheritDoc} */
        @Override public Long initialValue(Integer evt, Integer key) {
            return 1L;
        }
    }
}
