package com.noklin.vkphotomanager;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.noklin.vkphotomanager.data.ApplicationHub;
import com.noklin.vkphotomanager.data.Database;
import com.noklin.vkphotomanager.data.Downloader;
import com.vk.sdk.VKAccessToken;
import com.vk.sdk.VKCallback;
import com.vk.sdk.VKSdk;
import com.vk.sdk.api.VKError;
import com.vk.sdk.api.model.VKApiPhotoAlbum;

import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity{
    private final String TAG = MainActivity.class.getSimpleName();
    private AlbumsFragment mAlbumsFragment;
    private static int mMode = 0;
    private boolean mOnLogout = false;

    public static ApplicationHub getApplicationHub(Context context){
        if(mMode == Application.ONLINE_MODE){
            return Downloader.getInstance(context);
        }else if(mMode == Application.OFFLINE_MODE){
            return Database.getInstance(context);
        }else {
            throw new IllegalStateException("Unknown launch mode");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        MenuItem logout = menu.findItem(R.id.logout);
        if(mMode == Application.ONLINE_MODE){
            logout.setVisible(true);
        }else{
            logout.setVisible(false);
        }


        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        if(item.getItemId() == R.id.logout){
            VKSdk.logout();
            mAlbumsFragment.clear();
            getSupportFragmentManager().beginTransaction().remove(mAlbumsFragment).commit();
            mOnLogout = true;
            addFragment();

        }


        return super.onOptionsItemSelected(item);
    }

    public static int getLaunchMode(){
        return mMode;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mMode = isInternetAvailable() ? Application.ONLINE_MODE : Application.OFFLINE_MODE;
        addFragment();
    }




    private void addFragment(){
        String tag = AlbumsFragment.class.getSimpleName();
        FragmentManager fm = getSupportFragmentManager();
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if(fragment == null || mOnLogout){
            mAlbumsFragment = new AlbumsFragment();
            fm.beginTransaction().add(R.id.album_fragment_container
                    , mAlbumsFragment, tag).commit();
        }else{
            mAlbumsFragment = (AlbumsFragment) fragment;
        }
        mOnLogout = false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        VKSdk.onActivityResult(requestCode, resultCode, data, new VKCallback<VKAccessToken>() {
            @Override
            public void onResult(VKAccessToken res) {
                Log.d(TAG, "onResult");
                mAlbumsFragment.initAlbums(res.userId);

            }

            @Override
            public void onError(VKError error) {
                Log.d(TAG, "onError");
                finish();
            }


        });
        super.onActivityResult(requestCode, resultCode, data);
    }

    public  boolean isInternetAvailable(){
        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnectedOrConnecting();
    }


    // -----------------------ALBUMS FRAGMENT----------------------

    public final static class AlbumsFragment extends Fragment {
        private ProgressBar mProgressBar;
        private final static String TAG = AlbumsFragment.class.getSimpleName();


        private static final String IS_LOGGING = "logging";
        private boolean mLoggingIsActive = false;
        private GridView mGridView;
        private String mUserId;
        private AlbumAdapter mAdapter;

        public void clear(){
            mAdapter.clear();
        }

        private AdapterView.OnItemClickListener mAlbumClickListener = new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                VKApiPhotoAlbum album = mAdapter.getItem(position);
                if(album == null){
                    Log.d(TAG , "INVALID album position: " + position);
                    return;
                }
                if(MainActivity.getLaunchMode() == Application.ONLINE_MODE){
                    Database.getInstance(getContext()).insertAlbum(album);
//                    if(album.thumb_id != 0){
//                        Database.getInstance(getContext()).addPhotoData(album.thumb_id);
//                    }
                }

                Intent intent = new Intent(getActivity() , PhotoAlbumActivity.class);
                Bundle args = new Bundle();
//                args.putInt(PhotoAlbumActivity.EX_LAUNCH_MODE , Application.ONLINE_MODE);
                args.putInt(PhotoAlbumActivity.EX_ALBUM_ID , album.getId());
                args.putString(PhotoAlbumActivity.EX_ALBUM_TITLE, album.title);

                args.putString(PhotoAlbumActivity.EX_USER_ID, mUserId);
                args.putInt(PhotoAlbumActivity.EX_ALBUM_PHOTO_ID, album.thumb_id);
                intent.putExtras(args);
                startActivity(intent);
            }
        };

        public void setupAdapter(List<VKApiPhotoAlbum> albums){
            if(getActivity() == null || mGridView == null) return;
            mAdapter = new AlbumAdapter(albums);
            mGridView.setAdapter(mAdapter);
            mGridView.setOnItemClickListener(mAlbumClickListener);
            mProgressBar.setVisibility(View.GONE);
        }


        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            if(savedInstanceState != null)
                mLoggingIsActive = savedInstanceState.getBoolean(IS_LOGGING);
            View root = inflater.inflate(R.layout.fragment_albums, container, false);
            mGridView = (GridView)root.findViewById(R.id.gridView);
            mProgressBar = (ProgressBar)root.findViewById(R.id.progressBar);
            VKAccessToken token = VKAccessToken.currentToken();
            if(token != null)
                mUserId = token.userId;
            initAlbums(mUserId);
            return root;
        }

        @Override
        public void onDestroyView() {
            Downloader.getInstance(getActivity()).clear();
            super.onDestroyView();
        }

        @Override
        public void onSaveInstanceState(Bundle outState) {
            outState.putBoolean(IS_LOGGING, mLoggingIsActive);
            super.onSaveInstanceState(outState);
        }

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            Log.d(TAG, "F onCreate()");
            super.onCreate(savedInstanceState);

        }

        public void initAlbums(final String userId) {

            if (userId == null && !mLoggingIsActive && mMode == Application.ONLINE_MODE) {
                mLoggingIsActive = true;
                VKSdk.login(getActivity(), "photos");
                return;
            } else {
                mUserId = userId;
            }

            ApplicationHub.OnListDownloadedCallback callback = new ApplicationHub.OnListDownloadedCallback() {
                private List mSaved;
                @Override
                public void onSuccessDownload(List itemList) {
                    setupAdapter(itemList);
                }

                @Override
                public void onFailedDownload() {
                    initAlbums(userId);
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




            getApplicationHub(getContext()).downloadAlbumList(callback, mUserId);
        }
        //-----------------------------------adapter part----------------------------------------
        public final class AlbumAdapter extends ArrayAdapter<VKApiPhotoAlbum>{
            public AlbumAdapter(List<VKApiPhotoAlbum> albums) {
                super(getActivity(),0, albums);
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {

                if(convertView == null){
                    convertView = getActivity().getLayoutInflater().inflate(R.layout.photo_item, null);
                    convertView.setTag(UUID.randomUUID());
                }

                VKApiPhotoAlbum item = getItem(position);
                TextView title = (TextView)convertView.findViewById(R.id.title);
                ImageView image = (ImageView)convertView.findViewById(R.id.image);



                ProgressBar progressBar = (ProgressBar)convertView.findViewById(R.id.progressBar);
                if(!title.getText().equals(item.title)){
                    if(item.title.isEmpty()){
                        title.setVisibility(View.GONE);
                    }else{
                        title.setVisibility(View.VISIBLE);
                        title.setText(item.title);
                    }
                }

                progressBar.setVisibility(View.VISIBLE);
                image.setImageResource(R.drawable.m_noalbum);

                if(item.thumb_id == 0){
                    progressBar.setVisibility(View.GONE);
                }else {
                    Downloader.DownloadImageTask task = new Downloader.DownloadImageTask(
                            getContext(),  convertView.getTag(), image , progressBar, item.thumb_id , item.thumb_src, Downloader.DownloadImageTask.IMG_SIZE_SMALL);
                    getApplicationHub(getContext()).downLoadImage(task, true);
                }
                return convertView;
            }
        }
    }
}