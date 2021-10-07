package jetbrains.buildServer.artifacts.s3.publish.presigned.util;

import com.intellij.openapi.diagnostic.Logger;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import jetbrains.buildServer.artifacts.s3.S3Util;
import jetbrains.buildServer.artifacts.s3.exceptions.FileUploadFailedException;
import jetbrains.buildServer.artifacts.s3.publish.errors.CompositeHttpRequestErrorHandler;
import jetbrains.buildServer.artifacts.s3.publish.errors.HttpResponseErrorHandler;
import jetbrains.buildServer.artifacts.s3.publish.errors.S3ResponseErrorHandler;
import jetbrains.buildServer.artifacts.s3.publish.errors.TeamCityPresignedUrlsProviderErrorHandler;
import jetbrains.buildServer.http.HttpUserAgent;
import jetbrains.buildServer.http.HttpUtil;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.util.amazon.S3Util.S3AdvancedConfiguration;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.methods.EntityEnclosingMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.http.HttpHeaders;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LowLevelS3Client implements AutoCloseable {
  @NotNull
  private static final Logger LOGGER = Logger.getInstance(LowLevelS3Client.class);
  @NotNull
  private final HttpClient myHttpClient;
  @NotNull
  private final HttpResponseErrorHandler myErrorHandler = new CompositeHttpRequestErrorHandler(new S3ResponseErrorHandler(), new TeamCityPresignedUrlsProviderErrorHandler());
  private final boolean myCheckConsistency;

  public LowLevelS3Client(@NotNull final S3AdvancedConfiguration s3Config) {
    myHttpClient = HttpUtil.createHttpClient(s3Config.getConnectionTimeout());
    myHttpClient.setHttpConnectionManager(HttpClientUtil.createConnectionManager(s3Config.getConnectionTimeout(), s3Config.getNThreads()));
    myCheckConsistency = s3Config.isConsistencyCheckEnabled();
  }

  @NotNull
  public String uploadFile(@NotNull final String url, @NotNull final File file) throws IOException {
    final DigestingFileRequestEntity entity = new DigestingFileRequestEntity(file, S3Util.getContentType(file));
    EntityEnclosingMethod request = put(url, entity);
    final String digest = entity.getDigest();
    checkEtagsConsistency(digest, request);
    return digest;
  }

  @Nullable
  public String fetchETag(@NotNull final String url) throws IOException {
    return parseEtags(head(url));
  }

  @NotNull
  public String uploadFilePart(@NotNull final String url, @NotNull final File file, final long start, final long size) throws IOException {
    final RepeatableFilePartRequestEntity entity = new RepeatableFilePartRequestEntity(file, start, size);
    final HttpMethodBase request = put(url, entity);
    final String digest = entity.getDigest();
    checkEtagsConsistency(digest, request);
    return digest;
  }

  @NotNull
  private String parseEtags(@NotNull final HttpMethodBase request) {
    final Header eTags = request.getResponseHeader("ETag");
    if (eTags != null) {
      return StringUtil.unquoteString(eTags.getValue());
    } else {
      throw new FileUploadFailedException("Response does not contain etags", true);
    }
  }

  @NotNull
  private HttpMethodBase head(@NotNull final String url) throws IOException {
    final HttpMethodBase request = headRequest(url);
    request.setRequestHeader("Accept", "application/xml");
    HttpClientUtil.executeAndReleaseConnection(myHttpClient, request, myErrorHandler);
    return request;
  }

  @NotNull
  private EntityEnclosingMethod put(@NotNull final String url, @NotNull final RequestEntity requestEntity) throws IOException {
    final EntityEnclosingMethod request = putRequest(url);
    request.setRequestEntity(requestEntity);
    request.setRequestHeader("Accept", "application/xml");
    HttpClientUtil.executeAndReleaseConnection(myHttpClient, request, myErrorHandler);
    return request;
  }

  @NotNull
  private EntityEnclosingMethod putRequest(@NotNull String url) {
    return withUserAgent(new PutMethod(url));
  }

  @NotNull
  private HeadMethod headRequest(@NotNull final String url) {
    return withUserAgent(new HeadMethod(url));
  }

  @NotNull
  private <T extends HttpMethodBase> T withUserAgent(@NotNull final T request) {
    request.setRequestHeader(HttpHeaders.USER_AGENT, HttpUserAgent.getUserAgent());
    return request;
  }

  private void checkEtagsConsistency(@NotNull final String digest, @NotNull final HttpMethodBase request) {
    if (myCheckConsistency) {
      String etag = parseEtags(request);
      if (!Objects.equals(etag, digest)) {
        throw new FileUploadFailedException("Consistency check failed. Calculated digest [" + digest + "] is different from S3 etag [" + etag + "]", true);
      } else {
        LOGGER.debug("Consistency check successful");
      }
    }
  }

  @Override
  public void close() {
    HttpClientUtil.shutdown(myHttpClient);
  }
}
