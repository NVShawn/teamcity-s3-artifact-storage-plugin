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

package jetbrains.buildServer.artifacts.s3.publish;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import jetbrains.buildServer.artifacts.s3.FileUploadInfo;
import jetbrains.buildServer.artifacts.s3.S3Configuration;
import jetbrains.buildServer.artifacts.s3.exceptions.InvalidSettingsException;
import jetbrains.buildServer.artifacts.s3.publish.logger.S3UploadLogger;
import jetbrains.buildServer.artifacts.s3.publish.presigned.upload.S3SignedUrlFileUploader;
import jetbrains.buildServer.artifacts.s3.publish.presigned.upload.TeamCityConnectionConfiguration;
import jetbrains.buildServer.util.amazon.S3Util;
import org.jetbrains.annotations.NotNull;

public abstract class S3FileUploader {
  @NotNull
  protected final S3UploadLogger myLogger;
  @NotNull
  protected final S3Configuration myS3Configuration;

  public S3FileUploader(@NotNull final S3Configuration s3Configuration, @NotNull S3UploadLogger logger) {
    this.myS3Configuration = s3Configuration;
    this.myLogger = logger;
  }

  @NotNull
  public static S3Util.S3AdvancedConfiguration configuration(@NotNull final Map<String, String> sharedConfigurationParameters,
                                                             @NotNull final Map<String, String> artifactStorageSettings) {
    return new S3Util.S3AdvancedConfiguration()
      .withNumberOfRetries(jetbrains.buildServer.artifacts.s3.S3Util.getNumberOfRetries(sharedConfigurationParameters))
      .withRetryDelayMs(jetbrains.buildServer.artifacts.s3.S3Util.getRetryDelayInMs(sharedConfigurationParameters))
      .withPresignedUrlsChunkSize(jetbrains.buildServer.artifacts.s3.S3Util.getMaxNumberOfPresignedUrlsToLoadInOneRequest(sharedConfigurationParameters))
      .withMinimumUploadPartSize(jetbrains.buildServer.artifacts.s3.S3Util.getMinimumUploadPartSize(sharedConfigurationParameters, artifactStorageSettings))
      .withMultipartUploadThreshold(jetbrains.buildServer.artifacts.s3.S3Util.getMultipartUploadThreshold(sharedConfigurationParameters, artifactStorageSettings))
      .withPresignedMultipartUploadEnabled(jetbrains.buildServer.artifacts.s3.S3Util.getPresignedMultipartUploadEnabled(sharedConfigurationParameters))
      .withConnectionTimeout(jetbrains.buildServer.artifacts.s3.S3Util.getConnectionTimeout(sharedConfigurationParameters))
      .withNumberOfThreads(jetbrains.buildServer.artifacts.s3.S3Util.getNumberOfThreads(sharedConfigurationParameters))
      .withUrlTtlSeconds(jetbrains.buildServer.artifacts.s3.S3Util.getUrlTtlSeconds(sharedConfigurationParameters))
      .withShutdownClient();
  }

  public static S3FileUploader create(@NotNull final S3Configuration s3Configuration,
                                      @NotNull final S3UploadLogger s3UploadLogger,
                                      @NotNull final TeamCityConnectionConfiguration tcConnectionConfiguration) {
    return s3Configuration.isUsePresignedUrls()
           ? new S3SignedUrlFileUploader(s3Configuration, s3UploadLogger, tcConnectionConfiguration)
           : new S3RegularFileUploader(s3Configuration, s3UploadLogger);
  }

  @NotNull
  public abstract Collection<FileUploadInfo> upload(@NotNull final Map<File, String> filesToUpload, @NotNull final Supplier<String> interrupter)
    throws InvalidSettingsException;
}
