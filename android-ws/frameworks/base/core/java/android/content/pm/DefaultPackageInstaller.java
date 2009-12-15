//custom installer experiment
package android.content.pm;

import android.content.Context;
import android.os.Handler;
import android.util.*;


public class DefaultPackageInstaller implements IPackageInstaller {
	private final String TAG = "DefaultPackageInstaller";
	
	public DefaultPackageInstaller() {
		
	}

	public void preInstallPackage(final Handler handler) {
        Log.i(TAG, "preInstallPackage ... ");
		
	}

	public void postInstallPackage(final Context context, final Handler handler, final PackageInstalledInfo res) {
        Log.i(TAG, "postInstallPackage ... " + res);
        
	}

	public void preDeletePackage(final Handler handler) {
		Log.i(TAG, "pre delete package");
	}

	public void postDeletePackage(final Context context, final Handler handler) {
		Log.i(TAG, "post delete package");
	}
}
//custom installer experiment

