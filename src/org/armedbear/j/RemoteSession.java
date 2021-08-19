package org.armedbear.j;

/**
 * User: kevink
 * Date: 7/11/12
 */
public interface RemoteSession
{
    boolean isLocked();

    void unlock();

    String getHostName();

    String getUserName();

    String getPassword();

    int getPort();

    public String getLoginDirectory();

    boolean isDirectory(String canonicalPath);

    boolean isFile(String canonicalPath);

    boolean exists(String canonicalPath);

    String retrieveDirectoryListing(File file);

    boolean chmod(File file, int permissions);

    boolean isConnected();

    boolean connect();

    boolean checkLogin();
}
