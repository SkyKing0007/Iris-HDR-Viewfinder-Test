package com.skyking0007.irishdrviewfinder;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;

final class MediaStoreWriter {
    interface OutputAction {
        void write(OutputStream output) throws Exception;
    }

    private MediaStoreWriter() {}

    static Uri writeBytes(Context context, String displayName, String mimeType, byte[] bytes)
            throws Exception {
        return write(context, displayName, mimeType, output -> output.write(bytes));
    }

    static Uri write(Context context, String displayName, String mimeType, OutputAction action)
            throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/IrisHDRViewfinder");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore insert returned null for " + displayName);
        }

        boolean success = false;
        try (OutputStream output = resolver.openOutputStream(uri, "w")) {
            if (output == null) {
                throw new IOException("Unable to open output for " + displayName);
            }
            action.write(output);
            output.flush();
            success = true;
        } finally {
            if (success) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            } else {
                resolver.delete(uri, null, null);
            }
        }
        return uri;
    }
}
