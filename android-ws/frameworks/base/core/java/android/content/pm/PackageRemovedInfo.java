//custom installer experiment
package android.content.pm;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;

public class PackageRemovedInfo {
    public String removedPackage;
    public int uid = -1;
    public int removedUid = -1;
    public boolean isRemovedPackageSystemUpdate = false;

    public void sendBroadcast(boolean fullRemove, boolean replacing) {
        Bundle extras = new Bundle(1);
        extras.putInt(Intent.EXTRA_UID, removedUid >= 0 ? removedUid : uid);
        extras.putBoolean(Intent.EXTRA_DATA_REMOVED, fullRemove);
        if (replacing) {
            extras.putBoolean(Intent.EXTRA_REPLACING, true);
        }
        if (removedPackage != null) {
            sendPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED, removedPackage, extras);
        }
        if (removedUid >= 0) {
            sendPackageBroadcast(Intent.ACTION_UID_REMOVED, null, extras);
        }
    }
    
    private static final void sendPackageBroadcast(String action, String pkg, Bundle extras) {
        IActivityManager am = ActivityManagerNative.getDefault();
        if (am != null) {
            try {
                final Intent intent = new Intent(action,
                        pkg != null ? Uri.fromParts("package", pkg, null) : null);
                if (extras != null) {
                    intent.putExtras(extras);
                }
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                am.broadcastIntent(
                    null, intent,
                            null, null, 0, null, null, null, false, false);
            } catch (RemoteException ex) {
            }
        }
    }
}
//custom installer experiment