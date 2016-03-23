package com.noklin.vkphotomanager.data;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Path;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;

import com.noklin.vkphotomanager.R;
import com.vk.sdk.api.model.VKApiPhoto;
import com.vk.sdk.api.model.VKApiPhotoAlbum;
import com.vk.sdk.api.model.VKApiUser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Database extends SQLiteOpenHelper implements ApplicationHub {
    private final static String TAG = Database.class.getSimpleName();
    private final Downloader.BitmapCache mCache;
    private final Context mContext;
    private final UIHandler mUIHandler;
    private static final int SAVE_LIMIT = 1000;
    public static final int BITMAP_CACHE_SIZE = 10;
    public static final String NAME = "vkPhotoManager";
    public static int VERSION = 1;


    public void addToCache(String url , int id, Bitmap bitmap){
        mCache.add(url , id,  bitmap);
    }

    private final DbHandler mDbHandler;

    private Database(Context context) {
        super(context, NAME, null, VERSION);
        mUIHandler = new UIHandler();
        mContext = context;
        mCache = new Downloader.BitmapCache(BITMAP_CACHE_SIZE , context);

        HandlerThread chatOutputHandleThread = new HandlerThread(DbHandler.class.getSimpleName()
                , android.os.Process.THREAD_PRIORITY_BACKGROUND);
        chatOutputHandleThread.setDaemon(true);
        chatOutputHandleThread.start();
        mDbHandler = new DbHandler(chatOutputHandleThread.getLooper());
    }

    private static Database sInstance;

    public static Database getInstance(Context context){
        if (sInstance == null) {
            synchronized (Database.class) {
                if (sInstance == null) {
                    sInstance = new Database(context);
                }
            }
        }
        return sInstance;
    }


    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(Q_CREATE_USER);
        db.execSQL(Q_CREATE_PHOTO);
        db.execSQL(Q_CREATE_ALBUM);
        db.execSQL(Q_CREATE_ALBUM_PHOTO);
        db.execSQL(Q_INSERT_NULL_USER);
        db.execSQL(Q_INSERT_NULL_PHOTO);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

    }




    //-------------------------------handler part-------------------------

    private void postToDBHandler(int what, Object obj){
        Message msg = mDbHandler.obtainMessage(what, obj);
        msg.sendToTarget();
    }

    //----------------------ui handler part-------------------------------
    public static class UIHandler extends Handler{

        public UIHandler(){
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what){
                case LIST_DOWNLOADED:
                    ApplicationHub.OnListDownloadedCallback callback;
                    if(msg.obj instanceof ApplicationHub.OnListDownloadedCallback){
                        callback = (ApplicationHub.OnListDownloadedCallback)msg.obj;
                        callback.onSuccessDownload(callback.getList());
                    }
                    break;
                case IMAGE_DOWNLOADED:
                    if(msg.obj instanceof Downloader.DownloadImageTask){
                        Downloader.DownloadImageTask task = (Downloader.DownloadImageTask)msg.obj;
                        task.onSuccess();
                    }
                    break;
            }
        }
    }

    public static final int LIST_DOWNLOADED = 1;
    public static final int IMAGE_DOWNLOADED = 2;


    //------------------------db handler part---------------------------------
    public class DbHandler extends Handler{
        public DbHandler(Looper looper){
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {

            switch(msg.what){
                case INSERT_USER:
                    if(msg.obj instanceof VKApiUser){
                        VKApiUser user = (VKApiUser)msg.obj;
                        if(_isUserExist(user.id)){
                            _updateUser(user);
                        }else{
                            _insertUser(user);
                        }
                    }
                    break;
                case INSERT_ALBUM:
                    if(msg.obj instanceof VKApiPhotoAlbum){
                        VKApiPhotoAlbum album = (VKApiPhotoAlbum)msg.obj;
                        if(_isAlbumExist(album.id)){
                            _updateAlbum(album);
                        }else{
                            _insertAlbum(album);
                        }
                    }
                    break;
                case INSERT_PHOTO:
                    if(msg.obj instanceof VKApiPhoto){
                        VKApiPhoto photo = (VKApiPhoto)msg.obj;
                        if(_isPhotoExist(photo.id)){
                            _updatePhoto(photo);
                        }else{
                            _insertPhoto(photo);
                        }
                    }
                    break;

                case SELECT_ALBUMS:
                    if(msg.obj instanceof OnListDownloadedCallback){
                        OnListDownloadedCallback callback = (OnListDownloadedCallback)msg.obj;
                        List<VKApiPhotoAlbum> list = albumsCursorToList(getAlbums());
                        if(list != null && list.size() > 0){
                            callback.saveList(list);
                            Message msgToUI = mUIHandler.obtainMessage(LIST_DOWNLOADED, callback);
                            msgToUI.sendToTarget();
                        }
                    }
                    break;
                case SELECT_PHOTOS:
                    Log.d(TAG,"SELECT_PHOTOS");
                    if(msg.obj instanceof OnListDownloadedCallback){
                        OnListDownloadedCallback callback = (OnListDownloadedCallback)msg.obj;
                        List<VKApiPhoto> list = photoCursorToList(getPhotos(msg.arg1));
                        Log.d(TAG,"SELECT_PHOTOS list size: " + list.size());
                        callback.saveList(list);
                        Message msgToUI = mUIHandler.obtainMessage(LIST_DOWNLOADED, callback);
                        msgToUI.sendToTarget();
                    }

                    break;

                case UPDATE_IMG_PHOTO:
                    if(_isPhotoExist(msg.arg1)){
                        Log.d(TAG , "msg.arg1: " + msg.arg1);
                        Bitmap img = Downloader.getInstance(mContext).findInCache(msg.arg1);
                        if(img == null)
                            Log.d(TAG , "Not in cache");
                        _addPhotoData(msg.arg1, img);
                    }
                    break;

                case UPDATE_IMG_ID_ALBUM:
                    if(_isAlbumExist(msg.arg1) && _isPhotoExist(msg.arg2))
                        _updatePhotoIdnAlbum(msg.arg1 , msg.arg2);
                    break;

                case  ADD_PHOTO_TO_ALBUM:
                    if(!_isPhotoInAlbumExist(msg.arg1 , msg.arg2))
                        _addPhotoToAlbum(msg.arg2 , msg.arg1);
                    break;
                case SELECT_PHOTO_DATA:
                    if(msg.obj instanceof Downloader.DownloadImageTask){
                        Downloader.DownloadImageTask task = (Downloader.DownloadImageTask)msg.obj;
                        if(_isPhotoExist(task.getId())){
                            byte[] blob = _selectPhotoData(task.getId());
                            if(blob != null){
                                Bitmap bitmap = BitmapFactory.decodeByteArray(blob, 0, blob.length);
                                addToCache("" + task.getId(), task.getId(), bitmap);
                                task.setBitmap(bitmap);
                                Message msgToUI = mUIHandler.obtainMessage(IMAGE_DOWNLOADED, task);
                                msgToUI.sendToTarget();
                            }
                        }
                    }
                    break;
                case SAVE_IMAGE_TO_DISC:
                    if(msg.obj instanceof Downloader.DownloadImageTask){
                        Downloader.DownloadImageTask task = (Downloader.DownloadImageTask)msg.obj;
                        _savePhotoToSD(task.getId(), task.getBitmap());
                    }
                    break;

                case LOAD_IMAGE_FROM_DISC:
                    if(msg.obj instanceof Downloader.DownloadImageTask){
                        Downloader.DownloadImageTask task = (Downloader.DownloadImageTask)msg.obj;
                        Bitmap bitmap = _getBitmapFromSD(task.getId());
                        task.setBitmap(bitmap);
                        Message msgToUI = mUIHandler.obtainMessage(IMAGE_DOWNLOADED, task);
                        msgToUI.sendToTarget();
                    }
                    break;

                case CLEAR:
                    Log.d("CLEAR" , "CLEAR FORM DB");
                    if(_isPhotoExist(msg.arg1)){
                        _deletePhoto(msg.arg1);
                        Log.d("CLEAR", "delete photo with id: " + msg.arg1);
                        _deletePhotoFromAlbums(msg.arg1);
                        List<VKApiPhotoAlbum> albums = _selectFitAlbums();
                        for (VKApiPhotoAlbum album : albums){
                            Log.d("CLEAR", " photo count: " + _selectPhotoCountInAlbum(album.id));
                            if(_selectPhotoCountInAlbum(album.id) == 0){
                                if(album.thumb_id != 0){
                                    _deletePhoto(album.thumb_id);
                                    Log.d("CLEAR", "delete photo with id: " + album.thumb_id);
                                    _deleteAlbum(album.id);
                                }
                            }
                        }
                    }

                    break;
            }
        }


        private List<VKApiPhotoAlbum> albumsCursorToList(Cursor cursor){
            List<VKApiPhotoAlbum> list = new ArrayList<>();
            if(cursor.moveToFirst()){
                do{
                    VKApiPhotoAlbum album = new VKApiPhotoAlbum();
                    album.title = cursor.getString(cursor.getColumnIndex(E_ALBUM_TITLE));
                    Log.d(TAG , "Selected album with title: " + album.title);
                    album.created = cursor.getLong(cursor.getColumnIndex(E_ALBUM_DATE));
                    album.id = cursor.getInt(cursor.getColumnIndex(E_ALBUM_ID));
                    album.thumb_id = cursor.getInt(cursor.getColumnIndex(E_ALBUM_PHOTO));
                    Log.d(TAG , "Selected album with E_ALBUM_PHOTO: " + album.thumb_id);
                    list.add(album);
                }while(cursor.moveToNext());
            }
            Log.d(TAG , "Selected album with size: " + list.size());
            for(VKApiPhotoAlbum item : list){
                Log.d(TAG , "In list album with id: " + item.id);

            }
            cursor.close();
            return list;
        }

        private List<VKApiPhoto> photoCursorToList(Cursor cursor){
            List<VKApiPhoto> list = new ArrayList<>();
            if(cursor.moveToFirst()){
                do{
                    Log.d(TAG , " photo cursor to list");
                    Downloader.PhotoWithCoordinates photo = new Downloader.PhotoWithCoordinates();
                    photo.date = cursor.getLong(cursor.getColumnIndex(E_PHOTO_DATE));
                    photo.id = cursor.getInt(cursor.getColumnIndex(E_PHOTO_ID));
                    photo.likes = cursor.getInt(cursor.getColumnIndex(E_PHOTO_LIKE_COUNT));
                    photo.user_likes = cursor.getInt(cursor.getColumnIndex(E_PHOTO_SELF_LIKE)) == 1;
                    photo.setOwnerName(cursor.getString(cursor.getColumnIndex(E_USER_NAME)));
                    photo.setLat(cursor.getString(cursor.getColumnIndex(E_PHOTO_LAT)));
                    photo.setLong(cursor.getString(cursor.getColumnIndex(E_PHOTO_LONG)));
                    photo.text = cursor.getString(cursor.getColumnIndex(E_PHOTO_TEXT));
                    list.add(photo);
                }while(cursor.moveToNext());
            }
            cursor.close();
            return list;
        }

    }

    private final int INSERT_USER = 1;
    private final int INSERT_ALBUM = 2;
    private final int INSERT_PHOTO = 3;
    private final int UPDATE_IMG_PHOTO = 4;
    private final int UPDATE_IMG_ID_ALBUM = 5;
    private final int ADD_PHOTO_TO_ALBUM = 6;
    private final int SELECT_ALBUMS = 7;
    private final int SELECT_PHOTOS = 8;
    private final int SELECT_PHOTO_DATA = 9;
    private final int SAVE_IMAGE_TO_DISC = 10;
    private final int LOAD_IMAGE_FROM_DISC = 11;
    private final int CLEAR = 12;

    //-----------------------------------api---------------------------------------


    @Override
    public void downloadAlbumList(OnListDownloadedCallback callback, String userId) {
        postToDBHandler(SELECT_ALBUMS, callback);
    }

    @Override
    public void downloadPicturesFromAlbum(OnListDownloadedCallback callback, String userId, int albumId) {
        Message msgToDb= mDbHandler.obtainMessage(SELECT_PHOTOS, callback);
        msgToDb.arg1 = albumId;
        msgToDb.sendToTarget();
    }


    @Override
    public void addPhotoData(int photoId){
        //  NOP
    }

    @Override
    public void insertPhoto(VKApiPhoto photo){
        // NOP
    }

    public void addPhotoToAlbum(int photoId , int albumId){
        // NOP
    }

    @Override
    public void savePhotoToSD(Downloader.DownloadImageTask task) {
        /// NOP
    }

    public void forOnlineSavePhotoToSD(Downloader.DownloadImageTask task){
        Message msgToBd = mDbHandler.obtainMessage(SAVE_IMAGE_TO_DISC, task);
        msgToBd.sendToTarget();
    }

    public void _savePhotoToSD(int photoId, Bitmap bitmap){
        Log.d("CLEAR" , "savePhotoToSD CALLED");
        File file = constructFileName(photoId);
        Log.d(TAG, "SAve image: " + file.getAbsolutePath());
//        if (!file.exists()){
            try {
                FileOutputStream out = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                out.flush();
                out.close();
                Log.d("CLEAR", "saved: " + photoId);
            } catch (IOException ex) {
                Log.d(TAG , "On save photo ex: " + ex.getMessage());
            }
//        }else {
//            Log.d(TAG, "Date time changed: " + file.setLastModified(1458678394000L));
//        }
        clearPhotosFromSD();
    }

    private void clearPhotosFromSD(){

        String root = Environment.getExternalStorageDirectory().toString();
        File myDir = new File(root + "/" + mContext.getResources().getString(R.string.app_name));
        myDir.mkdirs();
        File fileToDelete = null;
        if(myDir.list().length > SAVE_LIMIT){
            Log.d("CLEAR", " LIMIT overflow");
            for(File f : myDir.listFiles()){
                fileToDelete = f;
                break;
            }
        }
        if(fileToDelete != null){
            Log.d("CLEAR", " file " + fileToDelete + " deleted: " + fileToDelete.delete());
            String photoIdAsString = fileToDelete.getName();
            Log.d(TAG , "Photo name to delete: " + photoIdAsString);
            try{
                int photoId = Integer.parseInt(fileToDelete.getName().substring(0 , photoIdAsString.length() - 4));
                Log.d(TAG , "Photo id to delete: " + photoId);
                clearPhoto(photoId);
            }catch (NumberFormatException ex){
                Log.d(TAG, " PARSE error: " + ex.getMessage());
            }

        }

    }

    private File constructFileName(int photoId){
        String root = Environment.getExternalStorageDirectory().toString();
        File myDir = new File(root + "/" + mContext.getResources().getString(R.string.app_name));
        myDir.mkdirs();
        String fileName = photoId +".jpg";
        return new File (myDir, fileName);
    }

    public Bitmap _getBitmapFromSD(int photoId){
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(constructFileName(photoId).getAbsolutePath(), options);
    }


    public void forOnlineAddPhotoToAlbum(int photoId , int albumId){
        Message msgToDb = mDbHandler.obtainMessage(ADD_PHOTO_TO_ALBUM);
        msgToDb.arg1 = photoId;
        msgToDb.arg2 = albumId;
        msgToDb.sendToTarget();
    }


    public void forOnlineInsertPhoto(VKApiPhoto photo){
        postToDBHandler(INSERT_PHOTO, photo);
    }

    public void clearPhoto(int photoId){
        Message msgToDb = mDbHandler.obtainMessage(CLEAR);
        msgToDb.arg1 = photoId;
        msgToDb.sendToTarget();
    }

    public void forOnlineAddPhotoData(int photoId){
        Message msg = mDbHandler.obtainMessage(UPDATE_IMG_PHOTO);
        msg.arg1 = photoId;
        msg.sendToTarget();
    }

    public void forOnlineUpdateIdPhotoImage(int albumId , int PhotoId){
        Message mshToDb = mDbHandler.obtainMessage(UPDATE_IMG_ID_ALBUM);
        mshToDb.arg1 = albumId;
        mshToDb.arg2 = PhotoId;
        mshToDb.sendToTarget();
    }


    @Override
    public void downLoadImage(Downloader.DownloadImageTask task , boolean drawEmpty) {

        task.getProgressBar().setVisibility(View.GONE);
        if(drawEmpty)
            task.getImageView().setImageResource(R.drawable.m_noalbum);

        if(task.getSizeFlag() == Downloader.DownloadImageTask.IMG_SIZE_SMALL){
            Bitmap img = mCache.getBitmapById(task.getId());
            if(img == null){
                Message msgToDb = mDbHandler.obtainMessage(SELECT_PHOTO_DATA, task);
                msgToDb.sendToTarget();
            }else{
                task.setBitmap(img);
                Message msgToUI = mUIHandler.obtainMessage(IMAGE_DOWNLOADED, task);
                msgToUI.sendToTarget();
            }
        }else{
            Message msgToDb = mDbHandler.obtainMessage(LOAD_IMAGE_FROM_DISC, task);
            msgToDb.sendToTarget();
        }
    }




    public void insertUser(VKApiUser user){
        postToDBHandler(INSERT_USER, user);
    }

    public void insertAlbum(VKApiPhotoAlbum album){
        postToDBHandler(INSERT_ALBUM, album);
    }




    //--------------------------------------------user part----------------------------------------
    private void _updateUser(VKApiUser user){
        ContentValues values = new ContentValues();
        values.put(E_USER_NAME, user.first_name);
        int i = getWritableDatabase().update(T_USER, values
                , E_USER_ID + " =  ? ", new String[]{"" + user.id});
        Log.d(TAG, "User updated " + i);
    }

    private void _insertUser(VKApiUser user){
        ContentValues values = new ContentValues();
        values.put(E_USER_ID, user.id);
        values.put(E_USER_NAME, user.first_name);
        long i = getWritableDatabase().insert(T_USER, null, values);
        Log.d(TAG, "Insert user id: " + user.id + " fn: " + user.first_name + " success: " + (i != -1));
    }

    private boolean _isUserExist(int userId){
        boolean exist = false;
        Cursor cursor = getReadableDatabase().rawQuery(Q_SELECT_USER, new String[]{"" + userId});
        if(cursor.moveToFirst()) exist = true;
        cursor.close();
        Log.d(TAG, "User with id: " +userId +" is "+exist);
        return exist;
    }

    //--------------------------------------album part------------------------------------
    private void _updateAlbum(VKApiPhotoAlbum album){
        ContentValues values = new ContentValues();
        values.put(E_ALBUM_TITLE, album.title);
        int i = getWritableDatabase().update(T_ALBUM, values
                , E_ALBUM_ID + " =  ? ", new String[]{"" + album.id});
        Log.d(TAG, "Album updated id:" +album.id + " E_ALBUM_TITLE: " + album.title );
    }

    private void _updatePhotoIdnAlbum(int albumId, int photoId){
        ContentValues values = new ContentValues();
        values.put(E_ALBUM_PHOTO, photoId);
        int i = getWritableDatabase().update(T_ALBUM, values
                , E_ALBUM_ID + " =  ? ", new String[]{"" + albumId});
        Log.d(TAG, "Album image id updated to:" + photoId + " success? " + (i != 0));
    }

    private void _insertAlbum(VKApiPhotoAlbum album){
        ContentValues values = new ContentValues();
        values.put(E_ALBUM_ID, album.id);
        values.put(E_ALBUM_TITLE, album.title);
        values.put(E_ALBUM_DATE, album.created);
        values.put(E_ALBUM_PHOTO, album.thumb_id);
        long i = getWritableDatabase().insert(T_ALBUM, null, values);
        Log.d(TAG, "Insert album id: " + album.id + " title: " + album.title + " success: " + (i != -1));
    }


    private List<VKApiPhotoAlbum> _selectFitAlbums(){ // for clear prepare
        List<VKApiPhotoAlbum> list = new ArrayList<>();
        Cursor cursor = getReadableDatabase().rawQuery(Q_SELECT_SLIM_ALBUMS, null);
        if(cursor.moveToFirst()){
            do{
                VKApiPhotoAlbum album = new VKApiPhotoAlbum();
                album.id = cursor.getInt(cursor.getColumnIndex(E_ALBUM_ID));
                album.thumb_id = cursor.getInt(cursor.getColumnIndex(E_ALBUM_PHOTO));
//                list.add(album);
            }while(cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    private boolean _isAlbumExist(int albumId){
        boolean exist = false;
        Cursor cursor = getReadableDatabase().rawQuery(Q_SELECT_ALBUM, new String[]{"" + albumId});
        if(cursor.moveToFirst()) exist = true;
        cursor.close();
        Log.d(TAG, "check album id: " + albumId + " exist? " + exist);
        return exist;
    }

    //-----------------------------------------------photo part---------------------------------------


    private void _addPhotoToAlbum(int photoId, int albumId){
        ContentValues values = new ContentValues();
        values.put(E_ALBUM_PHOTO_FROM, albumId);
        values.put(E_ALBUM_PHOTO_TO, photoId);
        long i = getWritableDatabase().insert(T_ALBUM_PHOTO, null, values);
        Log.d(TAG, "Add photo id: " + photoId + " to album id: " + albumId + " success? " + (i != -1));
    }

    private void _deletePhotoFromAlbums(int photoId){
        int i = getWritableDatabase().delete(T_ALBUM_PHOTO
                , E_ALBUM_PHOTO_TO + " = ? ", new String[]{"" + photoId});
        Log.d(TAG, "DELETED photos from album photo id: " + photoId + " deleted: " + i);
    }

    private void _deleteAlbum(int id){
        int i = getWritableDatabase().delete(T_ALBUM
                , E_ALBUM_ID + " = ? ", new String[]{"" + id});
        Log.d(TAG, "DELETED Album with id: " + id + " success: " + (i == 1));
    }

    private void _updatePhoto(VKApiPhoto photo){
        ContentValues values = new ContentValues();
        values.put(E_PHOTO_ID, photo.id);
        values.put(E_PHOTO_OWNER, photo.owner_id);
        values.put(E_PHOTO_DATE, photo.date);
        values.put(E_PHOTO_LIKE_COUNT, photo.likes);
        values.put(E_PHOTO_SELF_LIKE, photo.user_likes ? 1 : 0);
        if(photo instanceof Downloader.PhotoWithCoordinates){
            Downloader.PhotoWithCoordinates pwc = (Downloader.PhotoWithCoordinates)photo;
            values.put(E_PHOTO_LAT, pwc.getLat());
            values.put(E_PHOTO_LONG, pwc.getLong());
        }

        int i = getWritableDatabase().update(T_PHOTO, values
                , E_PHOTO_ID + " =  ? ", new String[]{"" + photo.id});
        Log.d(TAG, "Photo updated id: " + photo.id + " success? " + (i != 0));
    }

    private byte[] _selectPhotoData(int photoId){
        byte[] raw = null;
        Cursor cursor = getReadableDatabase().rawQuery(Q_SELECT_PHOTO_DATA, new String[]{"" + photoId});
        if(cursor.moveToFirst()){
            raw = cursor.getBlob(cursor.getColumnIndex(E_PHOTO_DATA));
        }
        Log.d(TAG, " SELECT PHOTO DATA id: " + photoId + " result: " + raw);
        cursor.close();
        return raw;
    }


    private void _insertPhoto(VKApiPhoto photo){
        ContentValues values = new ContentValues();
        values.put(E_PHOTO_ID, photo.id);
        values.put(E_PHOTO_OWNER, photo.owner_id);
        values.put(E_PHOTO_DATE, photo.date);
        values.put(E_PHOTO_LIKE_COUNT, photo.likes);
        values.put(E_PHOTO_TEXT, photo.text);

        if(photo instanceof Downloader.PhotoWithCoordinates){
            Downloader.PhotoWithCoordinates pwc = (Downloader.PhotoWithCoordinates)photo;
            values.put(E_PHOTO_LAT, pwc.getLat());
            values.put(E_PHOTO_LONG, pwc.getLong());
        }

        values.put(E_PHOTO_SELF_LIKE, photo.user_likes ? 1 : 0);
        long i = getWritableDatabase().insert(T_PHOTO, null, values);
        Log.d(TAG, "Insert E_PHOTO_ID: " + photo.id + " E_PHOTO_OWNER: " + photo.owner_id
                + " E_PHOTO_DATE: " + photo.date + " E_PHOTO_LIKE_COUNT " + photo.likes
                + " E_PHOTO_SELF_LIKE " + (photo.user_likes) + " success: " + (i != -1));
    }

    private boolean _isPhotoInAlbumExist(int photoId , int albumId){
        boolean exist = false;
        Cursor cursor = getReadableDatabase().rawQuery(Q_SELECT_ALBUM_PHOTO, new String[]{"" + photoId, "" + albumId});
        if(cursor.moveToFirst()) exist = true;
        cursor.close();
        return exist;
    }

    private void _deletePhoto(int id){
        int i = getWritableDatabase().delete(T_PHOTO
                , E_PHOTO_ID + " = ? ", new String[]{"" + id});
        Log.d(TAG, "DELETED Photo with id: " + id + " success: " + (i == 1));
    }

    private int _selectPhotoCountInAlbum(int albumId){
        Cursor cursor = getReadableDatabase().rawQuery(Q_SELECT_PHOTOS_COUNT_IN_ALBUM, new String[]{"" + albumId,});
        int count = cursor.getCount();
        cursor.close();
        return count;
    }

    private boolean _isPhotoExist(int photoId){
        boolean exist = false;
        Cursor cursor = getReadableDatabase().rawQuery(Q_SELECT_PHOTO, new String[]{"" + photoId});
        if(cursor.moveToFirst()) exist = true;
        cursor.close();
        Log.d("CLEAR" , " photo id : " + photoId + (exist));
        return exist;
    }

    private void _addPhotoData(int id, Bitmap bitmap){
        Log.d(TAG, "_addPhotoData called bitmap == null? " + (bitmap == null));
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        if(bitmap == null) return;
        bitmap.compress(Bitmap.CompressFormat.PNG, 0, blob);
        ContentValues values = new ContentValues();
        values.put(E_PHOTO_DATA, blob.toByteArray());
        int i = getWritableDatabase().update(T_PHOTO, values
                , E_PHOTO_ID + " =  ? ", new String[]{"" + id});
        Log.d(TAG, "Photo data updated id: " + id + " l: " + blob.toByteArray().length + " success? " + (i != 0) );
    }

    //-----------------------------------cursor part------------------------------------------------
    public Cursor getAlbums(){
        return getReadableDatabase().rawQuery(Q_SELECT_ALL_ALBUMS, null);
    }

    public Cursor getPhotos(int albumId){
//        return getReadableDatabase().rawQuery(Q_SELECT_ALL_PHOTOS_IN_ALBUM, null);
        return getReadableDatabase().rawQuery(Q_SELECT_ALL_PHOTOS_IN_ALBUM, new String[]{"" + albumId});
    }



    //-------------------------------sql part------------------------------

    public final static String T_ALBUM = "album";
    public final static String E_ALBUM_ID = "_id";
    public final static String E_ALBUM_TITLE = "title";
    public final static String E_ALBUM_DATE = "date";
    public final static String E_ALBUM_PHOTO = "photo_id";

    public final static String T_USER = "user";
    public final static String E_USER_ID = "_id";
    public final static String E_USER_NAME = "name";

    public final static String T_PHOTO = "photo";
    public final static String E_PHOTO_ID = "_id";
    public final static String E_PHOTO_DATE = "date";
    public final static String E_PHOTO_DATA = "data";
    public final static String E_PHOTO_TEXT = "text";
    public final static String E_PHOTO_OWNER = "owner";
    public final static String E_PHOTO_LIKE_COUNT = "like_count";
    public final static String E_PHOTO_SELF_LIKE = "liked";
    public final static String E_PHOTO_LAT = "lat";
    public final static String E_PHOTO_LONG = "long";



    public final static String T_ALBUM_PHOTO = "album_photo";
    public final static String E_ALBUM_PHOTO_FROM = "album_id";
    public final static String E_ALBUM_PHOTO_TO = "photo_id";


    private final static String Q_CREATE_ALBUM =
            "CREATE TABLE " +T_ALBUM+ " ( "
                    +E_ALBUM_ID+ " INTEGER PRIMARY KEY, "
                    +E_ALBUM_TITLE+ " VARCHAR(128) , "
                    +E_ALBUM_DATE+ " DATETIME NOT NULL, "
                    +E_ALBUM_PHOTO+ " INTEGER NOT NULL DEFAULT 0 "
                    +")";

    private final static String Q_CREATE_USER =
            "CREATE TABLE " +T_USER+ " ( "
                    +E_USER_ID+ " INTEGER PRIMARY KEY, "
                    +E_USER_NAME+ " VARCHAR(128) NOT NULL  "
                    +")";

    private final static String Q_CREATE_PHOTO =
            "CREATE TABLE " +T_PHOTO+ " ( "
                    +E_PHOTO_ID+ " INTEGER PRIMARY KEY, "
                    +E_PHOTO_DATE+ " DATETIME NOT NULL, "
                    +E_PHOTO_DATA+ " BLOB , "
                    +E_PHOTO_TEXT+ " VARCHAR , "
                    +E_PHOTO_OWNER + " INTEGER , "
                    +E_PHOTO_LAT + " VARCHAR(16) , "
                    +E_PHOTO_LONG + " VARCHAR(16) , "
                    +E_PHOTO_LIKE_COUNT+ " INTEGER NOT NULL DEFAULT 0, "
                    +E_PHOTO_SELF_LIKE+ " TINYINT(1) NOT NULL DEFAULT 0, "
                    +"FOREIGN KEY (" + E_PHOTO_OWNER + ") "
                    +"REFERENCES " +T_USER+ " ( " +E_USER_ID+ " ) "
                    +")";

    private final static String Q_CREATE_ALBUM_PHOTO =
            "CREATE TABLE " +T_ALBUM_PHOTO+ " ( "
                    +E_ALBUM_PHOTO_FROM+ " INTEGER , "
                    +E_ALBUM_PHOTO_TO+ " INTEGER , "
                    +"PRIMARY KEY (" +E_ALBUM_PHOTO_FROM+ " , " +E_ALBUM_PHOTO_TO+ "), "
                    +"FOREIGN KEY (" +E_ALBUM_PHOTO_FROM+ ") "
                    +"REFERENCES " +T_ALBUM+ " ( " +E_ALBUM_ID+ " ), "
                    +"FOREIGN KEY (" + E_ALBUM_PHOTO_TO + ") "
                    +"REFERENCES " +T_PHOTO+ " ( " +E_PHOTO_ID+ " ) "
                    +")";



    private static final String Q_INSERT_NULL_USER =
            "INSERT INTO " +T_USER+ "("+E_USER_ID+ ", " +E_USER_NAME+
                    ") VALUES (0 , 'null')";

    private static final String Q_INSERT_NULL_PHOTO =
            "INSERT INTO " +T_PHOTO+ "("+E_PHOTO_ID+ ", " +E_PHOTO_DATE+ ", " +E_PHOTO_SELF_LIKE+
                    ", " +E_PHOTO_LIKE_COUNT+ ", " +E_PHOTO_OWNER +
                    ") VALUES (0 , 0 , 0 , 0, 0)";

    private static final String Q_SELECT_USER =
            "SELECT  " +E_USER_ID+ " AS " +E_USER_ID+ " FROM " +T_USER+ " WHERE " + E_USER_ID + " = ? ";

    private static final String Q_SELECT_PHOTO =
            "SELECT  " +E_PHOTO_ID+ " AS " +E_PHOTO_ID+ " FROM " +T_PHOTO+ " WHERE " + E_PHOTO_ID + " = ? ";

    private static final String Q_SELECT_ALBUM =
            "SELECT  " +E_ALBUM_ID+ " AS " +E_ALBUM_ID+" FROM " +T_ALBUM+ " WHERE " + E_ALBUM_ID + " = ? ";

    private static final String Q_SELECT_ALBUM_PHOTO =
            "SELECT " +E_ALBUM_PHOTO_FROM+ " AS " +E_ALBUM_PHOTO_FROM+" FROM " +T_ALBUM_PHOTO+
                    " WHERE " + E_ALBUM_PHOTO_FROM + " = ? AND " +E_ALBUM_PHOTO_TO+ " = ? ";

//    private static final String Q_SELECT_ALL_ALBUMS =
//            "SELECT a." +E_ALBUM_TITLE+ " AS " +E_ALBUM_TITLE+
//                    ", a." +E_ALBUM_DATE+ " AS " +E_ALBUM_DATE+
//                    ", a." +E_ALBUM_ID+ " AS " +E_ALBUM_ID+
//                    ", a." +E_ALBUM_PHOTO+ " AS " +E_ALBUM_PHOTO+
//                    ", p." +E_PHOTO_DATA + " AS " +E_PHOTO_DATA+
//                    " FROM " +T_ALBUM + " a "+
//                    " INNER JOIN " +T_PHOTO + " p "+
//                    " ON p." +E_ALBUM_ID + " = a."+E_ALBUM_PHOTO;


    private static final String Q_SELECT_ALL_ALBUMS =
            "SELECT " +E_ALBUM_TITLE+ " AS " +E_ALBUM_TITLE+
                    ", " +E_ALBUM_DATE+ " AS " +E_ALBUM_DATE+
                    ", " +E_ALBUM_ID+ " AS " +E_ALBUM_ID+
                    ", " +E_ALBUM_PHOTO+ " AS " +E_ALBUM_PHOTO+
                    " FROM " +T_ALBUM;

    private static final String Q_SELECT_PHOTOS_COUNT_IN_ALBUM =
            "SELECT " +E_ALBUM_PHOTO_FROM+ " FROM " +T_ALBUM_PHOTO+
                    " WHERE " +E_ALBUM_PHOTO_FROM+ " = ?";



//    private static final String Q_SELECT_ALL_PHOTOS_IN_ALBUM =
//            "SELECT p." +E_PHOTO_DATE+ " AS " +E_PHOTO_DATE+
//                    ", p." +E_PHOTO_DATA+ " AS " +E_PHOTO_DATA+
//                    ", p." +E_PHOTO_ID+ " AS " +E_PHOTO_ID+
//                    ", p." +E_PHOTO_LIKE_COUNT + " AS " +E_PHOTO_LIKE_COUNT+
//                    ", p." +E_PHOTO_SELF_LIKE + " AS " +E_PHOTO_SELF_LIKE+
//                    ", p." +E_PHOTO_TEXT + " AS " +E_PHOTO_TEXT+
//                    ", p." +E_PHOTO_LAT + " AS " +E_PHOTO_LAT+
//                    ", p." +E_PHOTO_LONG + " AS " +E_PHOTO_LONG+
//                    ", u." +E_USER_NAME + " AS " +E_USER_NAME+
//                    " FROM " +T_ALBUM_PHOTO + " ap "+
//                    " INNER JOIN " +T_PHOTO + " p "+
//                    " ON ap." +E_ALBUM_PHOTO_TO + " = p."+E_PHOTO_ID+
//                    " INNER JOIN " +T_USER + " u "+
//                    " ON p." +E_PHOTO_OWNER + " = u."+E_USER_ID+
//                    " WHERE " +E_ALBUM_PHOTO_FROM+ " = ?";


    private static final String Q_SELECT_ALL_PHOTOS_IN_ALBUM =
            "SELECT p." +E_PHOTO_DATE+ " AS " +E_PHOTO_DATE+
                    ", p." +E_PHOTO_DATA+ " AS " +E_PHOTO_DATA+
                    ", p." +E_PHOTO_ID+ " AS " +E_PHOTO_ID+
                    ", p." +E_PHOTO_LIKE_COUNT + " AS " +E_PHOTO_LIKE_COUNT+
                    ", p." +E_PHOTO_SELF_LIKE + " AS " +E_PHOTO_SELF_LIKE+
                    ", p." +E_PHOTO_TEXT + " AS " +E_PHOTO_TEXT+
                    ", p." +E_PHOTO_LAT + " AS " +E_PHOTO_LAT+
                    ", p." +E_PHOTO_LONG + " AS " +E_PHOTO_LONG+
                    ", u." +E_USER_NAME + " AS " +E_USER_NAME+
                    " FROM " +T_ALBUM_PHOTO + " ap "+
                    " INNER JOIN " +T_PHOTO + " p "+
                    " ON ap." +E_ALBUM_PHOTO_TO + " = p."+E_PHOTO_ID+
                    " INNER JOIN " +T_USER + " u "+
                    " ON p." +E_PHOTO_OWNER + " = u."+E_USER_ID+
                    " WHERE " +E_ALBUM_PHOTO_FROM+ " = ?";


    private static final String Q_SELECT_PHOTO_DATA =
            "SELECT  " +E_PHOTO_DATA+ " AS " +E_PHOTO_DATA+
                    " FROM " +T_PHOTO+
                    " WHERE " +E_PHOTO_ID+ " = ?";

    private static final String Q_SELECT_SLIM_ALBUMS =             // for clear prepare
            "SELECT  " +E_ALBUM_ID+ " AS " +E_ALBUM_ID+
                    ", "+ E_ALBUM_PHOTO+ " AS " + E_ALBUM_PHOTO+
                    " FROM " +T_ALBUM;

//    private static final String Q_SELECT_ALL_PHOTOS_IN_ALBUM =
//            "SELECT  " +E_ALBUM_PHOTO_FROM+ " AS " +E_ALBUM_PHOTO_FROM+
//                    ", " +E_ALBUM_PHOTO_TO+ " AS " +E_ALBUM_PHOTO_TO+
//                    " FROM " +T_ALBUM_PHOTO;

}
