package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
    private final Clock clock;
    private final Duration timeout;
    private final int popularWordCount;
    private final ForkJoinPool pool;
    private final int maxDepth;
    private final PageParserFactory parserFactory;
    private final List<Pattern> ignoredUrls;
    public static Lock lock = new ReentrantLock();

    @Inject
    ParallelWebCrawler(
            Clock clock,
            @Timeout Duration timeout,
            @PopularWordCount int popularWordCount,
            @TargetParallelism int threadCount,
            @MaxDepth int maxDepth,
            @IgnoredUrls List<Pattern> ignoredUrls,
            PageParserFactory parserFactory) {
        this.clock = clock;
        this.timeout = timeout;
        this.popularWordCount = popularWordCount;
        this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
        this.maxDepth = maxDepth;
        this.ignoredUrls = ignoredUrls;
        this.parserFactory = parserFactory;
    }

    @Override
    public CrawlResult crawl(List<String> startingUrls) {
        Instant deadline = clock.instant().plus(timeout);
        ConcurrentMap<String, Integer> counts = new ConcurrentHashMap<>();
        ConcurrentSkipListSet<String> visitedUrls = new ConcurrentSkipListSet<>();
        for (String url : startingUrls) {
            pool.invoke(new crawlInternal(url, deadline, maxDepth, counts, visitedUrls, clock, parserFactory, ignoredUrls));
        }

        return new CrawlResult.Builder()
                .setWordCounts(counts.isEmpty() ? counts : WordCounts.sort(counts, popularWordCount))
                .setUrlsVisited(visitedUrls.size())
                .build();


    }

    public static class crawlInternal extends RecursiveTask<Boolean> {
        private final String url;
        private final Instant deadline;
        private final int maxDepth;
        private final ConcurrentMap<String, Integer> counts;
        private final ConcurrentSkipListSet<String> visitedUrls;
        private final Clock clock;
        private final PageParserFactory parserFactory;
        private final List<Pattern> ignoredUrls;

        public crawlInternal(String url, Instant deadline, int maxDepth, ConcurrentMap<String, Integer> counts, ConcurrentSkipListSet<String> visitedUrls, Clock clock,
                             PageParserFactory parserFactory, List<Pattern> ignoredUrls) {
            this.url = url;
            this.deadline = deadline;
            this.maxDepth = maxDepth;
            this.counts = counts;
            this.visitedUrls = visitedUrls;
            this.clock = clock;
            this.parserFactory = parserFactory;
            this.ignoredUrls = ignoredUrls;
        }

        @Override
        protected Boolean compute() {
            if (maxDepth == 0 || clock.instant().isAfter(deadline)) {
                return false;
            }

            for (Pattern pattern : ignoredUrls) {
                if (pattern.matcher(url).matches()) {
                    return false;
                }
            }

            try {
                lock.lock();
                if (visitedUrls.contains(url)) {
                    return false;

                }
                visitedUrls.add(url);
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                lock.unlock();
            }

            PageParser.Result result = parserFactory.get(url).parse();
            for (ConcurrentMap.Entry<String, Integer> entry : result.getWordCounts().entrySet()) {
                if (counts.containsKey(entry.getKey())) {
                    counts.put(entry.getKey(), entry.getValue() + counts.get(entry.getKey()));
                } else {
                    counts.put(entry.getKey(), entry.getValue());
                }
            }


            List<crawlInternal> subtasks = new ArrayList<>();
            for (String link : result.getLinks()) {
                subtasks.add(new crawlInternal(link, deadline, maxDepth - 1, counts, visitedUrls, clock, parserFactory, ignoredUrls));
            }
            invokeAll(subtasks);
            return true;
        }

    }

    @Override
    public int getMaxParallelism() {
        return Runtime.getRuntime().availableProcessors();
    }
}
