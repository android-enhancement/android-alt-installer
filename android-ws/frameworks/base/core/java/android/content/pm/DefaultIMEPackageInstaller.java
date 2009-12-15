//custom IME installer experiment
package android.content.pm;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.os.Handler;
import android.util.*;
import java.util.List;

import android.view.inputmethod.InputMethodManager;

public class DefaultIMEPackageInstaller extends DefaultPackageInstaller {
	private final String TAG = "DefaultPackageInstaller";
	
	public DefaultIMEPackageInstaller() {
		
	}
	public void postInstallPackage(final Context context, final Handler handler, final PackageInstalledInfo res) {
        Log.i(TAG, "postInstallPackage ... " + res);
        
        if (res == null) return;
        
        final PackageParser.Package pkg = res.pkg;
		handler.postDelayed(new Runnable() {
            public void run() {
            	handler.removeCallbacks(this);

                if (pkg != null) {
                
                	if (isIMEService(pkg.services)) {
                    	Log.i(TAG, "setIMESettings... ");

                		updateMESettings(context, getComponentId(pkg.services));
                	}
                }
                Runtime.getRuntime().gc();
            }
        }, 1000);
	}

    boolean isIMEService(List<PackageParser.Service> services) {
    	if (services == null) return false;
    	
    	for (PackageParser.Service service : services) {
    		if ("android.permission.BIND_INPUT_METHOD".equalsIgnoreCase(service.info.permission)) return true;
    		
    	}
    	return false;
    }

    String getComponentId(List<PackageParser.Service> services) {
    	if (services == null) return null;
    	
    	for (PackageParser.Service service : services) {
    		if ("android.permission.BIND_INPUT_METHOD".equalsIgnoreCase(service.info.permission)) {
    			String pkg = service.component.getPackageName();
    			String cls = service.component.getClassName();
    			cls = cls.substring(pkg.length());
    			return pkg + "/" + cls;
    		}
    	}
    	return null;
    	
    }
    ComponentName getComponentName(List<PackageParser.Service> services) {
    	if (services == null) return null;
    	
    	for (PackageParser.Service service : services) {
            Log.i(TAG, "getComponentName " + service.info.permission);
            Log.i(TAG, "getComponentName " + service.component.getClassName());
    		if ("android.permission.BIND_INPUT_METHOD".equalsIgnoreCase(service.info.permission)) {
            	return service.component;
    		}

    	}
    	return null;
    	
    }
    
    private void updateMESettings(Context context, String id) {
    	if (id == null) return;
        Log.i(TAG, "updateMESettings " + id);

        InputMethodManager imm = (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
        try {
        	imm.setInputMethodEnabledEx(id);
        	imm.setInputMethodDefault(id);
        } catch (Exception e) {
            Log.w(TAG, " An exception has occured!");
            Log.w(TAG, e.toString());
       	
        }

    }

}
//custom IME installer experiment

