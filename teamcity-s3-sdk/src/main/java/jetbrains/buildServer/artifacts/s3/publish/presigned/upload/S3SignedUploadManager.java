package jetbrains.buildServer.artifacts.s3.publish.presigned.upload;

import com.intellij.openapi.diagnostic.Logger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import jetbrains.buildServer.artifacts.s3.exceptions.FileUploadFailedException;
import jetbrains.buildServer.artifacts.s3.transport.MultipartUploadAbortRequestDto;
import jetbrains.buildServer.artifacts.s3.transport.MultipartUploadCompleteRequestDto;
import jetbrains.buildServer.artifacts.s3.transport.PresignedUrlDto;
import jetbrains.buildServer.util.CollectionsUtil;
import jetbrains.buildServer.util.ExceptionUtil;
import jetbrains.buildServer.util.NamedThreadFactory;
import jetbrains.buildServer.util.UptodateValue;
import jetbrains.buildServer.util.amazon.S3Util;
import jetbrains.buildServer.util.amazon.retry.Retrier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class S3SignedUploadManager implements AutoCloseable {
  @NotNull
  private static final Logger LOGGER = Logger.getInstance(S3SignedUploadManager.class);
  @NotNull
  private final List<String> myS3ObjectKeys;
  @NotNull
  private final UptodateValue<Map<String, PresignedUrlDto>> myCache;
  private final int myMaxUrlChunkSize;
  @NotNull
  private final Lock myFetchLock = new ReentrantLock();
  @NotNull
  private final Map<String, String> myMultipartUploadIds = new ConcurrentHashMap<>();
  @NotNull
  private final String myCorrelationId = UUID.randomUUID().toString();
  @NotNull
  private final PresignedUrlsProviderClient myPresignedUrlsProviderClient;
  @NotNull
  private final Retrier myRetrier;

  public S3SignedUploadManager(@NotNull final PresignedUrlsProviderClient presignedUrlsProviderClient,
                               @NotNull final S3Util.S3AdvancedConfiguration s3Config,
                               @NotNull final Collection<String> s3ObjectKeys) {
    myPresignedUrlsProviderClient = presignedUrlsProviderClient;
    myS3ObjectKeys = new ArrayList<>(s3ObjectKeys);
    myMaxUrlChunkSize = s3Config.getPresignedUrlMaxChunkSize();
    myRetrier = Retrier.defaultRetrier(s3Config.getRetriesNum(), s3Config.getRetryDelay(), LOGGER);
    myCache = new UptodateValue<>(this::fetchPresignedUrlsFromProvider, s3Config.getUrlTtlSeconds() * 1000L);
  }


  @NotNull
  public String getUrl(@NotNull final String s3ObjectKey) {
    PresignedUrlDto presignedUrlDto = myCache.getValue().get(s3ObjectKey);
    if (presignedUrlDto == null) {
      LOGGER.info(() -> "Presigned url for object key '" + s3ObjectKey + "' wasn't found in cached result from server, cache: '" + myCache.getValue().toString() + "'");
      throw new IllegalArgumentException("Specified object key not found in cached response from server");
    }
    if (presignedUrlDto.isMultipart()) {
      throw new IllegalArgumentException("Specified object key requested as a multipart upload, while regular upload expected");
    } else {
      if (presignedUrlDto.getPresignedUrlParts().size() != 1) {
        throw new IllegalArgumentException("Specified object key requested exactly [1] presigned url while ["
                                           + presignedUrlDto.getPresignedUrlParts().size() + "] urls returned from provider");
      }
      return Objects.requireNonNull(CollectionsUtil.findFirst(presignedUrlDto.getPresignedUrlParts(), CollectionsUtil.acceptAllFilter())).getUrl();
    }
  }


  @NotNull
  private Map<String, PresignedUrlDto> fetchPresignedUrlsFromProvider() {
    try {
      return NamedThreadFactory.executeWithNewThreadName("Fetching presigned urls for " + this, () -> {
        LOGGER.debug(() -> "Fetching presigned urls for manager " + this + " started...");
        myFetchLock.lock();
        try {
          return CollectionsUtil.split(myS3ObjectKeys, (myS3ObjectKeys.size() / myMaxUrlChunkSize) + 1)
                                .stream()
                                .peek(keys -> LOGGER.debug(() -> "Fetching chunk " + keys + " of size " + keys.size() + " of total " + myS3ObjectKeys.size() + " started"))
                                .map(myRetrier.retryableMapper(myPresignedUrlsProviderClient::getRegularPresignedUrls))
                                .peek(keys -> LOGGER.debug(() -> "Fetching chunk " + keys + " of size " + keys.size() + " of total " + myS3ObjectKeys.size() + " finished"))
                                .flatMap(presignedUrlDto -> presignedUrlDto.stream())
                                .collect(Collectors.toMap(o -> o.getObjectKey(), presignedUrlDto -> presignedUrlDto));
        } finally {
          myFetchLock.unlock();
          LOGGER.debug(() -> "Fetching presigned urls for manager " + this + " finished.");
        }
      });
    } catch (Exception e) {
      LOGGER.info("Fetching presigned urls for manager " + this + " failed.", e);
      ExceptionUtil.rethrowAsRuntimeException(e);
      return null;
    }
  }

  @NotNull
  public PresignedUrlDto getMultipartUploadUrls(@NotNull final String objectKey, @NotNull final List<String> digests) {
    final PresignedUrlDto presignedUrl = myRetrier.execute(() -> myPresignedUrlsProviderClient.getMultipartPresignedUrl(objectKey, digests));
    myMultipartUploadIds.put(presignedUrl.getObjectKey(), presignedUrl.getUploadId());
    return presignedUrl;
  }

  @Nullable
  public String getUrl(@NotNull final String objectKey, @Nullable final String digest) {
    final PresignedUrlDto presignedUrl = myRetrier.execute(() -> myPresignedUrlsProviderClient.getUrl(objectKey, digest));
    if (presignedUrl.getPresignedUrlParts().isEmpty()) {
      LOGGER.warn("Fetching URL with digest failed");
      return null;
    }
    return presignedUrl.getPresignedUrlParts().iterator().next().getUrl();
  }

  public void onUploadSuccess(@NotNull final S3PresignedUpload upload) {
    onUploadFinished(upload, true);
  }

  public void onUploadFailed(@NotNull final S3PresignedUpload upload) {
    onUploadFinished(upload, false);
  }

  private void onUploadFinished(@NotNull final S3PresignedUpload upload, final boolean isSuccess) {
    LOGGER.debug("Sending " + (isSuccess ? "success" : "abort") + " multipart upload for manager " + this + " started...");
    final String uploadId = myMultipartUploadIds.get(upload.getObjectKey());
    if (uploadId != null && upload.isMultipartUpload()) {
      try {
        myRetrier.execute(() -> {
          if (isSuccess) {
            myPresignedUrlsProviderClient.completeMultipartUpload(new MultipartUploadCompleteRequestDto(upload.getObjectKey(), uploadId, upload.getEtags()));
          } else {
            myPresignedUrlsProviderClient.abortMultipartUpload(new MultipartUploadAbortRequestDto(upload.getObjectKey(), uploadId));
          }
        });
        LOGGER.debug("Multipart upload for " + this + " has been " + (isSuccess ? "completed" : "aborted"));
      } catch (Exception e) {
        final String message = "Failed to " + (isSuccess ? "complete" : "abort") + " multipart upload for " + this;
        LOGGER.warnAndDebugDetails(message, e);
        throw new FileUploadFailedException(message, false, e);
      }
      myS3ObjectKeys.remove(upload.getObjectKey());
      myMultipartUploadIds.remove(upload.getObjectKey());
    }
  }

  @Override
  public String toString() {
    return "PresignedUpload{correlationId: " + myCorrelationId + ", objectKeysSize: " + myS3ObjectKeys.size() + "}";
  }

  @Override
  public void close() {
    myPresignedUrlsProviderClient.close();
  }
}
