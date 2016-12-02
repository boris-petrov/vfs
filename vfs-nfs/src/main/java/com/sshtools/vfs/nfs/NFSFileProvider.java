package com.sshtools.vfs.nfs;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.apache.commons.vfs2.Capability;
import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystem;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.UserAuthenticationData;
import org.apache.commons.vfs2.provider.AbstractOriginatingFileProvider;
import org.apache.commons.vfs2.provider.GenericFileName;
import org.apache.commons.vfs2.util.UserAuthenticatorUtils;

import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3;

public class NFSFileProvider extends AbstractOriginatingFileProvider {

	public final static Collection<Capability> capabilities = Collections
			.unmodifiableCollection(Arrays.asList(Capability.CREATE, Capability.DELETE,
					// Capability.RENAME,
					Capability.ATTRIBUTES, Capability.GET_TYPE, Capability.GET_LAST_MODIFIED, Capability.LIST_CHILDREN,
					Capability.READ_CONTENT, Capability.URI, Capability.WRITE_CONTENT));

	public final static UserAuthenticationData.Type[] AUTHENTICATOR_TYPES = new UserAuthenticationData.Type[] {
			UserAuthenticationData.USERNAME, UserAuthenticationData.PASSWORD };
	/** Global instance of the JSON factory. */

	private static FileSystemOptions defaultOptions = new FileSystemOptions();

	public static FileSystemOptions getDefaultFileSystemOptions() {
		return defaultOptions;
	}

	public NFSFileProvider() {
		super();
		setFileNameParser(new NFSFileNameParser());
	}

    /**
     * Locates a file from its parsed URI.
     * @param name The file name.
     * @param fileSystemOptions FileSystem options.
     * @return A FileObject associated with the file.
     * @throws FileSystemException if an error occurs.
     */
    protected FileObject findFile(final FileName name, final FileSystemOptions fileSystemOptions)
        throws FileSystemException
    {
        // Check in the cache for the file system
        final FileName rootName = getContext().getFileSystemManager().resolveName(name, FileName.ROOT_PATH);

        final FileSystem fs = getFileSystem(rootName, fileSystemOptions);

        // Locate the file
        // return fs.resolveFile(name.getPath());
        return fs.resolveFile(name);
    }

	protected FileSystem doCreateFileSystem(FileName fileName, FileSystemOptions fileSystemOptions)
			throws FileSystemException {

		FileSystemOptions fsOptions = fileSystemOptions != null ? fileSystemOptions : getDefaultFileSystemOptions();
		try {
			UserAuthenticationData authData = null;
			NFSFileName fn = (NFSFileName) fileName;
			try {

				authData = UserAuthenticatorUtils.authenticate(fsOptions, AUTHENTICATOR_TYPES);

				String clientId = UserAuthenticatorUtils
						.toString(UserAuthenticatorUtils.getData(authData, UserAuthenticationData.USERNAME, null));

				if (clientId == null || clientId.length() == 0)
					clientId = ((GenericFileName) fileName).getUserName();

				String clientSecret = UserAuthenticatorUtils
						.toString(UserAuthenticatorUtils.getData(authData, UserAuthenticationData.PASSWORD, null));

				if (clientSecret == null || clientSecret.length() == 0)
					clientSecret = ((GenericFileName) fileName).getPassword();

				if (clientId == null || clientSecret == null || clientId.length() + clientSecret.length() == 0) {
					throw new FileSystemException("Empty credentials");
				}
				String mount = fn.getMount();
				while (mount != null && mount.length() > 1 && mount.endsWith("/"))
					mount = mount.substring(0, mount.length() - 1);
				if (mount == null || mount.equals(""))
					mount = "/";

				Nfs3 nfs3 = new Nfs3(fn.getHostName() + ":" + mount, 0, 0, 3);
				return new NFSFileSystem(fn, fsOptions, nfs3);
			} finally {
				UserAuthenticatorUtils.cleanup(authData);
			}

		} catch (IOException e) {
			throw new FileSystemException(e.getMessage(), e);
		} finally {
		}

	}

	public Collection<Capability> getCapabilities() {
		return capabilities;
	}
}
