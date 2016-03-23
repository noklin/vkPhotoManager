package com.noklin.vkphotomanager;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.noklin.vkphotomanager.data.ApplicationHub;
import com.noklin.vkphotomanager.data.Downloader;
import com.vk.sdk.api.model.VKApiPhoto;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class PhotoAlbumActivity extends AppCompatActivity{
    private static final String TAG = PhotoAlbumActivity.class.getSimpleName();
    private String mUserId;
    private int mAlbumId;
    private int mPhotoId;
    private AlbumFragment mAlbumFragment;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Intent args = getIntent();
        mAlbumId = args.getIntExtra(EX_ALBUM_ID , -1);
        mPhotoId = args.getIntExtra(EX_ALBUM_PHOTO_ID , 0);
        setTitle(args.getStringExtra(EX_ALBUM_TITLE));
        if(mAlbumId == -1)
            onIllegalState("illegal album id");
        mUserId = args.getStringExtra(EX_USER_ID);
        if(mUserId == null)
            onIllegalState("user id is null");
        initFragment();

    }


    private void  onIllegalState(String error){
        Log.e(TAG, error);
        finish();
    }

    private void initFragment(){
        String tag = AlbumFragment.class.getSimpleName();
        FragmentManager fm = getSupportFragmentManager();
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if(fragment == null){
            mAlbumFragment = AlbumFragment.newInstance(mUserId, mAlbumId, mPhotoId);
            fm.beginTransaction().add(R.id.album_fragment_container
                    , mAlbumFragment, tag).commit();
        }else{
            mAlbumFragment = (AlbumFragment) fragment;
        }
    }


    // -------------------CONSTANTS-----------------------------
    public static final String EX_ALBUM_ID = "album_id";
    public static final String EX_ALBUM_TITLE = "album_title";
    public static final String EX_ALBUM_PHOTO_ID = "photo_id";
    public static final String EX_USER_ID = "user_id";

    //-------------------FRAGMENT PART------------------------------------------------

    public static class AlbumFragment extends Fragment{
        private GridView mGridView;
        private ProgressBar mProgressBar;
        private String mUserId;
        private int mAlbumId;
        private int mPhotoId;
        private PhotoAdapter mPhotoAdapter;


        AdapterView.OnItemClickListener mOnItemClickListener = new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Intent intent = new Intent(getActivity() , PhotoPagerActivity.class);
                intent.putExtra(PhotoPagerActivity.EX_CURRENT_POSITION , position);
                intent.putExtra(PhotoPagerActivity.EX_MODE , Application.ONLINE_MODE);
                intent.putExtra(PhotoPagerActivity.EX_USER_ID , mUserId);
                intent.putExtra(PhotoPagerActivity.EX_ALBUM_ID , mAlbumId);
                startActivity(intent);
            }
        };


        public static AlbumFragment newInstance(String userIn , int albumId, int photoId){
            AlbumFragment instance = new AlbumFragment();
            Bundle args = new Bundle();
            args.putString(EX_USER_ID , userIn);
            args.putInt(EX_ALBUM_ID, albumId);
            args.putInt(EX_ALBUM_PHOTO_ID, photoId);
            instance.setArguments(args);
            return instance;
        }

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            Bundle args = getArguments();
            mUserId = args.getString(EX_USER_ID);
            mAlbumId = args.getInt(EX_ALBUM_ID, -1);
            mPhotoId = args.getInt(EX_ALBUM_PHOTO_ID, -1);
        }

        @Nullable
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View root = inflater.inflate(R.layout.fragment_albums, container, false);
            mGridView = (GridView)root.findViewById(R.id.gridView);
            mProgressBar = (ProgressBar)root.findViewById(R.id.progressBar);
            mGridView.setOnItemClickListener(mOnItemClickListener);
            loadPhotos();
            return root;
        }

        private void loadPhotos(){
            ApplicationHub.OnListDownloadedCallback callback = new ApplicationHub.OnListDownloadedCallback(){
                private List mSaved;
                @Override
                public void onSuccessDownload(List itemList) {
                    setupAdapter(itemList);
                }

                @Override
                public void onFailedDownload() {
                    loadPhotos();
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

            MainActivity.getApplicationHub(getContext()).downloadPicturesFromAlbum(callback, mUserId, mAlbumId);
        }

        public void setupAdapter(List<VKApiPhoto> photos){
            if(getActivity() == null || mGridView == null) return;
            mPhotoAdapter = new PhotoAdapter(photos);
            mGridView.setAdapter(mPhotoAdapter);
            mProgressBar.setVisibility(View.GONE);
            VKApiPhoto photoToSave = null;
            for(VKApiPhoto photo : photos){
                if(photo.id == mPhotoId){
                    photoToSave = photo;
                }
            }

            if(photoToSave != null && mPhotoId != 0){
                MainActivity.getApplicationHub(getContext()).insertPhoto(photoToSave);
                MainActivity.getApplicationHub(getContext()).addPhotoData(mPhotoId);
//                MainActivity.getApplicationHub(getContext()).updateIdPhotoImage(mAlbumId , mPhotoId);
            }
        }



        public final class PhotoAdapter extends ArrayAdapter<VKApiPhoto> {

            private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm" , Locale.getDefault()) ;
            public PhotoAdapter(List<VKApiPhoto> albums) {
                super(getActivity(),0, albums);
            }


            @Override
            public View getView(int position, View convertView, ViewGroup parent) {

                if(convertView == null){
                    convertView = getActivity().getLayoutInflater().inflate(R.layout.photo_item, null);
                    convertView.setTag(UUID.randomUUID());
                }


                VKApiPhoto item = getItem(position);
                TextView title = (TextView)convertView.findViewById(R.id.title);
                TextView date = (TextView)convertView.findViewById(R.id.date);
                ImageView image = (ImageView)convertView.findViewById(R.id.image);


                Log.d(TAG , "item id: " + item.id);

                ProgressBar progressBar = (ProgressBar)convertView.findViewById(R.id.progressBar);
                if(!title.getText().equals(item.text)){
                    if(item.text.isEmpty()){
                        title.setVisibility(View.GONE);
                    }else{
                        title.setVisibility(View.VISIBLE);
                        title.setText(item.text);
                    }
                }

                String formatDate = mDateFormat.format(item.date * 1000l);
                if(!date.getText().equals(formatDate)){
                    date.setText(formatDate);
                }

//                progressBar.setVisibility(View.VISIBLE);
//                image.setImageResource(R.drawable.m_noalbum);

                if(item.photo_130 != null && item.photo_130.isEmpty()){
                    progressBar.setVisibility(View.GONE);
                }else{

                    /*
                    *
                    * vDownloader.DownloadImageTask task = new Downloader.DownloadImageTask(
                            getContext(),  convertView.getTag(), image , progressBar, item.thumb_id , item.thumb_src, Downloader.DownloadImageTask.IMG_SIZE_SMALL);
                    getApplicationHub(getContext()).downLoadImage(task, true);
                    * */
                    Downloader.DownloadImageTask task = new Downloader.DownloadImageTask(
                            getContext(), convertView.getTag(), image , progressBar, item.id
                            , item.photo_130 , Downloader.DownloadImageTask.IMG_SIZE_SMALL);

                    MainActivity.getApplicationHub(getContext()).downLoadImage(task, true);
                }
                return convertView;
            }
        }
    }
}




