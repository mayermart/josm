// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.data.cache;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import org.openstreetmap.josm.data.cache.ICachedLoaderListener.LoadResult;
import org.openstreetmap.josm.data.imagery.TileJobOptions;
import org.openstreetmap.josm.data.preferences.IntegerProperty;
import org.openstreetmap.josm.tools.CheckParameterUtil;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;

import org.apache.commons.jcs3.access.behavior.ICacheAccess;
import org.apache.commons.jcs3.engine.behavior.ICacheElement;

/**
 * Generic loader for HTTP based tiles. Uses custom attribute, to check, if entry has expired
 * according to HTTP headers sent with tile. If so, it tries to verify using Etags
 * or If-Modified-Since / Last-Modified.
 *
 * If the tile is not valid, it will try to download it from remote service and put it
 * to cache. If remote server will fail it will try to use stale entry.
 *
 * This class will keep only one Job running for specified tile. All others will just finish, but
 * listeners will be gathered and notified, once download job will be finished
 *
 * @author Wiktor Niesiobędzki
 * @param <K> cache entry key type
 * @param <V> cache value type
 * @since 8168
 */
public abstract class JCSCachedTileLoaderJob<K, V extends CacheEntry> implements ICachedLoaderJob<K> {
    protected static final long DEFAULT_EXPIRE_TIME = TimeUnit.DAYS.toMillis(7);
    // Limit for the max-age value send by the server.
    protected static final long EXPIRE_TIME_SERVER_LIMIT = TimeUnit.DAYS.toMillis(28);
    // Absolute expire time limit. Cached tiles that are older will not be used,
    // even if the refresh from the server fails.
    protected static final long ABSOLUTE_EXPIRE_TIME_LIMIT = TimeUnit.DAYS.toMillis(365);

    /**
     * maximum download threads that will be started
     */
    public static final IntegerProperty THREAD_LIMIT = new IntegerProperty("cache.jcs.max_threads", 10);

    /*
     * ThreadPoolExecutor starts new threads, until THREAD_LIMIT is reached. Then it puts tasks into LinkedBlockingDeque.
     *
     * The queue works FIFO, so one needs to take care about ordering of the entries submitted
     *
     * There is no point in canceling tasks, that are already taken by worker threads (if we made so much effort, we can at least cache
     * the response, so later it could be used). We could actually cancel what is in LIFOQueue, but this is a tradeoff between simplicity
     * and performance (we do want to have something to offer to worker threads before tasks will be resubmitted by class consumer)
     */

    private static final ThreadPoolExecutor DEFAULT_DOWNLOAD_JOB_DISPATCHER = new ThreadPoolExecutor(
            1, // we have a small queue, so threads will be quickly started (threads are started only, when queue is full)
            THREAD_LIMIT.get(), // do not this number of threads
            30, // keepalive for thread
            TimeUnit.SECONDS,
            // make queue of LIFO type - so recently requested tiles will be loaded first (assuming that these are which user is waiting to see)
            new LinkedBlockingDeque<Runnable>(),
            Utils.newThreadFactory("JCS-downloader-%d", Thread.NORM_PRIORITY)
            );

    private static final ConcurrentMap<String, Set<ICachedLoaderListener>> inProgress = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Boolean> useHead = new ConcurrentHashMap<>();

    protected final long now; // when the job started

    private final ICacheAccess<K, V> cache;
    private ICacheElement<K, V> cacheElement;
    protected V cacheData;
    protected CacheEntryAttributes attributes;

    // HTTP connection parameters
    private final int connectTimeout;
    private final int readTimeout;
    private final Map<String, String> headers;
    private final ThreadPoolExecutor downloadJobExecutor;
    private Runnable finishTask;
    private boolean force;
    private final long minimumExpiryTime;

    /**
     * @param cache cache instance that we will work on
     * @param options options of the request
     * @param downloadJobExecutor that will be executing the jobs
     */
    protected JCSCachedTileLoaderJob(ICacheAccess<K, V> cache,
            TileJobOptions options,
            ThreadPoolExecutor downloadJobExecutor) {
        CheckParameterUtil.ensureParameterNotNull(cache, "cache");
        this.cache = cache;
        this.now = System.currentTimeMillis();
        this.connectTimeout = options.getConnectionTimeout();
        this.readTimeout = options.getReadTimeout();
        this.headers = options.getHeaders();
        this.downloadJobExecutor = downloadJobExecutor;
        this.minimumExpiryTime = TimeUnit.SECONDS.toMillis(options.getMinimumExpiryTime());
    }

    /**
     * @param cache cache instance that we will work on
     * @param options of the request
     */
    protected JCSCachedTileLoaderJob(ICacheAccess<K, V> cache,
            TileJobOptions options) {
        this(cache, options, DEFAULT_DOWNLOAD_JOB_DISPATCHER);
    }

    private void ensureCacheElement() {
        if (cacheElement == null && getCacheKey() != null) {
            cacheElement = cache.getCacheElement(getCacheKey());
            if (cacheElement != null) {
                attributes = (CacheEntryAttributes) cacheElement.getElementAttributes();
                cacheData = cacheElement.getVal();
            }
        }
    }

    @Override
    public V get() {
        ensureCacheElement();
        return cacheData;
    }

    @Override
    public void submit(ICachedLoaderListener listener, boolean force) throws IOException {
        this.force = force;
        boolean first = false;
        URL url = getUrl();
        String deduplicationKey = null;
        if (url != null) {
            // url might be null, for example when Bing Attribution is not loaded yet
            deduplicationKey = url.toString();
        }
        if (deduplicationKey == null) {
            Logging.warn("No url returned for: {0}, skipping", getCacheKey());
            throw new IllegalArgumentException("No url returned");
        }
        synchronized (this) {
            first = !inProgress.containsKey(deduplicationKey);
        }
        inProgress.computeIfAbsent(deduplicationKey, k -> ConcurrentHashMap.newKeySet()).add(listener);

        if (first || force) {
            // submit all jobs to separate thread, so calling thread is not blocked with IO when loading from disk
            Logging.debug("JCS - Submitting job for execution for url: {0}", getUrlNoException());
            downloadJobExecutor.execute(this);
        }
    }

    /**
     * This method is run when job has finished
     */
    protected void executionFinished() {
        if (finishTask != null) {
            finishTask.run();
        }
    }

    /**
     * Checks if object from cache has sufficient data to be returned.
     * @return {@code true} if object from cache has sufficient data to be returned
     */
    protected boolean isObjectLoadable() {
        if (cacheData == null) {
            return false;
        }
        return cacheData.getContent().length > 0;
    }

    /**
     * Simple implementation. All errors should be cached as empty. Though some JDK (JDK8 on Windows for example)
     * doesn't return 4xx error codes, instead they do throw an FileNotFoundException or IOException
     * @param headerFields headers sent by server
     * @param responseCode http status code
     *
     * @return true if we should put empty object into cache, regardless of what remote resource has returned
     */
    protected boolean cacheAsEmpty(Map<String, List<String>> headerFields, int responseCode) {
        return attributes.getResponseCode() < 500;
    }

    /**
     * Returns key under which discovered server settings will be kept.
     * @return key under which discovered server settings will be kept
     */
    protected String getServerKey() {
        try {
            return getUrl().getHost();
        } catch (IOException e) {
            Logging.trace(e);
            return null;
        }
    }

    @Override
    public void run() {
        final Thread currentThread = Thread.currentThread();
        final String oldName = currentThread.getName();
        currentThread.setName("JCS Downloading: " + getUrlNoException());
        Logging.debug("JCS - starting fetch of url: {0} ", getUrlNoException());
        ensureCacheElement();
        try {
            // try to fetch from cache
            if (!force && cacheElement != null && isCacheElementValid() && isObjectLoadable()) {
                // we got something in cache, and it's valid, so lets return it
                Logging.debug("JCS - Returning object from cache: {0}", getCacheKey());
                finishLoading(LoadResult.SUCCESS);
                return;
            }

            // try to load object from remote resource
            if (loadObject()) {
                finishLoading(LoadResult.SUCCESS);
            } else {
                // if loading failed - check if we can return stale entry
                if (isObjectLoadable()) {
                    // try to get stale entry in cache
                    finishLoading(LoadResult.SUCCESS);
                    Logging.debug("JCS - found stale object in cache: {0}", getUrlNoException());
                } else {
                    // failed completely
                    finishLoading(LoadResult.FAILURE);
                }
            }
        } finally {
            executionFinished();
            currentThread.setName(oldName);
        }
    }

    private void finishLoading(LoadResult result) {
        Set<ICachedLoaderListener> listeners;
        try {
            listeners = inProgress.remove(getUrl().toString());
        } catch (IOException e) {
            listeners = null;
            Logging.trace(e);
        }
        if (listeners == null) {
            Logging.warn("Listener not found for URL: {0}. Listener not notified!", getUrlNoException());
            return;
        }
        for (ICachedLoaderListener l: listeners) {
            l.loadingFinished(cacheData, attributes, result);
        }
    }

    protected boolean isCacheElementValid() {
        long expires = attributes.getExpirationTime();

        // check by expire date set by server
        if (expires != 0L) {
            // put a limit to the expire time (some servers send a value
            // that is too large)
            expires = Math.min(expires, attributes.getCreateTime() + Math.max(EXPIRE_TIME_SERVER_LIMIT, minimumExpiryTime));
            if (now > expires) {
                Logging.debug("JCS - Object {0} has expired -> valid to {1}, now is: {2}",
                        getUrlNoException(), Long.toString(expires), Long.toString(now));
                return false;
            }
        } else if (attributes.getLastModification() > 0 &&
                now - attributes.getLastModification() > Math.max(DEFAULT_EXPIRE_TIME, minimumExpiryTime)) {
            // check by file modification date
            Logging.debug("JCS - Object has expired, maximum file age reached {0}", getUrlNoException());
            return false;
        } else if (now - attributes.getCreateTime() > Math.max(DEFAULT_EXPIRE_TIME, minimumExpiryTime)) {
            Logging.debug("JCS - Object has expired, maximum time since object creation reached {0}", getUrlNoException());
            return false;
        }
        return true;
    }

    /**
     * @return true if object was successfully downloaded, false, if there was a loading failure
     */
    private boolean loadObject() {
        if (attributes == null) {
            attributes = new CacheEntryAttributes();
        }
        final URL url = this.getUrlNoException();
        if (url == null) {
            return false;
        }

        if (url.getProtocol().contains("http")) {
            return loadObjectHttp();
        }
        if (url.getProtocol().contains("file")) {
            return loadObjectFile(url);
        }

        return false;
    }

    private boolean loadObjectFile(URL url) {
        String fileName = url.toExternalForm();
        File file = new File(fileName.substring("file:/".length() - 1));
        if (!file.exists()) {
            file = new File(fileName.substring("file://".length() - 1));
        }
        try (InputStream fileInputStream = Files.newInputStream(file.toPath())) {
            cacheData = createCacheEntry(Utils.readBytesFromStream(fileInputStream));
            cache.put(getCacheKey(), cacheData, attributes);
            return true;
        } catch (IOException e) {
            Logging.error(e);
            attributes.setError(e);
            attributes.setException(e);
        }
        return false;
    }

    /**
     * @return true if object was successfully downloaded via http, false, if there was a loading failure
     */
    private boolean loadObjectHttp() {
        try {
            // if we have object in cache, and host doesn't support If-Modified-Since nor If-None-Match
            // then just use HEAD request and check returned values
            if (isObjectLoadable() &&
                    Boolean.TRUE.equals(useHead.get(getServerKey())) &&
                    isCacheValidUsingHead()) {
                Logging.debug("JCS - cache entry verified using HEAD request: {0}", getUrl());
                return true;
            }

            Logging.debug("JCS - starting HttpClient GET request for URL: {0}", getUrl());
            final HttpClient request = getRequest("GET");

            if (isObjectLoadable() &&
                    (now - attributes.getLastModification()) <= ABSOLUTE_EXPIRE_TIME_LIMIT) {
                request.setIfModifiedSince(attributes.getLastModification());
            }
            if (isObjectLoadable() && attributes.getEtag() != null) {
                request.setHeader("If-None-Match", attributes.getEtag());
            }

            final HttpClient.Response urlConn = request.connect();

            if (urlConn.getResponseCode() == 304) {
                // If isModifiedSince or If-None-Match has been set
                // and the server answers with a HTTP 304 = "Not Modified"
                Logging.debug("JCS - If-Modified-Since/ETag test: local version is up to date: {0}", getUrl());
                // update cache attributes
                attributes = parseHeaders(urlConn);
                cache.put(getCacheKey(), cacheData, attributes);
                return true;
            } else if (isObjectLoadable() // we have an object in cache, but we haven't received 304 response code
                    && (
                            (attributes.getEtag() != null && attributes.getEtag().equals(urlConn.getHeaderField("ETag"))) ||
                            attributes.getLastModification() == urlConn.getLastModified())
                    ) {
                // we sent ETag or If-Modified-Since, but didn't get 304 response code
                // for further requests - use HEAD
                String serverKey = getServerKey();
                Logging.info("JCS - Host: {0} found not to return 304 codes for If-Modified-Since or If-None-Match headers",
                        serverKey);
                useHead.put(serverKey, Boolean.TRUE);
            }

            attributes = parseHeaders(urlConn);

            for (int i = 0; i < 5; ++i) {
                if (urlConn.getResponseCode() == HttpURLConnection.HTTP_UNAVAILABLE) {
                    Thread.sleep(5000L+new SecureRandom().nextInt(5000));
                    continue;
                }

                attributes.setResponseCode(urlConn.getResponseCode());
                byte[] raw;
                if (urlConn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    raw = Utils.readBytesFromStream(urlConn.getContent());
                } else {
                    raw = new byte[]{};
                    try {
                        String data = urlConn.fetchContent();
                        if (!data.isEmpty()) {
                            String detectErrorMessage = detectErrorMessage(data);
                            if (detectErrorMessage != null) {
                                attributes.setErrorMessage(detectErrorMessage);
                            }
                        }
                    } catch (IOException e) {
                        Logging.warn(e);
                    }
                }

                if (isResponseLoadable(urlConn.getHeaderFields(), urlConn.getResponseCode(), raw)) {
                    // we need to check cacheEmpty, so for cases, when data is returned, but we want to store
                    // as empty (eg. empty tile images) to save some space
                    cacheData = createCacheEntry(raw);
                    cache.put(getCacheKey(), cacheData, attributes);
                    Logging.debug("JCS - downloaded key: {0}, length: {1}, url: {2}",
                            getCacheKey(), raw.length, getUrl());
                    return true;
                } else if (cacheAsEmpty(urlConn.getHeaderFields(), urlConn.getResponseCode())) {
                    cacheData = createCacheEntry(new byte[]{});
                    cache.put(getCacheKey(), cacheData, attributes);
                    Logging.debug("JCS - Caching empty object {0}", getUrl());
                    return true;
                } else {
                    Logging.debug("JCS - failure during load - response is not loadable nor cached as empty");
                    return false;
                }
            }
        } catch (FileNotFoundException e) {
            Logging.debug("JCS - Caching empty object as server returned 404 for: {0}", getUrlNoException());
            attributes.setResponseCode(404);
            attributes.setError(e);
            attributes.setException(e);
            boolean doCache = isResponseLoadable(null, 404, null) || cacheAsEmpty(Collections.emptyMap(), 404);
            if (doCache) {
                cacheData = createCacheEntry(new byte[]{});
                cache.put(getCacheKey(), cacheData, attributes);
            }
            return doCache;
        } catch (IOException e) {
            Logging.debug("JCS - IOException during communication with server for: {0}", getUrlNoException());
            if (isObjectLoadable()) {
                return true;
            } else {
                attributes.setError(e);
                attributes.setException(e);
                attributes.setResponseCode(599); // set dummy error code, greater than 500 so it will be not cached
                return false;
            }

        } catch (InterruptedException e) {
            attributes.setError(e);
            attributes.setException(e);
            Logging.logWithStackTrace(Logging.LEVEL_WARN, e, "JCS - Exception during download {0}", getUrlNoException());
            Thread.currentThread().interrupt();
        }
        Logging.warn("JCS - Silent failure during download: {0}", getUrlNoException());
        return false;
    }

    /**
     * Tries do detect an error message from given string.
     * @param data string to analyze
     * @return error message if detected, or null
     * @since 14535
     */
    public String detectErrorMessage(String data) {
        Matcher m = HttpClient.getTomcatErrorMatcher(data);
        return m.matches() ? m.group(1).replace("'", "''") : null;
    }

    /**
     * Check if the object is loadable. This means, if the data will be parsed, and if this response
     * will finish as successful retrieve.
     *
     * This simple implementation doesn't load empty response, nor client (4xx) and server (5xx) errors
     *
     * @param headerFields headers sent by server
     * @param responseCode http status code
     * @param raw data read from server
     * @return true if object should be cached and returned to listener
     */
    protected boolean isResponseLoadable(Map<String, List<String>> headerFields, int responseCode, byte[] raw) {
        return raw != null && raw.length != 0 && responseCode < 400;
    }

    protected abstract V createCacheEntry(byte[] content);

    protected CacheEntryAttributes parseHeaders(HttpClient.Response urlConn) {
        CacheEntryAttributes ret = new CacheEntryAttributes();

        /*
         * according to https://www.ietf.org/rfc/rfc2616.txt Cache-Control takes precedence over max-age
         * max-age is for private caches, s-max-age is for shared caches. We take any value that is larger
         */
        Long expiration = 0L;
        String cacheControl = urlConn.getHeaderField("Cache-Control");
        if (cacheControl != null) {
            for (String token: cacheControl.split(",", -1)) {
                try {
                    if (token.startsWith("max-age=")) {
                        expiration = Math.max(expiration,
                                TimeUnit.SECONDS.toMillis(Long.parseLong(token.substring("max-age=".length())))
                                + System.currentTimeMillis()
                                );
                    }
                    if (token.startsWith("s-max-age=")) {
                        expiration = Math.max(expiration,
                                TimeUnit.SECONDS.toMillis(Long.parseLong(token.substring("s-max-age=".length())))
                                + System.currentTimeMillis()
                                );
                    }
                } catch (NumberFormatException e) {
                    // ignore malformed Cache-Control headers
                    Logging.trace(e);
                }
            }
        }

        if (expiration.equals(0L)) {
            expiration = urlConn.getExpiration();
        }

        // if nothing is found - set default
        if (expiration.equals(0L)) {
            expiration = System.currentTimeMillis() + DEFAULT_EXPIRE_TIME;
        }

        ret.setExpirationTime(Math.max(minimumExpiryTime + System.currentTimeMillis(), expiration));
        ret.setLastModification(now);
        ret.setEtag(urlConn.getHeaderField("ETag"));

        return ret;
    }

    private HttpClient getRequest(String requestMethod) throws IOException {
        final HttpClient urlConn = HttpClient.create(getUrl(), requestMethod);
        urlConn.setAccept("text/html, image/png, image/jpeg, image/gif, */*");
        urlConn.setReadTimeout(readTimeout); // 30 seconds read timeout
        urlConn.setConnectTimeout(connectTimeout);
        if (headers != null) {
            urlConn.setHeaders(headers);
        }

        final boolean noCache = force
                // To remove when switching to Java 11
                // Workaround for https://bugs.openjdk.java.net/browse/JDK-8146450
                || (Utils.getJavaVersion() == 8 && Utils.isRunningJavaWebStart());
        urlConn.useCache(!noCache);

        return urlConn;
    }

    private boolean isCacheValidUsingHead() throws IOException {
        final HttpClient.Response urlConn = getRequest("HEAD").connect();
        long lastModified = urlConn.getLastModified();
        boolean ret = (attributes.getEtag() != null && attributes.getEtag().equals(urlConn.getHeaderField("ETag"))) ||
                (lastModified != 0 && lastModified <= attributes.getLastModification());
        if (ret) {
            // update attributes
            attributes = parseHeaders(urlConn);
            cache.put(getCacheKey(), cacheData, attributes);
        }
        return ret;
    }

    /**
     * TODO: move to JobFactory
     * cancels all outstanding tasks in the queue.
     */
    public void cancelOutstandingTasks() {
        for (Runnable r: downloadJobExecutor.getQueue()) {
            if (downloadJobExecutor.remove(r) && r instanceof JCSCachedTileLoaderJob) {
                ((JCSCachedTileLoaderJob<?, ?>) r).handleJobCancellation();
            }
        }
    }

    /**
     * Sets a job, that will be run, when job will finish execution
     * @param runnable that will be executed
     */
    public void setFinishedTask(Runnable runnable) {
        this.finishTask = runnable;

    }

    /**
     * Marks this job as canceled
     */
    public void handleJobCancellation() {
        finishLoading(LoadResult.CANCELED);
    }

    private URL getUrlNoException() {
        try {
            return getUrl();
        } catch (IOException e) {
            Logging.trace(e);
            return null;
        }
    }
}
