package android.content.pm;

// custom installer experiment
import android.os.Handler;
import android.content.Context;

public interface IPackageInstaller {
	public void preInstallPackage(final Handler handler);
	public void postInstallPackage(final Context context, final Handler handler, final PackageInstalledInfo res);
	public void preDeletePackage(final Handler handler);
	public void postDeletePackage(final Context context, final Handler handler);
}
//custom installer experiment
