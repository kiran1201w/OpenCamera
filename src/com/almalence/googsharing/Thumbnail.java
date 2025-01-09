package com.almalence.googsharing;

import java.io.*;
import java.net.URI;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import com.almalence.opencam.PluginManagerBase;
import com.almalence.util.Util;

public class Thumbnail {

    private static final String TAG = "Thumbnail";
    public static final String LAST_THUMB_FILENAME = "last_thumb";
    private static final int BUFSIZE = 4096;

    private Uri mUri;
    private Bitmap mBitmap;
    private Bitmap mFullBitmap;
    private boolean mFromFile = false;
    private static ContentResolver mResolver = null;

    public static final String DCIM = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DCIM).toString();
    public static final String DIRECTORY = DCIM + "/Camera";
    public static final String BUCKET_ID = String.valueOf(DIRECTORY.toLowerCase().hashCode());

    public Thumbnail(Uri uri, Bitmap bitmap, Bitmap fullBitmap, int orientation) {
        mUri = uri;
        mBitmap = rotateImage(bitmap, orientation);
        if (fullBitmap != null)
            mFullBitmap = rotateImage(fullBitmap, orientation);
        if (mBitmap == null)
            throw new IllegalArgumentException("null bitmap");
    }

    public Uri getUri() {
        return mUri;
    }

    public Bitmap getBitmap() {
        return mBitmap;
    }

    public Bitmap getFullBitmap() {
        if (mFullBitmap != null) return mFullBitmap;

        Media image = getLastImageThumbnail(mResolver);
        if (image == null) return null;

        try {
            Bitmap fullBitmap = Images.Media.getBitmap(mResolver, image.uri);
            mUri = image.uri;
            mFullBitmap = rotateImage(fullBitmap, image.orientation);
            if (mFullBitmap == null)
                throw new IllegalArgumentException("null bitmap");
        } catch (IOException e) {
            Log.e(TAG, "Error retrieving full bitmap", e);
        }

        return mFullBitmap;
    }

    public void setFromFile(boolean fromFile) {
        mFromFile = fromFile;
    }

    public boolean fromFile() {
        return mFromFile;
    }

    private static Bitmap rotateImage(Bitmap bitmap, int orientation) {
        if (orientation == 0) return bitmap;
        Matrix m = new Matrix();
        m.setRotate(orientation, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
        try {
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Exception t) {
            Log.w(TAG, "Failed to rotate thumbnail", t);
            return bitmap;
        }
    }

    public void saveTo(File file) {
        try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file), BUFSIZE))) {
            d.writeUTF(mUri.toString());
            mBitmap.compress(Bitmap.CompressFormat.JPEG, 90, d);
        } catch (IOException e) {
            Log.e(TAG, "Fail to store bitmap. path=" + file.getPath(), e);
        }
    }

    public static Thumbnail loadFrom(File file) {
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(new FileInputStream(file), BUFSIZE))) {
            Uri uri = Uri.parse(d.readUTF());
            Bitmap bitmap = BitmapFactory.decodeStream(d);
            if (bitmap == null) return null;

            Thumbnail thumbnail = createThumbnail(uri, bitmap, null, 0);
            if (thumbnail != null) thumbnail.setFromFile(true);
            return thumbnail;
        } catch (IOException e) {
            Log.i(TAG, "Fail to load bitmap.", e);
            return null;
        }
    }

    public static Thumbnail getLastThumbnail(ContentResolver resolver) {
        mResolver = resolver;
        Media image = getLastImageThumbnail(resolver);
        Media video = getLastVideoThumbnail(resolver);

        if (image == null && video == null) return null;

        Media lastMedia = (image != null && (video == null || image.dateTaken >= video.dateTaken)) ? image : video;
        try {
            Bitmap bitmap = (lastMedia == image) ?
                    Images.Thumbnails.getThumbnail(resolver, image.id, Images.Thumbnails.MICRO_KIND, null) :
                    Video.Thumbnails.getThumbnail(resolver, video.id, Video.Thumbnails.MICRO_KIND, null);
            return createThumbnail(lastMedia.uri, bitmap, null, lastMedia.orientation);
        } catch (Exception ex) {
            Log.e(TAG, "getLastThumbnail error", ex);
            return null;
        }
    }

    private static class Media {
        public final long id;
        public final int orientation;
        public final long dateTaken;
        public final Uri uri;

        public Media(long id, int orientation, long dateTaken, Uri uri) {
            this.id = id;
            this.orientation = orientation;
            this.dateTaken = dateTaken;
            this.uri = uri;
        }
    }

    private static Media getLastImageThumbnail(ContentResolver resolver) {
        return queryMedia(resolver, Images.Media.INTERNAL_CONTENT_URI, Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg");
    }

    private static Media getLastVideoThumbnail(ContentResolver resolver) {
        return queryMedia(resolver, Video.Media.INTERNAL_CONTENT_URI, Video.Media.EXTERNAL_CONTENT_URI, "video/mp4");
    }

    private static Media queryMedia(ContentResolver resolver, Uri internalUri, Uri externalUri, String mimeType) {
        String name = getName();
        Media internalMedia = queryMediaUri(resolver, internalUri, name, mimeType);
        Media externalMedia = queryMediaUri(resolver, externalUri, name, mimeType);
        
        if (internalMedia == null) return externalMedia;
        if (externalMedia == null) return internalMedia;
        return (internalMedia.dateTaken > externalMedia.dateTaken) ? internalMedia : externalMedia;
    }

    private static Media queryMediaUri(ContentResolver resolver, Uri baseUri, String name, String mimeType) {
        String selection = Images.ImageColumns.DATA + " like '%" + name + "%' AND " + Images.ImageColumns.MIME_TYPE + "='" + mimeType + "'";
        String order = Images.ImageColumns.DATE_TAKEN + " DESC," + Images.ImageColumns._ID + " DESC";

        try (Cursor cursor = resolver.query(baseUri.buildUpon().appendQueryParameter("limit", "1").build(),
                new String[]{Images.ImageColumns._ID, Images.ImageColumns.ORIENTATION, Images.ImageColumns.DATE_TAKEN},
                selection, null, order)) {

            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                int orientation = cursor.getInt(1);
                long dateTaken = cursor.getLong(2);
                return new Media(id, orientation, dateTaken, ContentUris.withAppendedId(baseUri, id));
            }
        } catch (Exception e) {
            Log.e(TAG, "queryMediaUri error", e);
        }
        return null;
    }

    public static String getName() {
        String name = "";
        DocumentFile saveDir = PluginManagerBase.getSaveDirNew(false);

        if (saveDir != null && !saveDir.canWrite()) {
            saveDir = PluginManagerBase.getSaveDirNew(true);
        }
        
        File fileObject = Util.getFileFromDocumentFile(saveDir);
        if (fileObject != null) {
            name = fileObject.getAbsolutePath();
        }

        return name;
    }

    private static Thumbnail createThumbnail(Uri uri, Bitmap bitmap, Bitmap fullBitmap, int orientation) {
        if (bitmap == null) {
            Log.e(TAG, "Failed to create thumbnail from null bitmap");
            return null;
        }
        try {
            return new Thumbnail(uri, bitmap, fullBitmap, orientation);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to construct thumbnail", e);
            return null;
        }
    }
}
