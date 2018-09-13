package com.sshtools.vfs.s3.provider.s3;

import static com.amazonaws.services.s3.model.ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION;
import static com.sshtools.vfs.s3.operations.Acl.Permission.READ;
import static com.sshtools.vfs.s3.operations.Acl.Permission.WRITE;
import static com.sshtools.vfs.s3.provider.s3.AmazonS3ClientHack.extractCredentials;
import static java.util.Calendar.SECOND;
import static org.apache.commons.vfs2.FileName.SEPARATOR;
import static org.apache.commons.vfs2.NameScope.CHILD;
import static org.apache.commons.vfs2.NameScope.FILE_SYSTEM;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.AllFileSelector;
import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.provider.AbstractFileName;
import org.apache.commons.vfs2.provider.AbstractFileObject;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.Headers;
import com.amazonaws.services.s3.internal.Mimetypes;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.CanonicalGrantee;
import com.amazonaws.services.s3.model.Grant;
import com.amazonaws.services.s3.model.Grantee;
import com.amazonaws.services.s3.model.GroupGrantee;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.Owner;
import com.amazonaws.services.s3.model.Permission;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.sshtools.vfs.s3.operations.Acl;

/**
 * Implementation of the virtual S3 file system object using the AWS-SDK.<br>
 * Based on Matthias Jugel code. <a href="http://thinkberg.com/svn/moxo/trunk/modules/vfs.s3/">http://thinkberg.com/svn/moxo/trunk/modules/vfs.s3/</a>
 *
 * @author Marat Komarov
 * @author Matthias L. Jugel
 * @author Moritz Siuts
 * @author Shon Vella
 */
public class S3FileObject extends AbstractFileObject<S3FileSystem> {

	private static final Log logger = LogFactory.getLog(S3FileObject.class);

    private static final String MIMETYPE_JETS3T_DIRECTORY = "application/x-directory";

    /** Amazon S3 object */
    private ObjectMetadata objectMetadata;
    private String objectKey;

    /**
     * True when content attached to file
     */
    private boolean attached = false;

    /**
     * Amazon file owner. Used in ACL
     */
    private Owner fileOwner;

    boolean flaggedAsFolder = false;
    
    public S3FileObject(AbstractFileName fileName,
                        S3FileSystem fileSystem) throws FileSystemException {
        super(fileName, fileSystem);
    }

    @Override
    protected void doAttach() {
        if (!attached) {

        		if(!flaggedAsFolder) {
	            try {
	                // Do we have file with name?
	                String candidateKey = getS3Key();
	                objectMetadata = getService().getObjectMetadata(getBucket().getName(), candidateKey);
	                objectKey = candidateKey;
	                logger.info("Attach file to S3 Object: " + objectKey);
	
	                attached = true;
	                return;
	            } catch (AmazonServiceException e) {
	                // No, we don't
	            }
	            catch (AmazonClientException e) {
	                // We are attempting to attach to the root bucket
	            }
        	}

            try {
                // Do we have folder with that name?
                String candidateKey = getS3Key() + FileName.SEPARATOR;
                objectMetadata = getService().getObjectMetadata(getBucket().getName(), candidateKey);
                objectKey = candidateKey;
                logger.info("Attach folder to S3 Object: " + objectKey);

                attached = true;
                return;
            } catch (AmazonServiceException e) {
                // No, we don't
            }

            // Create a new
            if (objectMetadata == null) {
                objectMetadata = new ObjectMetadata();
                objectKey = getS3Key();
                objectMetadata.setLastModified(new Date());

                logger.info("Attach new S3 Object: " + objectKey);

                attached = true;
            }
        }
    }

    @Override
    protected void doDetach() throws Exception {
        if (attached) {
            logger.info("Detach from S3 Object: " + objectKey);
            objectMetadata = null;
            attached = false;
        }
    }

    @Override
    protected void doDelete() throws Exception {
        getService().deleteObject(getBucket().getName(), objectKey);
    }

    @Override
    protected void doCreateFolder() throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "Create new folder in bucket [" +
                    ((getBucket() != null) ? getBucket().getName() : "null") +
                    "] with key [" +
                    ((objectMetadata != null) ? objectKey : "null") +
                    "]"
            );
        }

        if (objectMetadata == null) {
            return;
        }

        InputStream input = new ByteArrayInputStream(new byte[0]);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(0);

        if (getServerSideEncryption()) {
            metadata.setSSEAlgorithm(AES_256_SERVER_SIDE_ENCRYPTION);
        }

        String dirName = objectKey.endsWith(SEPARATOR) ? objectKey : objectKey + SEPARATOR;
        getService().putObject(new PutObjectRequest(getBucket().getName(), dirName, input, metadata));
        flaggedAsFolder = true;
    }

    @Override
    protected long doGetLastModifiedTime() throws Exception {
        return objectMetadata.getLastModified().getTime();
    }

    @Override
    protected boolean doSetLastModifiedTime(final long modtime) throws Exception {
        long oldModified = objectMetadata.getLastModified().getTime();
        boolean differentModifiedTime = oldModified != modtime;
        if (differentModifiedTime) {
            objectMetadata.setLastModified(new Date(modtime));
        }
        return differentModifiedTime;
    }

    
    @Override
	protected void doRename(FileObject newFile) throws Exception {
		
        newFile.copyFrom(this, new AllFileSelector());
        deleteAll();
	}
    
    @Override
    protected InputStream doGetInputStream() throws Exception {
    	final String objectPath = getName().getPath();

        S3Object obj = null;

        try {
            obj = getService().getObject(getBucket().getName(), objectKey);

            logger.info(String.format("Downloading S3 Object: %s", objectPath));

            if (obj.getObjectMetadata().getContentLength() > 0) {
                return new S3InputStream(obj);
            } else {
            	return new S3InputStream();
            }
        } catch (AmazonServiceException e) {
            final String failedMessage = "Failed to download S3 Object %s. %s";

            throw new FileSystemException(String.format(failedMessage, objectPath, e.getMessage()), e);
        } finally {
        	/***
        	 * LDP - This closes the stream we just opened?!
        	 */
//            if (obj != null) {
//                try {
//                    obj.close();
//                } catch (IOException e) {
//                    logger.warn("Not able to close S3 object [" + objectPath + "]", e);
//                }
//            }
        }
    }

    @Override
    protected OutputStream doGetOutputStream(boolean bAppend) throws Exception {
        return new S3OutputStream(getService(), getBucket().getName(), getName().getPath());
    }

    @Override
    protected FileType doGetType() throws Exception {
    	
    	if(flaggedAsFolder) {
    		return FileType.FOLDER;
    	}
    	
        if (objectMetadata.getContentType() == null) {
            return FileType.IMAGINARY;
        }

        if ("".equals(objectKey) || isDirectoryPlaceholder()) {
            return FileType.FOLDER;
        }

        return FileType.FILE;
    }

    @Override
    protected String[] doListChildren() throws Exception {
        String path = objectKey;
        // make sure we add a '/' slash at the end to find children
        if ((!"".equals(path)) && (!path.endsWith(SEPARATOR))) {
            path = path + "/";
        }

        final ListObjectsRequest loReq = new ListObjectsRequest();
        loReq.setBucketName(getBucket().getName());
        loReq.setDelimiter("/");
        loReq.setPrefix(path);

        ObjectListing listing = getService().listObjects(loReq);
        final List<S3ObjectSummary> summaries = new ArrayList<S3ObjectSummary>(listing.getObjectSummaries());
        final Set<String> commonPrefixes = new TreeSet<String>(listing.getCommonPrefixes());
        while (listing.isTruncated()) {
            listing = getService().listNextBatchOfObjects(listing);
            summaries.addAll(listing.getObjectSummaries());
            commonPrefixes.addAll(listing.getCommonPrefixes());
        }

        List<String> childrenNames = new ArrayList<String>(summaries.size() + commonPrefixes.size());

        // add the prefixes (non-empty subdirs) first
        for (String commonPrefix : commonPrefixes) {
            // strip path from name (leave only base name)
            final String stripPath = commonPrefix.substring(path.length());
            childrenNames.add(stripPath);
        }

        for (S3ObjectSummary summary : summaries) {
            if (!summary.getKey().equals(path)) {
                // strip path from name (leave only base name)
                final String stripPath = summary.getKey().substring(path.length());
                childrenNames.add(stripPath);
            }
        }

        return childrenNames.toArray(new String[childrenNames.size()]);
    }

    /**
     * Lists the children of this file.  Is only called if {@link #doGetType}
     * returns {@link FileType#FOLDER}.  The return value of this method
     * is cached, so the implementation can be expensive.<br>
     * Other than <code>doListChildren</code> you could return FileObject's to e.g. reinitialize the
     * type of the file.<br>
     * (Introduced for Webdav: "permission denied on resource" during getType())
     * @return The children of this FileObject.
     * @throws Exception if an error occurs.
     */
    @Override
    protected FileObject[] doListChildrenResolved() throws Exception
    {
        String path = objectKey;
        // make sure we add a '/' slash at the end to find children
        if ((!"".equals(path)) && (!path.endsWith(SEPARATOR))) {
            path = path + "/";
        }

        final ListObjectsRequest loReq = new ListObjectsRequest();
        loReq.setBucketName(getBucket().getName());
        loReq.setDelimiter("/");
        loReq.setPrefix(path);

        ObjectListing listing = getService().listObjects(loReq);
        final List<S3ObjectSummary> summaries = new ArrayList<S3ObjectSummary>(listing.getObjectSummaries());
        final Set<String> commonPrefixes = new TreeSet<String>(listing.getCommonPrefixes());
        while (listing.isTruncated()) {
            listing = getService().listNextBatchOfObjects(listing);
            summaries.addAll(listing.getObjectSummaries());
            commonPrefixes.addAll(listing.getCommonPrefixes());
        }

        List<FileObject> resolvedChildren = new ArrayList<FileObject>(summaries.size() + commonPrefixes.size());

        // add the prefixes (non-empty subdirs) first
        for (String commonPrefix : commonPrefixes) {
            // strip path from name (leave only base name)
            String stripPath = commonPrefix.substring(path.length());
            FileObject childObject = resolveFile(stripPath, (stripPath.equals("/")) ? FILE_SYSTEM : CHILD);

            if ((childObject instanceof S3FileObject) && !stripPath.equals("/")) {
            	((S3FileObject)childObject).flaggedAsFolder = true;
                resolvedChildren.add(childObject);
            }
        }

        for (S3ObjectSummary summary : summaries) {
            if (!summary.getKey().equals(path)) {
                // strip path from name (leave only base name)
                final String stripPath = summary.getKey().substring(path.length());
                FileObject childObject = resolveFile(stripPath, CHILD);
                if (childObject instanceof S3FileObject) {
                    S3FileObject s3FileObject = (S3FileObject) childObject;
                    ObjectMetadata childMetadata = new ObjectMetadata();
                    childMetadata.setContentLength(summary.getSize());
                    childMetadata.setContentType(
                        Mimetypes.getInstance().getMimetype(s3FileObject.getName().getBaseName()));
                    childMetadata.setLastModified(summary.getLastModified());
                    childMetadata.setHeader(Headers.ETAG, summary.getETag());
                    s3FileObject.objectMetadata = childMetadata;
                    s3FileObject.objectKey = summary.getKey();
                    s3FileObject.attached = true;
                    resolvedChildren.add(s3FileObject);
                }
            }
        }

        return resolvedChildren.toArray(new FileObject[resolvedChildren.size()]);
    }

    @Override
    protected long doGetContentSize() throws Exception {
        return objectMetadata.getContentLength();
    }

    /**
     * Same as in Jets3t library, to be compatible.
     */
    private boolean isDirectoryPlaceholder() {
        // Recognize "standard" directory place-holder indications used by
        // Amazon's AWS Console and Panic's Transmit.
        if (objectKey.endsWith("/") && objectMetadata.getContentLength() == 0) {
            return true;
        }

        // Recognize s3sync.rb directory placeholders by MD5/ETag value.
        if ("d66759af42f282e1ba19144df2d405d0".equals(objectMetadata.getETag())) {
            return true;
        }

        // Recognize place-holder objects created by the Google Storage console
        // or S3 Organizer Firefox extension.
        if (objectKey.endsWith("_$folder$") && (objectMetadata.getContentLength() == 0)) {
            return true;
        }

        // Recognize legacy JetS3t directory place-holder objects, only gives
        // accurate results if an object's metadata is populated.
        if (objectMetadata.getContentLength() == 0
                && MIMETYPE_JETS3T_DIRECTORY.equals(objectMetadata.getContentType())) {
            return true;
        }
        return false;
    }


    /**
     * Create an S3 key from a commons-vfs path. This simply strips the slash
     * from the beginning if it exists.
     *
     * @return the S3 object key
     */
    private String getS3Key() {
        return getS3Key(getName());
    }

    private String getS3Key(FileName fileName) {
        String path = fileName.getPath();

        if ("".equals(path)) {
            return path;
        } else {
            return path.substring(1);
        }
    }

    // ACL extension methods

    /**
     * Returns S3 file owner.
     * Loads it from S3 if needed.
     */
    private Owner getS3Owner() {
        if (fileOwner == null) {
            AccessControlList s3Acl = getS3Acl();
            fileOwner = s3Acl.getOwner();
        }
        return fileOwner;
    }

    /**
     * Get S3 ACL list
     * @return acl list
     */
    private AccessControlList getS3Acl() {
        String key = getS3Key();
        return "".equals(key) ? getService().getBucketAcl(getBucket().getName()) : getService().getObjectAcl(getBucket().getName(), key);
    }

    /**
     * Put S3 ACL list
     * @param s3Acl acl list
     */
    private void putS3Acl (AccessControlList s3Acl) {
        String key = getS3Key();
        // Determine context. Object or Bucket
        if ("".equals(key)) {
            getService().setBucketAcl(getBucket().getName(), s3Acl);
        } else {
            // Before any operations with object it must be attached
            doAttach();
            // Put ACL to S3
            getService().setObjectAcl(getBucket().getName(), objectKey, s3Acl);
        }
    }

    /**
     * Returns access control list for this file.
     *
     * VFS interfaces doesn't provide interface to manage permissions. ACL can be accessed through {@link FileObject#getFileOperations()}
     * Sample: <code>file.getFileOperations().getOperation(IAclGetter.class)</code>
     *
     * @return Current Access control list for a file
     * @throws FileSystemException on error
     */
    public Acl getAcl () throws FileSystemException {
        Acl myAcl = new Acl();
        AccessControlList s3Acl;
        try {
            s3Acl = getS3Acl();
        } catch (AmazonServiceException e) {
            throw new FileSystemException(e);
        }

        // Get S3 file owner
        Owner owner = s3Acl.getOwner();
        fileOwner = owner;

        // Read S3 ACL list and build VFS ACL.
        @SuppressWarnings("deprecation")
		Set<Grant> grants = s3Acl.getGrants();

        for (Grant item : grants) {
            // Map enums to jets3t ones
            Permission perm = item.getPermission();
            Acl.Permission[] rights;
            if (perm.equals(Permission.FullControl)) {
                rights = Acl.Permission.values();
            } else if (perm.equals(Permission.Read)) {
                rights = new Acl.Permission[1];
                rights[0] = READ;
            } else if (perm.equals(Permission.Write)) {
                rights = new Acl.Permission[1];
                rights[0] = WRITE;
            } else {
                // Skip unknown permission
                logger.error(String.format("Skip unknown permission %s", perm));
                continue;
            }

            // Set permissions for groups
            if (item.getGrantee() instanceof GroupGrantee) {
                GroupGrantee grantee = (GroupGrantee)item.getGrantee();
                if (GroupGrantee.AllUsers.equals(grantee)) {
                    // Allow rights to GUEST
                    myAcl.allow(Acl.Group.EVERYONE, rights);
                } else if (GroupGrantee.AuthenticatedUsers.equals(grantee)) {
                    // Allow rights to AUTHORIZED
                    myAcl.allow(Acl.Group.AUTHORIZED, rights);
                }
            } else if (item.getGrantee() instanceof CanonicalGrantee) {
                CanonicalGrantee grantee = (CanonicalGrantee)item.getGrantee();
                if (grantee.getIdentifier().equals(owner.getId())) {
                    // The same owner and grantee understood as OWNER group
                    myAcl.allow(Acl.Group.OWNER, rights);
                }
            }

        }

        return myAcl;
    }

    /**
     * Returns access control list for this file.
     *
     * VFS interfaces doesn't provide interface to manage permissions. ACL can be accessed through {@link FileObject#getFileOperations()}
     * Sample: <code>file.getFileOperations().getOperation(IAclGetter.class)</code>
     *
     * @param acl the access control list
     * @throws FileSystemException on error
     */
    public void setAcl (Acl acl) throws FileSystemException {

        // Create empty S3 ACL list
        AccessControlList s3Acl = new AccessControlList();

        // Get file owner
        Owner owner;
        try {
            owner = getS3Owner();
        } catch (AmazonServiceException e) {
            throw new FileSystemException(e);
        }
        s3Acl.setOwner(owner);

        // Iterate over VFS ACL rules and fill S3 ACL list
        Map<Acl.Group, Acl.Permission[]> rules = acl.getRules();

        final Acl.Permission[] allRights = Acl.Permission.values();

        for (Acl.Group group : rules.keySet()) {
            Acl.Permission[] rights = rules.get(group);

            if (rights.length == 0) {
                // Skip empty rights
                continue;
            }

            // Set permission
            Permission perm;
            if (Arrays.equals(rights, allRights)) {
                perm = Permission.FullControl;
            } else if (acl.isAllowed(group, READ)) {
                perm = Permission.Read;
            } else if (acl.isAllowed(group, WRITE)) {
                perm = Permission.Write;
            } else {
                logger.error(String.format("Skip unknown set of rights %s", Arrays.toString(rights)));
                continue;
            }

            // Set grantee
            Grantee grantee;
            if (group.equals(Acl.Group.EVERYONE)) {
                grantee = GroupGrantee.AllUsers;
            } else if (group.equals(Acl.Group.AUTHORIZED)) {
                grantee = GroupGrantee.AuthenticatedUsers;
            } else if (group.equals(Acl.Group.OWNER)) {
               grantee = new CanonicalGrantee(owner.getId());
            } else {
                logger.error(String.format("Skip unknown group %s", group));
                continue;
            }

            // Grant permission
            s3Acl.grantPermission(grantee, perm);
        }

        // Put ACL to S3
        try {
            putS3Acl(s3Acl);
        } catch (Exception e) {
            throw new FileSystemException(e);
        }
    }

    /**
     * Get direct http url to S3 object.
     * @return the direct http url to S3 object
     */
    public String getHttpUrl() {
        StringBuilder sb = new StringBuilder("http://" + getBucket().getName() + ".s3.amazonaws.com/");
        String key = getS3Key();

        // Determine context. Object or Bucket
        if ("".equals(key)) {
            return sb.toString();
        } else {
            return sb.append(key).toString();
        }
    }

    /**
     * Get private url with access key and secret key.
     *
     * @return the private url
     */
    public String getPrivateUrl() throws FileSystemException {
        AWSCredentials awsCredentials = S3FileSystemConfigBuilder.getInstance().getAWSCredentials(getFileSystem().getFileSystemOptions());

        if (awsCredentials == null) {
            awsCredentials = extractCredentials(getService());
        }

        if (awsCredentials == null) {
            throw new FileSystemException("Not able to build private URL - empty AWS credentials");
        }

        return String.format(
                "s3://%s:%s@%s/%s",
                awsCredentials.getAWSAccessKeyId(),
                awsCredentials.getAWSSecretKey(),
                getBucket().getName(),
                getS3Key()
        );
    }

    /**
     * Temporary accessible url for object.
     * @param expireInSeconds seconds until expiration
     * @return temporary accessible url for object
     * @throws FileSystemException on error
     */
    public String getSignedUrl(int expireInSeconds) throws FileSystemException {
        final Calendar cal = Calendar.getInstance();

        cal.add(SECOND, expireInSeconds);

        try {
            return getService().generatePresignedUrl(
                getBucket().getName(),
                getS3Key(), cal.getTime()).toString();
        } catch (AmazonServiceException e) {
            throw new FileSystemException(e);
        }
    }

    /**
     * Get MD5 hash for the file
     * @return md5 hash for file
     * @throws FileSystemException on error
     */
    public String getMD5Hash() throws FileSystemException {
        String hash = null;

        ObjectMetadata metadata = getObjectMetadata();
        if (metadata != null) {
            hash = metadata.getETag(); // TODO this is something different than mentioned in methodname / javadoc
        }

        return hash;
    }

    public ObjectMetadata getObjectMetadata() throws FileSystemException {
        try {
            return getService().getObjectMetadata(getBucket().getName(), getS3Key());
        } catch (AmazonServiceException e) {
            throw new FileSystemException(e);
        }
    }

    protected AmazonS3 getService() {
        return ((S3FileSystem)getFileSystem()).getService();
    }

    /* Amazon S3 bucket */
    protected Bucket getBucket() {
        return ((S3FileSystem)getFileSystem()).getBucket();
    }

    /**
     * Queries the object if a simple rename to the filename of <code>newfile</code> is possible.
     *
     * @param newfile
     *  the new filename
     * @return true if rename is possible
     */
    @Override
    public boolean canRenameTo(FileObject newfile) {
        return true;
    }

    private boolean getServerSideEncryption() {
        return S3FileSystemConfigBuilder.getInstance().getServerSideEncryption(getFileSystem().getFileSystemOptions());
    }
}
