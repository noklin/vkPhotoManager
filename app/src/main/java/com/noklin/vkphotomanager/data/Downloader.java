package com.noklin.vkphotomanager.data;


import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.noklin.vkphotomanager.R;
import com.vk.sdk.api.VKApiConst;
import com.vk.sdk.api.VKError;
import com.vk.sdk.api.VKParameters;
import com.vk.sdk.api.VKParser;
import com.vk.sdk.api.VKRequest;
import com.vk.sdk.api.VKResponse;
import com.vk.sdk.api.model.VKApiPhoto;
import com.vk.sdk.api.model.VKApiPhotoAlbum;
import com.vk.sdk.api.model.VKApiPhotoSize;
import com.vk.sdk.api.model.VKApiUser;
import com.vk.sdk.api.model.VKList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;


public class Downloader implements ApplicationHub {

    public static final int DOWNLOAD_CACHE_SIZE = 10;

    private final ExecutorService mExecutor = Executors.newFixedThreadPool(4);
    private final static String TAG = Downloader.class.getSimpleName();
    private final Handler mHandler;
    private final BitmapCache mBitmapCache;
    private final Map<Object , DownloadPictureRunnable> mDownloading = new WeakHashMap<>();
    private final Context mContext;

    private Downloader(Context context){
        mContext = context;
        mHandler = new MyHandler(context);
        mBitmapCache = new BitmapCache(DOWNLOAD_CACHE_SIZE , context);
    }


    public void addToCache(DownloadImageTask task) {
        String url = task.getUrl();
        Bitmap bitmap = task.getBitmap();
        if(url == null || bitmap == null){
            Log.d(TAG, "url or bitmap == null. cannot add to cache");
            Log.d(TAG , " url: " + url);
            return;
        }
        mBitmapCache.add(task.getUrl(), task.getId(), task.getBitmap(), task.mSize);
    }

    public void clear(){
        mBitmapCache.clear();
        mDownloading.clear();
    }

    public Bitmap findInCache(int id){
        return mBitmapCache.getBitmapById(id);
    }

    private static Downloader sInstance;

    public static Downloader getInstance(Context context){
        if (sInstance == null) {
            synchronized (Downloader.class) {
                if (sInstance == null) {
                    sInstance = new Downloader(context);
                }
            }
        }
        return sInstance;
    }

    public void handle(DownloadImageTask task){
        Message msg = mHandler.obtainMessage(0, task);
        msg.sendToTarget();
    }







    //-------------------------------------download picture part-----------------------------

    public void downLoadImage(DownloadImageTask task , boolean drawEmpty){
        Object containerTag = task.getTag();
        //----------------------------check outdated and duplicates downloads---------------
        DownloadPictureRunnable currentDownloading = mDownloading.get(containerTag);
        if(currentDownloading != null){
            if(currentDownloading.getUrl().equals(task.getUrl())){
                return;
            }
            currentDownloading.onOutDated();
            mDownloading.remove(containerTag);
        }

        if(drawEmpty)
            task.getImageView().setImageResource(R.drawable.m_noalbum);
        task.getProgressBar().setVisibility(View.VISIBLE);
        //----------------------------check in cache---------------------------------------

        Bitmap downloaded = mBitmapCache.getBitmapByUrl(task.getUrl());
        if(downloaded != null){
            task.setBitmap(downloaded);
            task.onSuccess();
            return;
        }

        //----------------------------downloading---------------------------------------
        DownloadPictureRunnable download = new DownloadPictureRunnable(task);
        mDownloading.put(task.getTag(), download);
        mExecutor.execute(download);
    }

    @Override
    public void addPhotoData(int id) {
        Database.getInstance(mContext).forOnlineAddPhotoData(id);
    }

    @Override
    public void insertPhoto(VKApiPhoto photo) {
        Database.getInstance(mContext).forOnlineInsertPhoto(photo);
    }

    @Override
    public void addPhotoToAlbum(int photoId, int albumId) {
        Database.getInstance(mContext).forOnlineAddPhotoToAlbum(albumId, photoId);
    }

    @Override
    public void savePhotoToSD(DownloadImageTask task) {
        Database.getInstance(mContext).forOnlineSavePhotoToSD(task);
    }

    //-------------------------------------download picture part-----------------------------


    //------------------------------------download pictures from album part------------------
    public void downloadPicturesFromAlbum(final OnListDownloadedCallback callback, String userId, int albumId){

            VKRequest request = new VKRequest("photos.get", VKParameters.from(VKApiConst.USER_ID, userId
                    , "album_id" , albumId, "extended" , 1));

            request.setResponseParser(new VKParser() {
                @Override
                public Object createModel(JSONObject object) {
                    Log.d(TAG, object.toString());
                    return new VKList<>(object, PhotoWithCoordinates.class);
                }
            });
            request.executeWithListener(new VKRequest.VKRequestListener() {
                @Override
                public void onComplete(VKResponse response) {
                    if (response.parsedModel instanceof VKList) {
                        VKList l = (VKList) response.parsedModel;
                        callback.onSuccessDownload(l);

                    }
                }

                @Override
                public void onError(VKError error) {
                    Log.d(TAG, "Response Error: " + error.toString());
                }
            });
    }

    public static class PhotoWithCoordinates extends VKApiPhoto{
        private String mLat;
        private String mLong;
        private String mOwnerName;

        @Override
        public VKApiPhoto parse(JSONObject from) {
            VKApiPhoto s =  super.parse(from);
            mLat = from.optString("lat");
            mLong = from.optString("long");
            return s;
        }

        public String getOwnerName() {
            return mOwnerName;
        }

        public void setOwnerName(String ownerName) {
            mOwnerName = ownerName;
        }

        public String getLat() {
            return mLat;
        }
        public String getLong() {
            return mLong;
        }

        public void setLong(String aLong) {
            mLong = aLong;
        }

        public void setLat(String lat) {
            mLat = lat;
        }

        public static Creator<VKApiPhoto> CREATOR = new Creator<VKApiPhoto>() {
            public VKApiPhoto createFromParcel(Parcel source) {
                return new VKApiPhoto(source);
            }
            public VKApiPhoto[] newArray(int size) {
                return new VKApiPhoto[size];
            }
        };
    }

    //------------------------------------download pictures from album part------------------


    //--------------------------------download album part-------------------------------
    public void downloadAlbumList(final OnListDownloadedCallback callback, final String userId){

        VKRequest request = new VKRequest("photos.getAlbums"
                , VKParameters.from(VKApiConst.USER_ID, userId, "need_covers", "1"));
        request.setResponseParser(new VKParser() {
            @Override
            public Object createModel(JSONObject object) {
                return new VKList<>(object, VKApiPhotoAlbum.class);
            }
        });
        request.executeWithListener(new VKRequest.VKRequestListener() {
            @Override
            public void onComplete(VKResponse response) {
                if (response.parsedModel instanceof List) {
                    List l = (List) response.parsedModel;
                    callback.onSuccessDownload(l);
                }
            }

            @Override
            public void onError(VKError error) {
                callback.onFailedDownload();
            }
        });

        request = new VKRequest("users.get"
                , VKParameters.from(VKApiConst.USER_ID, userId ));

        request.executeWithListener(new VKRequest.VKRequestListener() {
            @Override
            public void onComplete(VKResponse response) {
                try{
                    JSONArray arr = response.json.getJSONArray("response");
                    VKApiUser user =  new VKApiUser(arr.getJSONObject(0));
                    if(user.id != 0){
                        Database.getInstance(mContext).insertUser(user);
                    }
                }catch(JSONException ignore){
                    //NOP
                }
            }

            @Override
            public void onError(VKError error) {
                // NOP
            }
        });

    }

    //--------------------------------download album part-------------------------------


    //---------------------------Download image part------------------------------------
    public static class DownloadPictureRunnable implements Runnable{
        private boolean mActual = true;
        private final DownloadImageTask mTask;

        public DownloadPictureRunnable(DownloadImageTask task){
            mTask = task;
        }

        public void onOutDated(){
            mActual = false;
        }

        public String getUrl(){
            return mTask.getUrl();
        }

        @Override
        public void run() {
            long b = System.currentTimeMillis();
            mTask.mState = DownloadImageTask.START;
            HttpsURLConnection connection = null;
            try{
                URL url = new URL(mTask.mUrl);
                connection = (HttpsURLConnection) url.openConnection();
                if(connection.getResponseCode() != HttpsURLConnection.HTTP_OK){
                    mTask.setState(DownloadImageTask.FAILED);
                }else{
                    connection.connect();
                    InputStream input = connection.getInputStream();
                    Bitmap downloadedBitmap = BitmapFactory.decodeStream(input);
                    if (downloadedBitmap != null){
                        mTask.mState = DownloadImageTask.SUCCESS;
                        mTask.setBitmap(downloadedBitmap);
                    }
                }
            }catch (IOException ex){
                mTask.mState = DownloadImageTask.FAILED;
            }finally {
                if(connection != null)
                    connection.disconnect();
            }
            long a = System.currentTimeMillis();
            Log.d("TASK_COMPLETED_TIME: ", mTask.mUrl + " time: " + (a - b) + "(ms) thread:" + Thread.currentThread().getName());
            mTask.setActual(mActual);
            mTask.handleComplete();
        }
    }
    //---------------------------Download image part------------------------------------

    //----------------------------------DOWNLOAD TASK PART-----------------------------
    public static class DownloadImageTask {
        private final Context mContext;

        public DownloadImageTask(Context context , Object tag, ImageView imageView, ProgressBar progressBar, int id, String url, int size){
            mUrl =  url;
            mImageView = imageView;
            mProgressBar = progressBar;
            mSize = size;
            mTag = tag;
            mContext = context;
            mId = id;

        }

        //--------------------CONSTANTS-----------------------------------
        public final static int IMG_SIZE_SMALL = 1;
        public final static int IMG_SIZE_BIG = 2;
        public final static int START = 0;
        public final static int SUCCESS = 1;
        public final static int FAILED = -1;
        //--------------------CONSTANTS-----------------------------------


        //--------------------------container fields-------------------------------
        private ImageView mImageView;
        private ProgressBar mProgressBar;
        private Bitmap mBitmap ;
        private int mState;
        //--------------------------container fields-------------------------------

        private final int mId;
        private final Object mTag;
        private final int mSize;
        private final String mUrl;
        private boolean mIsActual = true;


        //-------------------------api-------------------------------------------------


        public int getSizeFlag() {
            return mSize;
        }

        public ImageView getImageView() {
            return mImageView;
        }

        public ProgressBar getProgressBar() {
            return mProgressBar;
        }

        public int getId(){
            return mId;
        }

        public boolean isActual() {
            return mIsActual;
        }

        public void setActual(boolean isActual){
            mIsActual = isActual;
        }

        public Object getTag() {
            return mTag;
        }

        public void setState(int state) {
            mState = state;
        }

        public void setBitmap(Bitmap bitmap){
            mBitmap = bitmap;
        }

        public String getUrl(){
            return mUrl;
        }

        public void setFields(DownloadImageTask task){
            mImageView = task.mImageView;
            mProgressBar = task.mProgressBar;
        }


        public Bitmap getBitmap(){
            return mBitmap;
        }


        //----------------FOR BG thread-------------------------

        public void handleComplete(){
            Downloader.getInstance(mContext).handle(this);
        }
        //----------------FOR BG thread-------------------------


        //----------------FOR UI thread-------------------------

        public void onSuccess(){
            mImageView.setImageBitmap(mBitmap);
            mProgressBar.setVisibility(View.GONE);
        }



        //----------------FOR UI thread-------------------------

        //-------------------------api-------------------------------------------------

    }

    //------------------------handler part----------------------------------------
    private static class MyHandler extends Handler{
        private Context mContext;
        public MyHandler(Context context){
            super(Looper.getMainLooper());
            mContext = context;
        }

        @Override
        public void handleMessage(Message msg) {
            DownloadImageTask task = (DownloadImageTask)msg.obj;
            if(task != null){
                switch(task.mState){
                    case DownloadImageTask.SUCCESS:
                        Downloader.getInstance(mContext).addToCache(task);
                        if(task.getSizeFlag() == DownloadImageTask.IMG_SIZE_BIG){
                            Downloader.getInstance(mContext).savePhotoToSD(task);
                        }
                        if(task.isActual()){
                            task.onSuccess();
                        }
                        break;
                    case DownloadImageTask.FAILED:
                        break;

                }
            }
        }
    }
    //------------------------handler part----------------------------------------


    //--------------------------CACHE PART-------------------------------

    public static class BitmapCache{
        private final Context mContext;
        private final int mCacheSize;
//        private final List<WeakReference<CacheItem>> mCache = new ArrayList<>();
        private final List<CacheItem> mCache = new ArrayList<>();
        private int mItemCount = 0;

        public BitmapCache(int cacheSize, Context context){
            mCacheSize = cacheSize;
            mContext = context;
        }

        public Bitmap getBitmapByUrl(String url){
            CacheItem item = getItemByUrl(url);
            return item != null ? item.mBitmap : null;
        }

        public Bitmap getBitmapById(int id){
            CacheItem item = getItemById(id);
            return item != null ? item.mBitmap : null;
        }

        public CacheItem getItemByUrl(String url){
            if(url == null) return null;
            CacheItem result = null;
//            for(WeakReference<CacheItem> item : mCache){
//                if(item.get() != null && item.get().mUrl.equals(url)){
//                    result = item.get();
//                    break;
//                }
//            }
            for(CacheItem item : mCache){
                if(item.mUrl.equals(url)){
                    result = item;
                    break;
                }
            }
            return result;
        }

        public synchronized CacheItem getItemById(int id){
            if(id == 0) return null;
            CacheItem result = null;
//            for(WeakReference<CacheItem> item : mCache){
//                if(item.get() != null && item.get().mUrl.equals(url)){
//                    result = item.get();
//                    break;
//                }
//            }
            for(CacheItem item : mCache){
                if(item.mId == id && item.mSizeFlag == DownloadImageTask.IMG_SIZE_SMALL){
                    result = item;
                    break;
                }
            }
            return result;
        }


        public synchronized void add(String url , int id,  Bitmap bitmap , int sizeFlag){
            Log.d(TAG , "ADD TO CACHE with flag: " + sizeFlag);
            int index = mItemCount++ % mCacheSize;
            if(mCache.size() > index){
                mCache.remove(index);
            }
//                mCache.add(index, new WeakReference<CacheItem>(new CacheItem(url,id  bitmap)));
            mCache.add(index, new CacheItem(url, id, sizeFlag, bitmap));


        }

        public void add(String url , int id,  Bitmap bitmap){
            add(url , id , bitmap, DownloadImageTask.IMG_SIZE_SMALL);
        }

        public void clear(){
            mCache.clear();
            mItemCount = 0;
        }

        private class CacheItem{
            private final int mSizeFlag;
            private final String mUrl;
            private final int mId;
            private Bitmap mBitmap;

            public CacheItem(String url, int id, int sizeFlag, Bitmap bitmap){
                if(url == null || bitmap == null)
                    throw new NullPointerException("url or bitmap or size cannot be null");
                mUrl = url;
                mBitmap = bitmap;
                mId = id;
                mSizeFlag = sizeFlag;
            }

            public CacheItem(String url, int id, Bitmap bitmap){
                this(url, id, DownloadImageTask.IMG_SIZE_SMALL, bitmap);
            }
        }

    }
}
