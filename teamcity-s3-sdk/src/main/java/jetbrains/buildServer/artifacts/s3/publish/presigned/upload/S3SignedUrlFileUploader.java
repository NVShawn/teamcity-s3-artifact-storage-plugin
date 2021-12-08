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

package jetbrains.buildServer.artifacts.s3.publish.presigned.upload;

import com.intellij.openapi.diagnostic.Logger;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import jetbrains.buildServer.artifacts.s3.FileUploadInfo;
import jetbrains.buildServer.artifacts.s3.S3Configuration;
import jetbrains.buildServer.artifacts.s3.S3Util;
import jetbrains.buildServer.artifacts.s3.exceptions.FileUploadFailedException;
import jetbrains.buildServer.artifacts.s3.publish.S3FileUploader;
import jetbrains.buildServer.artifacts.s3.publish.logger.S3UploadLogger;
import jetbrains.buildServer.artifacts.s3.publish.presigned.util.CloseableForkJoinPoolAdapter;
import jetbrains.buildServer.artifacts.s3.publish.presigned.util.HttpClientUtil;
import jetbrains.buildServer.artifacts.s3.publish.presigned.util.LowLevelS3Client;
import jetbrains.buildServer.util.ExceptionUtil;
import jetbrains.buildServer.util.amazon.retry.Retrier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static jetbrains.buildServer.util.amazon.retry.Retrier.defaultRetrier;

public class S3SignedUrlFileUploader extends S3FileUploader {
  @NotNull
  private static final Logger LOGGER = Logger.getInstance(S3SignedUrlFileUploader.class.getName());
  public static final int MAX_VERBOSE_UPLOAD_LOGS = 10;
  @NotNull
  private final Supplier<PresignedUrlsProviderClient> myPresignedUrlsProviderClient;
  private final AtomicInteger logCounter = new AtomicInteger(0);

  public S3SignedUrlFileUploader(@NotNull final S3Configuration s3Configuration,
                                 @NotNull final S3UploadLogger s3UploadLogger,
                                 @NotNull final Supplier<PresignedUrlsProviderClient> presignedUrlsProviderClient) {
    super(s3Configuration, s3UploadLogger);
    myPresignedUrlsProviderClient = presignedUrlsProviderClient;
  }

  @NotNull
  @Override
  public Collection<FileUploadInfo> upload(@NotNull final Map<File, String> filesToUpload, @NotNull final Supplier<String> interrupter) {
    LOGGER.debug(() -> "Publishing artifacts using S3 configuration " + myS3Configuration);

    final Map<String, FileWithArtifactPath> normalizedObjectPaths = new HashMap<>();
    for (Map.Entry<File, String> entry : filesToUpload.entrySet()) {
      final File file = entry.getKey();
      final String artifactPath = S3Util.normalizeArtifactPath(entry.getValue(), file);
      final String objectKey = myS3Configuration.getPathPrefix() + artifactPath;

      final FileWithArtifactPath existingMapping = normalizedObjectPaths.get(objectKey);
      if (existingMapping != null && !existingMapping.getFile().equals(file)) {
        myLogger.warn("Found clashing artifacts path: " + artifactPath + " leading to different files [" + existingMapping.getFile().getPath() + "," + file.getPath() + "].\n" +
                      "Only the last file will be uploaded to the specified artifact path.");
      }
      normalizedObjectPaths.put(objectKey, FileWithArtifactPath.create(artifactPath, file));
    }

    final Retrier retrier = defaultRetrier(myS3Configuration.getAdvancedConfiguration().getRetriesNum(), myS3Configuration.getAdvancedConfiguration().getRetryDelay(), LOGGER);

    try (final CloseableForkJoinPoolAdapter forkJoinPool = new CloseableForkJoinPoolAdapter(myS3Configuration.getAdvancedConfiguration().getNThreads());
         final LowLevelS3Client lowLevelS3Client = createAwsClient(myS3Configuration);
         final S3SignedUploadManager uploadManager = new S3SignedUploadManager(myPresignedUrlsProviderClient.get(),
                                                                               myS3Configuration.getAdvancedConfiguration(),
                                                                               normalizedObjectPaths.keySet())) {

      if (normalizedObjectPaths.size() > MAX_VERBOSE_UPLOAD_LOGS) {
        myLogger.debug(normalizedObjectPaths.size() + " files are being published. Verbose logs will only be published for first " + MAX_VERBOSE_UPLOAD_LOGS + " files");
      }
      return normalizedObjectPaths.entrySet()
                                  .stream()
                                  .map(objectKeyToFileWithArtifactPath -> {
                                    try {
                                      return forkJoinPool.submit(() -> retrier
                                        .execute(S3PresignedUpload.create(objectKeyToFileWithArtifactPath.getValue().getArtifactPath(),
                                                                          objectKeyToFileWithArtifactPath.getKey(),
                                                                          objectKeyToFileWithArtifactPath.getValue().getFile(),
                                                                          myS3Configuration.getAdvancedConfiguration(),
                                                                          uploadManager,
                                                                          lowLevelS3Client,
                                                                          new PresignedUploadProgressListenerImpl(myLogger, uploadManager, interrupter, logCounter))));
                                    } catch (RejectedExecutionException e) {
                                      if (isPoolTerminating(forkJoinPool)) {
                                        LOGGER.debug("Artifact publishing rejected by pool shutdown");
                                      } else {
                                        LOGGER.warnAndDebugDetails("Artifact publishing rejected by pool", e);
                                      }
                                      return null;
                                    }
                                  })
                                  .filter(Objects::nonNull)
                                  .map((ForkJoinTask<FileUploadInfo> future) -> waitForCompletion(future, e -> {
                                    logPublishingError(e);
                                    if (isPublishingInterruptedException(e)) {
                                      shutdownPool(forkJoinPool);
                                    } else {
                                      ExceptionUtil.rethrowAsRuntimeException(e);
                                    }
                                  }))
                                  .filter(Objects::nonNull)
                                  .collect(Collectors.toList());
    } catch (Throwable th) {
      if (isPublishingInterruptedException(th)) {
        LOGGER.info("Publishing is interrupted " + th.getMessage(), th);
        return Collections.emptyList();
      } else {
        if (th instanceof FileUploadFailedException) {
          throw th;
        }
        LOGGER.warnAndDebugDetails("Got error while uploading artifacts " + th.getMessage(), th);
        throw new FileUploadFailedException(th.getMessage(), false, th);
      }
    }
  }

  private boolean isPoolTerminating(CloseableForkJoinPoolAdapter forkJoinPool) {
    return forkJoinPool.isShutdown() || forkJoinPool.isTerminated() || forkJoinPool.isTerminating();
  }

  private void logPublishingError(@NotNull final Throwable e) {
    if (isPublishingInterruptedException(e)) {
      myLogger.debug("Artifact publishing has been interrupted");
      LOGGER.debug("Artifact upload has been interrupted, will not continue with current upload");
    } else {
      myLogger.debug("Artifact publishing failed with error " + ExceptionUtil.getDisplayMessage(e));
      LOGGER.infoAndDebugDetails("Got exception while waiting for upload to finish, the upload will not continue and the artifact will be ignored", e);
    }
  }

  public static boolean isPublishingInterruptedException(@NotNull Throwable e) {
    final HttpClientUtil.HttpErrorCodeException errorCodeException = ExceptionUtil.getCause(e, HttpClientUtil.HttpErrorCodeException.class);
    if (errorCodeException != null && errorCodeException.isUploadInterrupted()) {
      return true;
    }
    return ExceptionUtil.getCause(e, InterruptedException.class) != null || ExceptionUtil.getCause(e, PublishingInterruptedException.class) != null;
  }

  private void shutdownPool(@NotNull final CloseableForkJoinPoolAdapter pool) {
    if (!isPoolTerminating(pool)) {
      LOGGER.debug("Shutting down artifact publishing pool");
      pool.shutdownNow();
    }
  }

  @Nullable
  private FileUploadInfo waitForCompletion(@NotNull final ForkJoinTask<FileUploadInfo> future, @NotNull final Consumer<Throwable> onError) {
    try {
      return future.get();
    } catch (final ExecutionException e) {
      onError.accept(e.getCause());
      return null;
    } catch (Throwable e) {
      onError.accept(e);
      return null;
    }
  }

  @NotNull
  private LowLevelS3Client createAwsClient(@NotNull final S3Configuration s3Configuration) {
    return new LowLevelS3Client(s3Configuration);
  }

  private static class PresignedUploadProgressListenerImpl implements PresignedUploadProgressListener {
    @NotNull
    private final S3UploadLogger myS3UploadLogger;
    @NotNull
    private final S3SignedUploadManager myUploadManager;
    @NotNull
    private final Supplier<String> myInterrupter;
    private final AtomicInteger myLogCounter;
    private S3PresignedUpload myUpload;

    private PresignedUploadProgressListenerImpl(@NotNull final S3UploadLogger uploadLogger,
                                                @NotNull final S3SignedUploadManager uploadManager,
                                                @NotNull final Supplier<String> interrupter, AtomicInteger logCounter) {
      myS3UploadLogger = uploadLogger;
      myUploadManager = uploadManager;
      myInterrupter = interrupter;
      myLogCounter = logCounter;
    }

    @Override
    public void setUpload(@NotNull S3PresignedUpload upload) {
      myUpload = upload;
    }

    @Override
    public void onPartUploadFailed(@NotNull Exception e) {
      myS3UploadLogger.warn("Upload chunk " + myUpload.description() + " failed with error: " + e.getMessage());
    }

    @Override
    public void onPartUploadSuccess(@NotNull String uploadUrl) {
      if (myLogCounter.get() < MAX_VERBOSE_UPLOAD_LOGS) {
        myS3UploadLogger.debug("Artifact upload " + myUpload.description() + " to " + uploadUrl + " at " + myUpload.getFinishedPercentage() + "%");
      }
    }

    @Override
    public void onFileUploadFailed(@NotNull Exception e) {
      myS3UploadLogger.warn("Upload " + myUpload.description() + " failed with error: " + e.getMessage());
      myUploadManager.onUploadFailed(myUpload);
    }

    @Override
    public void onFileUploadSuccess(@NotNull String uploadUrl) {
      if (myLogCounter.incrementAndGet() < MAX_VERBOSE_UPLOAD_LOGS) {
        myS3UploadLogger.debug("Artifact upload " + myUpload.description() + " to " + uploadUrl + " is finished");
      }
      myUploadManager.onUploadSuccess(myUpload);
    }

    @Override
    public void beforeUploadStarted() {
      checkInterrupted();
      if (myLogCounter.get() < MAX_VERBOSE_UPLOAD_LOGS) {
        myS3UploadLogger.debug("Started uploading " + myUpload.description());
      }
    }

    @Override
    public void beforePartUploadStarted(int partNumber) {
      checkInterrupted();
      if (myLogCounter.get() < MAX_VERBOSE_UPLOAD_LOGS) {
        myS3UploadLogger.debug(String.format("Started uploading part #%d of %s", partNumber, myUpload.description()));
      }
    }

    private void checkInterrupted() {
      final String interruptedReason = myInterrupter.get();
      if (interruptedReason != null) {
        throw new PublishingInterruptedException(interruptedReason);
      }
    }
  }

  private static class FileWithArtifactPath {
    private final String myArtifactPath;
    private final File myFile;

    private FileWithArtifactPath(@NotNull final String artifactPath, @NotNull final File file) {
      myArtifactPath = artifactPath;
      myFile = file;
    }

    public String getArtifactPath() {
      return myArtifactPath;
    }

    public File getFile() {
      return myFile;
    }

    public static FileWithArtifactPath create(@NotNull final String artifactPath, @NotNull final File file) {
      return new FileWithArtifactPath(artifactPath, file);
    }
  }
}
