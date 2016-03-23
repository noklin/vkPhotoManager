package com.noklin.vkphotomanager.data;


import com.vk.sdk.api.model.VKApiPhoto;

import java.util.List;

public interface ApplicationHub {

    void downloadAlbumList(OnListDownloadedCallback callback, String userId);
    void downloadPicturesFromAlbum(OnListDownloadedCallback callback, String userId, int albumId);
    void downLoadImage(Downloader.DownloadImageTask task , boolean drawEmpty);
    void addPhotoData(int id);
    void insertPhoto(VKApiPhoto photo);
    void addPhotoToAlbum(int photoId , int albumId);
    void savePhotoToSD(Downloader.DownloadImageTask task);

    interface OnListDownloadedCallback {
        void onSuccessDownload(List itemList);
        void onFailedDownload();
        void saveList(List itemList);
        List getList();
    }

}
