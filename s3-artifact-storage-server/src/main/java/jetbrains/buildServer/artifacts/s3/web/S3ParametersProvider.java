/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.artifacts.s3.web;

import jetbrains.buildServer.artifacts.s3.S3Constants;
import jetbrains.buildServer.artifacts.s3.cloudfront.CloudFrontConstants;

public class S3ParametersProvider {
  public String getBucketName() {
    return S3Constants.S3_BUCKET_NAME;
  }

  public String getBucketNameWasProvidedAsString() {
    return S3Constants.S3_BUCKET_NAME_WAS_PROVIDED_AS_STRING;
  }

  public String getUsePresignUrlsForUpload() {
    return S3Constants.S3_USE_PRE_SIGNED_URL_FOR_UPLOAD;
  }

  public String getContainersPath() {
    return String.format("/plugins/s3-artifact-storage/%s.html", S3Constants.S3_SETTINGS_PATH);
  }

  public String getForceVirtualHostAddressing() {
    return S3Constants.S3_FORCE_VIRTUAL_HOST_ADDRESSING;
  }

  public String getPathPrefix() {
    return S3Constants.S3_PATH_PREFIX_SETTING;
  }

  public String getMultipartUploadThreshold() {
    return S3Constants.S3_MULTIPART_UPLOAD_THRESHOLD;
  }

  public String getMultipartUploadPartSize() {
    return S3Constants.S3_MULTIPART_MINIMUM_UPLOAD_PART_SIZE;
  }

  public String getCloudFrontEnabled(){
    return CloudFrontConstants.S3_CLOUDFRONT_ENABLED;
  }

  public String getCloudFrontSettingsGroup(){
    return CloudFrontConstants.S3_CLOUDFRONT_SETTINGS_GROUP;
  }

  public String getCloudFrontDistribution(){
    return CloudFrontConstants.S3_CLOUDFRONT_DISTRIBUTION;
  }

  public String getCloudFrontPublicKeyId(){
    return CloudFrontConstants.S3_CLOUDFRONT_PUBLIC_KEY_ID;
  }

  public String getCloudFrontPrivateSshKey(){
    return CloudFrontConstants.S3_CLOUDFRONT_PRIVATE_SSH_KEY;
  }
}
