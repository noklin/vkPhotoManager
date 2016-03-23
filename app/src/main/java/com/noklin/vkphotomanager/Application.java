package com.noklin.vkphotomanager;


import android.support.annotation.Nullable;
import android.util.Log;

import com.noklin.vkphotomanager.data.Database;
import com.noklin.vkphotomanager.data.Downloader;
import com.vk.sdk.VKAccessToken;
import com.vk.sdk.VKAccessTokenTracker;
import com.vk.sdk.VKSdk;

public class Application extends android.app.Application {
    private final String TAG = Application.class.getSimpleName();


    private VKAccessTokenTracker vkAccessTokenTracker = new VKAccessTokenTracker() {
        @Override
        public void onVKAccessTokenChanged(@Nullable VKAccessToken oldToken, @Nullable VKAccessToken newToken) {
            Log.d(TAG , "new Token null? " + (newToken == null));
        }
    };


    @Override
    public void onCreate() {
        super.onCreate();
        vkAccessTokenTracker.startTracking();
        VKSdk.initialize(this);
        Database.getInstance(this);
    }

    @Override
    public void onLowMemory() {
        Downloader.getInstance(this).clear();
        super.onLowMemory();
    }



    //---------------------------------------constants------------------------------------
    public static final int OFFLINE_MODE = 0;
    public static final int ONLINE_MODE = 1;
}
