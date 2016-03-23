package com.noklin.vkphotomanager;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.noklin.vkphotomanager.data.ApplicationHub;
import com.noklin.vkphotomanager.data.Database;
import com.noklin.vkphotomanager.data.Downloader;
import com.vk.sdk.api.model.VKApiPhoto;
import com.vk.sdk.api.model.VKApiPhotoSize;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class PhotoPagerActivity extends AppCompatActivity{
    private final static String TAG =  PhotoPagerActivity.class.getSimpleName();

    private ViewPager mViewPager;
    private List<VKApiPhoto> mPhotos;
    private String mUserId = "";
    private int mAlbumId;
    private int mCurrentItem;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent extra = getIntent();
        if(savedInstanceState == null){
            mCurrentItem = extra.getIntExtra(EX_CURRENT_POSITION, -1);
        }else{
            mCurrentItem = savedInstanceState.getInt(EX_CURRENT_POSITION , -1);
        }

        mUserId = extra.getStringExtra(EX_USER_ID);
        mAlbumId = extra.getIntExtra(EX_ALBUM_ID, -1);
        if(mUserId.isEmpty() || mAlbumId < 0 || mCurrentItem < 0){
            onIllegalExtras();
        }

        mViewPager = new ViewPager(this);
        mViewPager.setId(R.id.viewPager);
        setContentView(mViewPager);
        initAdapter();
    }

    private void onIllegalExtras(){
        Log.d(TAG, "Illegal extras");
        finish();
    }


    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EX_CURRENT_POSITION, mCurrentItem);
    }

    private void initAdapter(){


        ApplicationHub.OnListDownloadedCallback callback = new ApplicationHub.OnListDownloadedCallback() {
            private List mSaved;
            @Override
            public void onSuccessDownload(List itemList) {
                initAdapter(itemList);
            }

            @Override
            public void onFailedDownload() {
                initAdapter();
            }

            @Override
            public void saveList(List itemList) {
                mSaved = itemList;
            }

            @Override
            public List getList() {
                return mSaved;
            }
        };
        MainActivity.getApplicationHub(getApplication()).downloadPicturesFromAlbum(callback, mUserId, mAlbumId);
    }

    private void initAdapter(List<VKApiPhoto> list){
        mPhotos = list;
        FragmentManager fm = getSupportFragmentManager();
        mViewPager.setAdapter(new FragmentStatePagerAdapter(fm) {
            @Override
            public Fragment getItem(int position) {
                VKApiPhoto ph = mPhotos.get(position);
                Log.d(TAG, " photo id:" + ph.id);
                MainActivity.getApplicationHub(getApplication()).insertPhoto(ph);
                MainActivity.getApplicationHub(getApplication()).addPhotoData(ph.id);
                MainActivity.getApplicationHub(getApplication()).addPhotoToAlbum(ph.id , mAlbumId);

                return DetailImageFragment.newInstance(mPhotos.get(position), getMaxSizeUrl(ph));
            }

            @Override
            public int getCount() {
                return mPhotos.size();
            }
        });

        mViewPager.setCurrentItem(mCurrentItem);
        mViewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

            }

            @Override
            public void onPageSelected(int position) {
                mCurrentItem = position;
            }


            @Override
            public void onPageScrollStateChanged(int state) {
            }
        });
    }

    private String getMaxSizeUrl(VKApiPhoto photo){
        String url = "";
        for(VKApiPhotoSize s :  photo.src){
            url = s.src;
        }

        return url;
    }




    //-----------------------------constants-----------------------------------

    public static String EX_CURRENT_POSITION = "ex-current-position";
    public static String EX_USER_ID = "ex-user-id";
    public static String EX_ALBUM_ID = "ex-album-id";
    public static String EX_MODE = "mode";


    //----------------------------image fragment--------------------------------

    public static class DetailImageFragment extends Fragment{
        private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm" , Locale.getDefault()) ;
        private final static String TAG = DetailImageFragment.class.getSimpleName();
        private static int count = 0;

        public DetailImageFragment(){
        }


        public static DetailImageFragment newInstance(VKApiPhoto photo, String url){
            DetailImageFragment instance = new DetailImageFragment();
            Bundle args = new Bundle();
            args.putInt(Database.E_PHOTO_ID, photo.id);
            args.putInt(Database.E_PHOTO_OWNER, photo.owner_id);
            args.putLong(Database.E_PHOTO_DATE, photo.date);
            args.putInt(Database.E_PHOTO_LIKE_COUNT, photo.likes);
            args.putBoolean(Database.E_PHOTO_SELF_LIKE, photo.user_likes);
            String lat = "";
            String llong = "";
            if(photo instanceof Downloader.PhotoWithCoordinates){
                Downloader.PhotoWithCoordinates photoWithCoordinates =
                        (Downloader.PhotoWithCoordinates)photo;
                lat = photoWithCoordinates.getLat();
                llong = photoWithCoordinates.getLong();
            }
            args.putString(Database.E_PHOTO_LAT, lat);
            args.putString(Database.E_PHOTO_LONG, llong);
            args.putString("URL", url);
            instance.setArguments(args);
            return instance;
        }



        private int mPhotoId;
        private int mOwnerId;
        private long mDate;
        private int mLikeCount;
        private boolean mSelfLike;
        private String mUrl;
        private String mLat;
        private String mLong;


        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            Bundle args = getArguments();
            mPhotoId = args.getInt(Database.E_PHOTO_ID, -1);
            mOwnerId = args.getInt(Database.E_PHOTO_OWNER, -1);
            mDate = args.getLong(Database.E_PHOTO_DATE, -1);
            mLikeCount = args.getInt(Database.E_PHOTO_LIKE_COUNT, -1);
            mSelfLike = args.getBoolean(Database.E_PHOTO_SELF_LIKE, false);
            mLat = args.getString(Database.E_PHOTO_LAT);
            mLong = args.getString(Database.E_PHOTO_LONG);
            mUrl = getArguments().getString("URL");
        }

        @Nullable
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View root = inflater.inflate(R.layout.fragment_detail_photo, container,  false);
            root.setTag(nextTag());
            ProgressBar progressBar = (ProgressBar)root.findViewById(R.id.progressBar);
            TextView created = (TextView)root.findViewById(R.id.created);
//            TextView owner = (TextView)root.findViewById(R.id.owner);
            TextView likes = (TextView)root.findViewById(R.id.likes);
            TextView show_in_map = (TextView)root.findViewById(R.id.show_in_map);
            show_in_map.setVisibility(View.GONE);

            if(!mLat.isEmpty() && !mLong.isEmpty()){
                show_in_map.setVisibility(View.VISIBLE);
                show_in_map.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Uri gmmIntentUri = Uri.parse("google.streetview:cbll="+mLat+","+mLong);
                        Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
                        startActivity(mapIntent);
                    }
                });
            }

            likes.setText("Likes: " + mLikeCount);

            String formatDate = mDateFormat.format(mDate * 1000l);
            if(!created.getText().equals(formatDate)){
                created.setText(formatDate);
            }
            ImageView image = (ImageView)root.findViewById(R.id.image);
            Downloader.DownloadImageTask task = new Downloader.DownloadImageTask(
                    getContext(), root.getTag() , image , progressBar, mPhotoId,mUrl
                    , Downloader.DownloadImageTask.IMG_SIZE_BIG);

            MainActivity.getApplicationHub(getContext()).downLoadImage(task, false);
            return root;

        }

        private Object nextTag(){
            return count++ % 3;
        }

    }

}