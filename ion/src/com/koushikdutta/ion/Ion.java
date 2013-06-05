package com.koushikdutta.ion;

import android.content.Context;
import android.util.Log;
import android.widget.ImageView;

import com.google.gson.Gson;
import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.future.Future;
import com.koushikdutta.async.http.AsyncHttpClient;
import com.koushikdutta.async.http.ResponseCacheMiddleware;
import com.koushikdutta.ion.builder.IonBodyParamsRequestBuilder;
import com.koushikdutta.ion.builder.IonFutureRequestBuilder;
import com.koushikdutta.ion.builder.IonLoadRequestBuilder;
import com.koushikdutta.ion.builder.IonMutableBitmapRequestPostLoadBuilder;
import com.koushikdutta.ion.cookie.CookieMiddleware;
import com.koushikdutta.ion.loader.ContentLoader;
import com.koushikdutta.ion.loader.FileLoader;
import com.koushikdutta.ion.loader.HttpLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by koush on 5/21/13.
 */
public class Ion {
    /**
     * Get the default Ion object instance and begin building a request
     * with the given uri
     * @param context
     * @param uri
     * @return
     */
    public static IonBodyParamsRequestBuilder with(Context context, String uri) {
        return getDefault(context).build(context, uri);
    }

    /**
     * Get the default Ion object instance and begin building an operation
     * on the given file
     * @param context
     * @param file
     * @return
     */
    public static IonFutureRequestBuilder with(Context context, File file) {
        return getDefault(context).build(context, file);
    }

    /**
     * Begin building an operation on the given file
     * @param context
     * @param file
     * @return
     */
    public IonFutureRequestBuilder build(Context context, File file) {
        return new IonRequestBuilder(context, this).load(file);
    }

    /**
     * Get the default Ion instance
     * @param context
     * @return
     */
    public static Ion getDefault(Context context) {
        if (instance == null)
            instance = new Ion(context);
        return instance;
    }

    /**
     * Create a ImageView bitmap request builder
     * @param imageView
     * @return
     */
    public static IonMutableBitmapRequestPostLoadBuilder with(ImageView imageView) {
        Ion ion = getDefault(imageView.getContext());
        return new IonRequestBuilder(imageView.getContext(), ion).withImageView(imageView);
    }

    /**
     * Begin building a request with the given uri
     * @param context
     * @param uri
     * @return
     */
    public IonBodyParamsRequestBuilder build(Context context, String uri) {
        return new IonRequestBuilder(context, this).load(uri);
    }

    /**
     * Create a builder that can be used to build an network request
     * @param imageView
     * @return
     */
    public IonMutableBitmapRequestPostLoadBuilder build(ImageView imageView) {
        return new IonRequestBuilder(imageView.getContext(), this).withImageView(imageView);
    }

    /**
     * Cancel all pending requests associated with the request group
     * @param group
     */
    public void cancelAll(Object group) {
        FutureSet members;
        synchronized (this) {
            members = inFlight.remove(group);
        }

        if (members == null)
            return;

        for (Future future: members.keySet()) {
            if (future != null)
                future.cancel();
        }
    }

    /**
     * Route all http requests through the given proxy.
     * @param host
     * @param port
     */
    public void proxy(String host, int port) {
        httpClient.getSocketMiddleware().enableProxy(host, port);
    }

    /**
     * Route all https requests through the given proxy.
     * Note that https proxying requires that the Android device has the appropriate
     * root certificate installed to function properly.
     * @param host
     * @param port
     */
    public void proxySecure(String host, int port) {
        httpClient.getSSLSocketMiddleware().enableProxy(host, port);
    }

    /**
     * Disable routing of http requests through a previous provided proxy
     */
    public void disableProxy() {
        httpClient.getSocketMiddleware().disableProxy();
    }

    /**
     * Disable routing of https requests through a previous provided proxy
     */
    public void disableSecureProxy() {
        httpClient.getSocketMiddleware().disableProxy();
    }

    void removeFutureInFlight(Future future, Object group) {

    }

    void addFutureInFlight(Future future, Object group) {
        if (group == null || future == null || future.isDone() || future.isCancelled())
            return;

        FutureSet members;
        synchronized (this) {
            members = inFlight.get(group);
            if (members == null) {
                members = new FutureSet();
                inFlight.put(group, members);
            }
        }

        members.put(future, true);
    }

    /**
     * Cancel all pending requests
     */
    public void cancelAll() {
        ArrayList<Object> groups;

        synchronized (this) {
            groups = new ArrayList<Object>(inFlight.keySet());
        }

        for (Object group: groups)
            cancelAll(group);
    }

    /**
     * Cancel all pending requests associated with the given context
     * @param context
     */
    public void cancelAll(Context context) {
        cancelAll((Object)context);
    }

    public int getPendingRequestCount(Object group) {
        synchronized (this) {
            FutureSet members = inFlight.get(group);
            if (members == null)
                return 0;
            int ret = 0;
            for (Future future: members.keySet()) {
                if (!future.isCancelled() && !future.isDone())
                    ret++;
            }
            return ret;
        }
    }

    public void dump() {
        bitmapCache.dump();
        Log.i(LOGTAG, "Pending downloads: " + pendingDownloads.size());
        Log.i(LOGTAG, "Pending transforms: " + pendingTransforms.size());
        Log.i(LOGTAG, "Pending views: " + pendingViews.size());
        Log.i(LOGTAG, "Groups: " + inFlight.size());
        for (FutureSet futures: inFlight.values()) {
            Log.i(LOGTAG, "Group size: " + futures.size());
        }
    }

    /**
     * Get the application Context object in use by this Ion instance
     * @return
     */
    public Context getContext() {
        return context;
    }

    AsyncHttpClient httpClient;
    CookieMiddleware cookieMiddleware;
    ResponseCacheMiddleware responseCache;

    static class FutureSet extends WeakHashMap<Future, Boolean> {
    }
    // maintain a list of futures that are in being processed, allow for bulk cancellation
    WeakHashMap<Object, FutureSet> inFlight = new WeakHashMap<Object, FutureSet>();

    Context context;
    private Ion(Context context) {
        httpClient = new AsyncHttpClient(new AsyncServer());
        this.context = context = context.getApplicationContext();

        try {
            responseCache = ResponseCacheMiddleware.addCache(httpClient, new File(context.getCacheDir(), "ion"), 10L * 1024L * 1024L);
        }
        catch (Exception e) {
            IonLog.w("unable to set up response cache", e);
        }
        httpClient.insertMiddleware(cookieMiddleware = new CookieMiddleware(context));

        httpClient.getSocketMiddleware().setConnectAllAddresses(true);
        httpClient.getSSLSocketMiddleware().setConnectAllAddresses(true);

        bitmapCache = new IonBitmapCache(this);

        configure()
        .addLoader(new HttpLoader())
        .addLoader(new ContentLoader())
        .addLoader(new FileLoader());
    }

    /**
     * Get the Cookie middleware that is attached to the AsyncHttpClient instance.
     * @return
     */
    public CookieMiddleware getCookieMiddleware() {
        return cookieMiddleware;
    }

    /**
     * Get the AsyncHttpClient object in use by this Ion instance
     * @return
     */
    public AsyncHttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * Get the AsyncServer reactor in use by this Ion instance
     * @return
     */
    public AsyncServer getServer() {
        return httpClient.getServer();
    }

    public class Config {
        ArrayList<Loader> loaders = new ArrayList<Loader>();
        public Config addLoader(int index, Loader loader) {
            loaders.add(index, loader);
            return this;
        }
        public Config insertLoader(Loader loader) {
            loaders.add(0, loader);
            return this;
        }
        public Config addLoader(Loader loader) {
            loaders.add(loader);
            return this;
        }
        public List<Loader> getLoaders() {
            return loaders;
        }
    }

    String LOGTAG;
    int logLevel;
    /**
     * Set the log level for all requests made by Ion.
     * @param logtag
     * @param logLevel
     */
    public void setLogging(String logtag, int logLevel) {
        LOGTAG = logtag;
        this.logLevel = logLevel;
    }

    Config config = new Config();
    public Config configure() {
        return config;
    }

    // map an ImageView to the url being downloaded for it.
    // but don't hold references to the ImageView...
    WeakHashMap<ImageView, String> pendingViews = new WeakHashMap<ImageView, String>();
    ExecutorService executorService = Executors.newFixedThreadPool(3);

    // track the downloads and transforms that are pending.
    // but don't maintain a reference.
    // The reference stays alive because the reference chain looks as follows:
    // AsyncServer -> AsyncHttpClient -> IonRequestBuilder.execute future ->
    // bitmap decode future -> transform future -> drawable future ->
    // asBitmap/intoImageView future
    // Thus, the reference to the future will exist, so long as it is reachable
    // by a callback; ie, it was not cancelled.
    WeakReferenceHashTable<String, IonBitmapRequestBuilder.ByteArrayToBitmapFuture> pendingDownloads = new WeakReferenceHashTable<String, IonBitmapRequestBuilder.ByteArrayToBitmapFuture>();
    WeakReferenceHashTable<String, IonBitmapRequestBuilder.BitmapToBitmap> pendingTransforms = new WeakReferenceHashTable<String, IonBitmapRequestBuilder.BitmapToBitmap>();

    IonBitmapCache bitmapCache;
    /**
     * Return the bitmap cache used by this Ion instance
     * @return
     */
    public IonBitmapCache getBitmapCache() {
        return bitmapCache;
    }

    Gson gson = new Gson();
    /**
     * Get the Gson object in use by this Ion instance.
     * This can be used to customize serialization and deserialization
     * from java objects.
     * @return
     */
    public Gson getGson() {
        return gson;
    }

    private static Ion instance;
}
