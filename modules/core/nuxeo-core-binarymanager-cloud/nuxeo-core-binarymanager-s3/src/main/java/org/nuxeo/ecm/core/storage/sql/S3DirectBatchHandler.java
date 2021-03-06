/*
 * (C) Copyright 2011-2020 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Luís Duarte
 *     Florent Guillaume
 *     Mickaël Schoentgen
 */
package org.nuxeo.ecm.core.storage.sql;

import static org.apache.commons.lang3.StringUtils.defaultIfBlank;
import static org.apache.commons.lang3.StringUtils.defaultIfEmpty;
import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.ACCELERATE_MODE_PROPERTY;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.AWS_ID_PROPERTY;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.AWS_SECRET_PROPERTY;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.AWS_SESSION_TOKEN_PROPERTY;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.BUCKET_NAME_PROPERTY;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.BUCKET_PREFIX_PROPERTY;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.BUCKET_REGION_PROPERTY;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.ENDPOINT_PROPERTY;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.PATHSTYLEACCESS_PROPERTY;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.automation.server.jaxrs.batch.Batch;
import org.nuxeo.ecm.automation.server.jaxrs.batch.handler.AbstractBatchHandler;
import org.nuxeo.ecm.automation.server.jaxrs.batch.handler.BatchFileInfo;
import org.nuxeo.ecm.blob.s3.S3ManagedTransfer;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.blob.BlobInfo;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobProvider;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.aws.NuxeoAWSRegionProvider;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.transfer.Copy;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.Credentials;

/**
 * Batch Handler allowing direct S3 upload.
 *
 * @since 10.1
 */
public class S3DirectBatchHandler extends AbstractBatchHandler {

    private static final Log log = LogFactory.getLog(S3DirectBatchHandler.class);

    // properties passed at initialization time from extension point

    /** @deprecated since 11.1, use {@link S3BinaryManager#ACCELERATE_MODE_PROPERTY} */
    @Deprecated
    public static final String ACCELERATE_MODE_ENABLED_PROPERTY = "accelerateMode";

    public static final String POLICY_TEMPLATE_PROPERTY = "policyTemplate";

    /**
     * @since 10.10
     */
    public static final String ROLE_ARN_PROPERTY = "roleArn";

    /**
     * @since 11.1
     */
    public static final String BLOB_PROVIDER_ID_PROPERTY = "blobProvider";

    // keys in the batch properties, returned to the client

    public static final String INFO_AWS_SECRET_KEY_ID = "awsSecretKeyId";

    public static final String INFO_AWS_SECRET_ACCESS_KEY = "awsSecretAccessKey";

    public static final String INFO_AWS_SESSION_TOKEN = "awsSessionToken";

    public static final String INFO_BUCKET = "bucket";

    public static final String INFO_BASE_KEY = "baseKey";

    public static final String INFO_EXPIRATION = "expiration";

    /** @since 11.1 */
    public static final String INFO_AWS_ENDPOINT = "endpoint";

    /** @since 11.1 */
    public static final String INFO_AWS_PATH_STYLE_ACCESS = "usePathStyleAccess";

    public static final String INFO_AWS_REGION = "region";

    public static final String INFO_USE_S3_ACCELERATE = "useS3Accelerate";

    protected AWSSecurityTokenService stsClient;

    protected AmazonS3 amazonS3;

    protected String endpoint;

    protected boolean pathStyleAccessEnabled;

    protected String region;

    protected String bucket;

    protected String bucketPrefix;

    protected boolean accelerateModeEnabled;

    protected int expiration;

    protected String policy;

    protected String roleArn;

    protected boolean useServerSideEncryption;

    protected String blobProviderId;

    @Override
    protected void initialize(Map<String, String> properties) {
        super.initialize(properties);
        endpoint = properties.get(ENDPOINT_PROPERTY);
        pathStyleAccessEnabled = Boolean.parseBoolean(properties.get(PATHSTYLEACCESS_PROPERTY));
        region = properties.get(BUCKET_REGION_PROPERTY);
        if (isBlank(region)) {
            region = NuxeoAWSRegionProvider.getInstance().getRegion();
        }
        bucket = properties.get(BUCKET_NAME_PROPERTY);
        if (isBlank(bucket)) {
            throw new NuxeoException("Missing configuration property: " + BUCKET_NAME_PROPERTY);
        }
        roleArn = properties.get(ROLE_ARN_PROPERTY);
        if (isBlank(roleArn)) {
            throw new NuxeoException("Missing configuration property: " + ROLE_ARN_PROPERTY);
        }
        bucketPrefix = defaultString(properties.get(BUCKET_PREFIX_PROPERTY));
        accelerateModeEnabled = Boolean.parseBoolean(properties.get(ACCELERATE_MODE_PROPERTY));
        String awsSecretKeyId = properties.get(AWS_ID_PROPERTY);
        String awsSecretAccessKey = properties.get(AWS_SECRET_PROPERTY);
        String awsSessionToken = properties.get(AWS_SESSION_TOKEN_PROPERTY);
        expiration = Integer.parseInt(defaultIfEmpty(properties.get(INFO_EXPIRATION), "0"));
        policy = properties.get(POLICY_TEMPLATE_PROPERTY);

        useServerSideEncryption = Boolean.parseBoolean(properties.get(S3BinaryManager.SERVERSIDE_ENCRYPTION_PROPERTY));

        AWSCredentialsProvider credentials = S3Utils.getAWSCredentialsProvider(awsSecretKeyId, awsSecretAccessKey,
                awsSessionToken);
        stsClient = initializeSTSClient(credentials);
        amazonS3 = initializeS3Client(credentials);

        if (!isBlank(bucketPrefix) && !bucketPrefix.endsWith("/")) {
            log.debug(String.format("%s %s S3 bucket prefix should end with '/': added automatically.",
                    BUCKET_PREFIX_PROPERTY, bucketPrefix));
            bucketPrefix += "/";
        }

        blobProviderId = defaultString(properties.get(BLOB_PROVIDER_ID_PROPERTY), transientStoreName);
    }

    protected AWSSecurityTokenService initializeSTSClient(AWSCredentialsProvider credentials) {
        AWSSecurityTokenServiceClientBuilder builder = AWSSecurityTokenServiceClientBuilder.standard();
        initializeBuilder(builder, credentials);
        return builder.build();
    }

    protected AmazonS3 initializeS3Client(AWSCredentialsProvider credentials) {
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();
        initializeBuilder(builder, credentials);
        builder.setPathStyleAccessEnabled(pathStyleAccessEnabled);
        builder.setAccelerateModeEnabled(accelerateModeEnabled);
        return builder.build();
    }

    protected void initializeBuilder(AwsClientBuilder<?, ?> builder, AWSCredentialsProvider credentials) {
        if (isBlank(endpoint)) {
            builder.setRegion(region);
        } else {
            builder.setEndpointConfiguration(new EndpointConfiguration(endpoint, region));
        }
        builder.setCredentials(credentials);
    }

    @Override
    public Batch getBatch(String batchId) {
        Map<String, Serializable> parameters = getBatchParameters(batchId);
        if (parameters == null) {
            return null;
        }

        // create the batch
        Batch batch = new Batch(batchId, parameters, getName(), getTransientStore());

        Credentials credentials = getAwsCredentials(batchId);

        Map<String, Object> properties = batch.getProperties();
        properties.put(INFO_AWS_SECRET_KEY_ID, credentials.getAccessKeyId());
        properties.put(INFO_AWS_SECRET_ACCESS_KEY, credentials.getSecretAccessKey());
        properties.put(INFO_AWS_SESSION_TOKEN, credentials.getSessionToken());
        properties.put(INFO_BUCKET, bucket);
        properties.put(INFO_BASE_KEY, bucketPrefix);
        properties.put(INFO_EXPIRATION, credentials.getExpiration().toInstant().toEpochMilli());
        properties.put(INFO_AWS_ENDPOINT, defaultIfBlank(endpoint, null));
        properties.put(INFO_AWS_PATH_STYLE_ACCESS, pathStyleAccessEnabled);
        properties.put(INFO_AWS_REGION, region);
        properties.put(INFO_USE_S3_ACCELERATE, accelerateModeEnabled);

        return batch;
    }

    protected Credentials assumeRole(AssumeRoleRequest request) {
        return stsClient.assumeRole(request).getCredentials();
    }

    @Override
    public boolean completeUpload(String batchId, String fileIndex, BatchFileInfo fileInfo) {
        String fileKey = fileInfo.getKey();
        ObjectMetadata metadata = amazonS3.getObjectMetadata(bucket, fileKey);
        String key = metadata.getETag();
        if (isEmpty(key)) {
            return false;
        }
        String bucketKey = bucketPrefix + key;

        BlobInfo blobInfo = new BlobInfo();
        blobInfo.mimeType = metadata.getContentType();
        blobInfo.encoding = metadata.getContentEncoding();
        blobInfo.filename = fileInfo.getFilename();
        blobInfo.length = metadata.getContentLength();

        CopyObjectRequest copyObjectRequest = new CopyObjectRequest(bucket, fileKey, bucket, bucketKey);
        // server-side encryption
        if (useServerSideEncryption) { // TODO KMS
            // SSE-S3
            ObjectMetadata newObjectMetadata = new ObjectMetadata();
            newObjectMetadata.setSSEAlgorithm(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
            copyObjectRequest.setNewObjectMetadata(newObjectMetadata);
        }

        Copy copy = getTransferManager().copy(copyObjectRequest);
        try {
            copy.waitForCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NuxeoException(e);
        } finally {
            amazonS3.deleteObject(bucket, fileKey);
        }

        ObjectMetadata newMetadata = amazonS3.getObjectMetadata(bucket, bucketKey);

        blobInfo.key = key;
        blobInfo.digest = defaultString(newMetadata.getContentMD5(), key);
        Blob blob;
        try {
            BlobManager blobManager = Framework.getService(BlobManager.class);
            blob = blobManager.getBlobProvider(blobProviderId).readBlob(blobInfo);
        } catch (IOException e) {
            throw new NuxeoException(e);
        }

        Batch batch = getBatch(batchId);
        try {
            batch.addFile(fileIndex, blob, blob.getFilename(), blob.getMimeType());
        } catch (NuxeoException e) {
            amazonS3.deleteObject(bucket, bucketKey);
            throw e;
        }

        return true;
    }

    protected TransferManager getTransferManager() {
        BlobProvider blobProvider = Framework.getService(BlobManager.class).getBlobProvider(blobProviderId);
        if (!(blobProvider instanceof S3ManagedTransfer)) {
            throw new NuxeoException("BlobProvider does not implement S3ManagedTransfer");
        }
        return ((S3ManagedTransfer) blobProvider).getTransferManager();
    }

    protected Credentials getAwsCredentials(String batchId) {
        AssumeRoleRequest request = new AssumeRoleRequest().withRoleArn(roleArn)
                                                           .withPolicy(policy)
                                                           .withRoleSessionName(batchId);
        if (expiration > 0) {
            request.setDurationSeconds(expiration);
        }
        return assumeRole(request);
    }

    /** @since 11.1 */
    @Override
    public Map<String, Object> refreshToken(String batchId) {
        Objects.requireNonNull(batchId, "required batch ID");

        Credentials credentials = getAwsCredentials(batchId);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put(INFO_AWS_SECRET_KEY_ID, credentials.getAccessKeyId());
        result.put(INFO_AWS_SECRET_ACCESS_KEY, credentials.getSecretAccessKey());
        result.put(INFO_AWS_SESSION_TOKEN, credentials.getSessionToken());
        result.put(INFO_EXPIRATION, credentials.getExpiration().toInstant().toEpochMilli());
        return result;
    }

}
