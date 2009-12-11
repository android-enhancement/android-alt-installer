/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import static android.os.FileObserver.*;
import static android.os.ParcelFileDescriptor.*;

import android.app.IWallpaperManager;
import android.app.IWallpaperManagerCallback;
import android.app.PendingIntent;
import android.app.WallpaperInfo;
import android.backup.BackupManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.FileObserver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.service.wallpaper.IWallpaperConnection;
import android.service.wallpaper.IWallpaperEngine;
import android.service.wallpaper.IWallpaperService;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.util.Xml;
import android.view.IWindowManager;
import android.view.WindowManager;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import com.android.internal.service.wallpaper.ImageWallpaper;
import com.android.internal.util.FastXmlSerializer;

class WallpaperManagerService extends IWallpaperManager.Stub {
    static final String TAG = "WallpaperService";
    static final boolean DEBUG = false;

    Object mLock = new Object();

    /**
     * Minimum time between crashes of a wallpaper service for us to consider
     * restarting it vs. just reverting to the static wallpaper.
     */
    static final long MIN_WALLPAPER_CRASH_TIME = 10000;
    
    static final File WALLPAPER_DIR = new File(
            "/data/data/com.android.settings/files");
    static final String WALLPAPER = "wallpaper";
    static final File WALLPAPER_FILE = new File(WALLPAPER_DIR, WALLPAPER);

    /**
     * List of callbacks registered they should each be notified
     * when the wallpaper is changed.
     */
    private final RemoteCallbackList<IWallpaperManagerCallback> mCallbacks
            = new RemoteCallbackList<IWallpaperManagerCallback>();

    /**
     * Observes the wallpaper for changes and notifies all IWallpaperServiceCallbacks
     * that the wallpaper has changed. The CREATE is triggered when there is no
     * wallpaper set and is created for the first time. The CLOSE_WRITE is triggered
     * everytime the wallpaper is changed.
     */
    private final FileObserver mWallpaperObserver = new FileObserver(
            WALLPAPER_DIR.getAbsolutePath(), CREATE | CLOSE_WRITE | DELETE | DELETE_SELF) {
                @Override
                public void onEvent(int event, String path) {
                    if (path == null) {
                        return;
                    }
                    synchronized (mLock) {
                        // changing the wallpaper means we'll need to back up the new one
                        long origId = Binder.clearCallingIdentity();
                        BackupManager bm = new BackupManager(mContext);
                        bm.dataChanged();
                        Binder.restoreCallingIdentity(origId);

                        File changedFile = new File(WALLPAPER_DIR, path);
                        if (WALLPAPER_FILE.equals(changedFile)) {
                            notifyCallbacksLocked();
                        }
                    }
                }
            };
    
    final Context mContext;
    final IWindowManager mIWindowManager;

    int mWidth = -1;
    int mHeight = -1;
    String mName = "";
    ComponentName mWallpaperComponent;
    WallpaperConnection mWallpaperConnection;
    long mLastDiedTime;
    
    class WallpaperConnection extends IWallpaperConnection.Stub
            implements ServiceConnection {
        final WallpaperInfo mInfo;
        final Binder mToken = new Binder();
        IWallpaperService mService;
        IWallpaperEngine mEngine;

        public WallpaperConnection(WallpaperInfo info) {
            mInfo = info;
        }
        
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                if (mWallpaperConnection == this) {
                    mService = IWallpaperService.Stub.asInterface(service);
                    attachServiceLocked(this);
                    // XXX should probably do saveSettingsLocked() later
                    // when we have an engine, but I'm not sure about
                    // locking there and anyway we always need to be able to
                    // recover if there is something wrong.
                    saveSettingsLocked();
                }
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mService = null;
                mEngine = null;
                if (mWallpaperConnection == this) {
                    Log.w(TAG, "Wallpaper service gone: " + mWallpaperComponent);
                    if ((mLastDiedTime+MIN_WALLPAPER_CRASH_TIME)
                            < SystemClock.uptimeMillis()) {
                        Log.w(TAG, "Reverting to built-in wallpaper!");
                        bindWallpaperComponentLocked(null);
                    }
                }
            }
        }
        
        public void attachEngine(IWallpaperEngine engine) {
            mEngine = engine;
        }
        
        public ParcelFileDescriptor setWallpaper(String name) {
            synchronized (mLock) {
                if (mWallpaperConnection == this) {
                    return updateWallpaperBitmapLocked(name);
                }
                return null;
            }
        }
    }
    
    public WallpaperManagerService(Context context) {
        if (DEBUG) Log.d(TAG, "WallpaperService startup");
        mContext = context;
        mIWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        WALLPAPER_DIR.mkdirs();
        loadSettingsLocked();
        mWallpaperObserver.startWatching();
    }
    
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        mWallpaperObserver.stopWatching();
    }
    
    public void systemReady() {
        synchronized (mLock) {
            try {
                bindWallpaperComponentLocked(mWallpaperComponent);
            } catch (RuntimeException e) {
                Log.w(TAG, "Failure starting previous wallpaper", e);
                try {
                    bindWallpaperComponentLocked(null);
                } catch (RuntimeException e2) {
                    Log.w(TAG, "Failure starting default wallpaper", e2);
                    clearWallpaperComponentLocked();
                }
            }
        }
    }
    
    public void clearWallpaper() {
        synchronized (mLock) {
            File f = WALLPAPER_FILE;
            if (f.exists()) {
                f.delete();
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                bindWallpaperComponentLocked(null);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void setDimensionHints(int width, int height) throws RemoteException {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_HINTS);

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }

        synchronized (mLock) {
            if (width != mWidth || height != mHeight) {
                mWidth = width;
                mHeight = height;
                saveSettingsLocked();
                if (mWallpaperConnection != null) {
                    if (mWallpaperConnection.mEngine != null) {
                        try {
                            mWallpaperConnection.mEngine.setDesiredSize(
                                    width, height);
                        } catch (RemoteException e) {
                        }
                        notifyCallbacksLocked();
                    }
                }
            }
        }
    }

    public int getWidthHint() throws RemoteException {
        synchronized (mLock) {
            return mWidth;
        }
    }

    public int getHeightHint() throws RemoteException {
        synchronized (mLock) {
            return mHeight;
        }
    }

    public ParcelFileDescriptor getWallpaper(IWallpaperManagerCallback cb,
            Bundle outParams) {
        synchronized (mLock) {
            try {
                if (outParams != null) {
                    outParams.putInt("width", mWidth);
                    outParams.putInt("height", mHeight);
                }
                mCallbacks.register(cb);
                File f = WALLPAPER_FILE;
                if (!f.exists()) {
                    return null;
                }
                return ParcelFileDescriptor.open(f, MODE_READ_ONLY);
            } catch (FileNotFoundException e) {
                /* Shouldn't happen as we check to see if the file exists */
                Log.w(TAG, "Error getting wallpaper", e);
            }
            return null;
        }
    }

    public WallpaperInfo getWallpaperInfo() {
        synchronized (mLock) {
            if (mWallpaperConnection != null) {
                return mWallpaperConnection.mInfo;
            }
            return null;
        }
    }
    
    public ParcelFileDescriptor setWallpaper(String name) {
        checkPermission(android.Manifest.permission.SET_WALLPAPER);
        synchronized (mLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                ParcelFileDescriptor pfd = updateWallpaperBitmapLocked(name);
                if (pfd != null) {
                    bindWallpaperComponentLocked(null);
                    saveSettingsLocked();
                }
                return pfd;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    ParcelFileDescriptor updateWallpaperBitmapLocked(String name) {
        if (name == null) name = "";
        try {
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(WALLPAPER_FILE,
                    MODE_CREATE|MODE_READ_WRITE);
            mName = name;
            return fd;
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Error setting wallpaper", e);
        }
        return null;
    }

    public void setWallpaperComponent(ComponentName name) {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_COMPONENT);
        synchronized (mLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                bindWallpaperComponentLocked(name);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    void bindWallpaperComponentLocked(ComponentName name) {
        // Has the component changed?
        if (mWallpaperConnection != null) {
            if (mWallpaperComponent == null) {
                if (name == null) {
                    // Still using default wallpaper.
                    return;
                }
            } else if (mWallpaperComponent.equals(name)) {
                // Changing to same wallpaper.
                return;
            }
        }
        
        try {
            ComponentName realName = name;
            if (realName == null) {
                // The default component is our static image wallpaper.
                realName = new ComponentName("android",
                        ImageWallpaper.class.getName());
                //clearWallpaperComponentLocked();
                //return;
            }
            ServiceInfo si = mContext.getPackageManager().getServiceInfo(realName,
                    PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS);
            if (!android.Manifest.permission.BIND_WALLPAPER.equals(si.permission)) {
                throw new SecurityException("Selected service does not require "
                        + android.Manifest.permission.BIND_WALLPAPER
                        + ": " + realName);
            }
            
            WallpaperInfo wi = null;
            
            Intent intent = new Intent(WallpaperService.SERVICE_INTERFACE);
            if (name != null) {
                // Make sure the selected service is actually a wallpaper service.
                List<ResolveInfo> ris = mContext.getPackageManager()
                        .queryIntentServices(intent, PackageManager.GET_META_DATA);
                for (int i=0; i<ris.size(); i++) {
                    ServiceInfo rsi = ris.get(i).serviceInfo;
                    if (rsi.name.equals(si.name) &&
                            rsi.packageName.equals(si.packageName)) {
                        try {
                            wi = new WallpaperInfo(mContext, ris.get(i));
                        } catch (XmlPullParserException e) {
                            throw new IllegalArgumentException(e);
                        } catch (IOException e) {
                            throw new IllegalArgumentException(e);
                        }
                        break;
                    }
                }
                if (wi == null) {
                    throw new SecurityException("Selected service is not a wallpaper: "
                            + realName);
                }
            }
            
            // Bind the service!
            WallpaperConnection newConn = new WallpaperConnection(wi);
            intent.setComponent(realName);
            intent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                    com.android.internal.R.string.wallpaper_binding_label);
            intent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                    mContext, 0,
                    Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER),
                            mContext.getText(com.android.internal.R.string.chooser_wallpaper)),
                            0));
            if (!mContext.bindService(intent, newConn,
                    Context.BIND_AUTO_CREATE)) {
                throw new IllegalArgumentException("Unable to bind service: "
                        + name);
            }
            
            clearWallpaperComponentLocked();
            mWallpaperComponent = name;
            mWallpaperConnection = newConn;
            mLastDiedTime = SystemClock.uptimeMillis();
            try {
                if (DEBUG) Log.v(TAG, "Adding window token: " + newConn.mToken);
                mIWindowManager.addWindowToken(newConn.mToken,
                        WindowManager.LayoutParams.TYPE_WALLPAPER);
            } catch (RemoteException e) {
            }
            
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("Unknown component " + name);
        }
    }
    
    void clearWallpaperComponentLocked() {
        mWallpaperComponent = null;
        if (mWallpaperConnection != null) {
            if (mWallpaperConnection.mEngine != null) {
                try {
                    mWallpaperConnection.mEngine.destroy();
                } catch (RemoteException e) {
                }
            }
            mContext.unbindService(mWallpaperConnection);
            try {
                if (DEBUG) Log.v(TAG, "Removing window token: "
                        + mWallpaperConnection.mToken);
                mIWindowManager.removeWindowToken(mWallpaperConnection.mToken);
            } catch (RemoteException e) {
            }
            mWallpaperConnection = null;
        }
    }
    
    void attachServiceLocked(WallpaperConnection conn) {
        try {
            conn.mService.attach(conn, conn.mToken,
                    WindowManager.LayoutParams.TYPE_WALLPAPER, false,
                    mWidth, mHeight);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed attaching wallpaper; clearing", e);
            bindWallpaperComponentLocked(null);
        }
    }
    
    private void notifyCallbacksLocked() {
        final int n = mCallbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mCallbacks.getBroadcastItem(i).onWallpaperChanged();
            } catch (RemoteException e) {

                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        }
        mCallbacks.finishBroadcast();
        final Intent intent = new Intent(Intent.ACTION_WALLPAPER_CHANGED);
        mContext.sendBroadcast(intent);
    }

    private void checkPermission(String permission) {
        if (PackageManager.PERMISSION_GRANTED!= mContext.checkCallingOrSelfPermission(permission)) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }

    private static JournaledFile makeJournaledFile() {
        final String base = "/data/system/wallpaper_info.xml";
        return new JournaledFile(new File(base), new File(base + ".tmp"));
    }

    private void saveSettingsLocked() {
        JournaledFile journal = makeJournaledFile();
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(journal.chooseForWrite(), false);
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, "utf-8");
            out.startDocument(null, true);

            out.startTag(null, "wp");
            out.attribute(null, "width", Integer.toString(mWidth));
            out.attribute(null, "height", Integer.toString(mHeight));
            out.attribute(null, "name", mName);
            if (mWallpaperComponent != null) {
                out.attribute(null, "component",
                        mWallpaperComponent.flattenToShortString());
            }
            out.endTag(null, "wp");

            out.endDocument();
            stream.close();
            journal.commit();
        } catch (IOException e) {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException ex) {
                // Ignore
            }
            journal.rollback();
        }
    }

    private void loadSettingsLocked() {
        JournaledFile journal = makeJournaledFile();
        FileInputStream stream = null;
        File file = journal.chooseForRead();
        boolean success = false;
        try {
            stream = new FileInputStream(file);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            int type;
            do {
                type = parser.next();
                if (type == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("wp".equals(tag)) {
                        mWidth = Integer.parseInt(parser.getAttributeValue(null, "width"));
                        mHeight = Integer.parseInt(parser.getAttributeValue(null, "height"));
                        mName = parser.getAttributeValue(null, "name");
                        String comp = parser.getAttributeValue(null, "component");
                        mWallpaperComponent = comp != null
                                ? ComponentName.unflattenFromString(comp)
                                : null;
                    }
                }
            } while (type != XmlPullParser.END_DOCUMENT);
            success = true;
        } catch (NullPointerException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        } catch (NumberFormatException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        } catch (XmlPullParserException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        } catch (IOException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "failed parsing " + file + " " + e);
        }
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException e) {
            // Ignore
        }

        if (!success) {
            mWidth = -1;
            mHeight = -1;
            mName = "";
        }
    }

    void settingsRestored() {
        boolean success = false;
        synchronized (mLock) {
            loadSettingsLocked();
            // If there's a wallpaper name, we use that.  If that can't be loaded, then we
            // use the default.
            if ("".equals(mName)) {
                success = true;
            } else {
                success = restoreNamedResourceLocked();
            }
        }

        if (!success) {
            Log.e(TAG, "Failed to restore wallpaper: '" + mName + "'");
            mName = "";
            WALLPAPER_FILE.delete();
        }
        saveSettingsLocked();
    }

    boolean restoreNamedResourceLocked() {
        if (mName.length() > 4 && "res:".equals(mName.substring(0, 4))) {
            String resName = mName.substring(4);

            String pkg = null;
            int colon = resName.indexOf(':');
            if (colon > 0) {
                pkg = resName.substring(0, colon);
            }

            String ident = null;
            int slash = resName.lastIndexOf('/');
            if (slash > 0) {
                ident = resName.substring(slash+1);
            }

            String type = null;
            if (colon > 0 && slash > 0 && (slash-colon) > 1) {
                type = resName.substring(colon+1, slash);
            }

            if (pkg != null && ident != null && type != null) {
                int resId = -1;
                InputStream res = null;
                FileOutputStream fos = null;
                try {
                    Context c = mContext.createPackageContext(pkg, Context.CONTEXT_RESTRICTED);
                    Resources r = c.getResources();
                    resId = r.getIdentifier(resName, null, null);
                    if (resId == 0) {
                        Log.e(TAG, "couldn't resolve identifier pkg=" + pkg + " type=" + type
                                + " ident=" + ident);
                        return false;
                    }

                    res = r.openRawResource(resId);
                    fos = new FileOutputStream(WALLPAPER_FILE);

                    byte[] buffer = new byte[32768];
                    int amt;
                    while ((amt=res.read(buffer)) > 0) {
                        fos.write(buffer, 0, amt);
                    }
                    // mWallpaperObserver will notice the close and send the change broadcast

                    Log.d(TAG, "Restored wallpaper: " + resName);
                    return true;
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Package name " + pkg + " not found");
                } catch (Resources.NotFoundException e) {
                    Log.e(TAG, "Resource not found: " + resId);
                } catch (IOException e) {
                    Log.e(TAG, "IOException while restoring wallpaper ", e);
                } finally {
                    if (res != null) {
                        try {
                            res.close();
                        } catch (IOException ex) {}
                    }
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (IOException ex) {}
                    }
                }
            }
        }
        return false;
    }
    
    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            
            pw.println("Permission Denial: can't dump wallpaper service from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized (mLock) {
            pw.println("Current Wallpaper Service state:");
            pw.print("  mWidth="); pw.print(mWidth);
                    pw.print(" mHeight="); pw.println(mHeight);
            pw.print("  mName="); pw.println(mName);
            pw.print("  mWallpaperComponent="); pw.println(mWallpaperComponent);
            if (mWallpaperConnection != null) {
                WallpaperConnection conn = mWallpaperConnection;
                pw.print("  Wallpaper connection ");
                        pw.print(conn); pw.println(":");
                pw.print("    mInfo.component="); pw.println(conn.mInfo.getComponent());
                pw.print("    mToken="); pw.println(conn.mToken);
                pw.print("    mService="); pw.println(conn.mService);
                pw.print("    mEngine="); pw.println(conn.mEngine);
                pw.print("    mLastDiedTime=");
                        pw.println(mLastDiedTime - SystemClock.uptimeMillis());
            }
        }
    }
}
