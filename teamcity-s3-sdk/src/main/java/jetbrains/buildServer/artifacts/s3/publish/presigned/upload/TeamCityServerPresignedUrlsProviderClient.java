package jetbrains.buildServer.artifacts.s3.publish.presigned.upload;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import jetbrains.buildServer.artifacts.ArtifactTransportAdditionalHeadersProvider;
import jetbrains.buildServer.artifacts.s3.S3Constants;
import jetbrains.buildServer.artifacts.s3.publish.errors.CompositeHttpRequestErrorHandler;
import jetbrains.buildServer.artifacts.s3.publish.errors.HttpResponseErrorHandler;
import jetbrains.buildServer.artifacts.s3.publish.errors.S3ServerResponseErrorHandler;
import jetbrains.buildServer.artifacts.s3.publish.errors.TeamCityPresignedUrlsProviderErrorHandler;
import jetbrains.buildServer.artifacts.s3.publish.presigned.util.HttpClientUtil;
import jetbrains.buildServer.artifacts.s3.transport.*;
import jetbrains.buildServer.http.HttpUserAgent;
import jetbrains.buildServer.http.HttpUtil;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.util.ExceptionUtil;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.xmlrpc.NodeIdHolder;
import jetbrains.buildServer.xmlrpc.XmlRpcConstants;
import org.apache.commons.httpclient.Cookie;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpConnectionManager;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.entity.ContentType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static jetbrains.buildServer.artifacts.s3.S3Constants.S3_ARTIFACT_KEYS_HEADER_MAX_NUMBER;
import static jetbrains.buildServer.artifacts.s3.transport.PresignedUrlRequestSerializer.*;

public class TeamCityServerPresignedUrlsProviderClient implements PresignedUrlsProviderClient {
  @NotNull
  private static final Logger LOGGER = Logger.getInstance(TeamCityServerPresignedUrlsProviderClient.class);
  public static final int DEFAULT_ARTIFACT_KEYS_HEADER_MAX_NUMBER = 10;
  @NotNull
  private final String myPresignedUrlsPostUrl;
  @NotNull
  private final HttpClient myTeamCityClient;
  @NotNull
  private final Collection<ArtifactTransportAdditionalHeadersProvider> myAdditionalHeadersProviders;
  @NotNull
  private final AtomicBoolean myShutdown = new AtomicBoolean(false);
  @NotNull
  private final HttpResponseErrorHandler myErrorHandler = new CompositeHttpRequestErrorHandler(new S3ServerResponseErrorHandler(), new TeamCityPresignedUrlsProviderErrorHandler());

  private final NodeIdHolder myNodeIdHolder;
  private final String myServerUrl;

  public TeamCityServerPresignedUrlsProviderClient(@NotNull final TeamCityConnectionConfiguration teamCityConnectionConfiguration,
                                                   @NotNull final Collection<ArtifactTransportAdditionalHeadersProvider> additionalHeadersProviders) {
    myPresignedUrlsPostUrl = teamCityConnectionConfiguration.getTeamCityUrl() + "/httpAuth/" + StringUtil.removeLeadingSlash(teamCityConnectionConfiguration.getUrlsProviderPath());
    myTeamCityClient = createClient(teamCityConnectionConfiguration);
    myAdditionalHeadersProviders = additionalHeadersProviders;
    myNodeIdHolder = teamCityConnectionConfiguration.getNodeIdHolder();
    myServerUrl = teamCityConnectionConfiguration.getTeamCityUrl();
  }

  @NotNull
  private HttpClient createClient(@NotNull final TeamCityConnectionConfiguration config) {
    try {
      final HttpClient httpClient = HttpUtil.createHttpClient(config.getConnectionTimeout(), new URL(myPresignedUrlsPostUrl), config.getCredentials());
      final HttpConnectionManager httpConnectionManager = HttpClientUtil.createConnectionManager(config.getConnectionTimeout(), config.getNThreads());
      httpClient.setHttpConnectionManager(httpConnectionManager);
      return httpClient;
    } catch (MalformedURLException e) {
      LOGGER.warnAndDebugDetails("Malformed url to TeamCity server", e);
      throw new MisconfigurationException(e);
    }
  }

  @Override
  @NotNull
  public Collection<PresignedUrlDto> getRegularPresignedUrls(@NotNull final List<String> objectKeys, @NotNull Map<String, String> digests) {
    validateClient();
    try {
      final PostMethod post = postTemplate();
      post.setRequestEntity(s3ObjectKeysRequestEntity(objectKeys, digests));
      int maxHeaders = TeamCityProperties.getInteger(S3_ARTIFACT_KEYS_HEADER_MAX_NUMBER, DEFAULT_ARTIFACT_KEYS_HEADER_MAX_NUMBER);
      for (int i = 0; i < Math.min(maxHeaders, objectKeys.size()); i++) {
        post.addRequestHeader(S3Constants.S3_ARTIFACT_KEYS_HEADER_NAME, objectKeys.get(i));
      }

      setNodeIdCookie();

      final String responseBody = HttpClientUtil.executeAndReleaseConnection(myTeamCityClient, post, myErrorHandler);

      return deserializeResponseV2(responseBody).getPresignedUrls();
    } catch (HttpClientUtil.HttpErrorCodeException | IOException e) {
      LOGGER.warnAndDebugDetails("Failed resolving S3 pre-signed URL, got exception " + e.getMessage(), e);
      throw new FetchFailedException(e);
    }
  }

  private void setNodeIdCookie() {
    // set current node id cookie to instruct proxy server where this request should be landed
    final String nodeId = myNodeIdHolder.getOwnerNodeId();
    if (nodeId == null) return;

    final Cookie cookie = new Cookie(myServerUrl, XmlRpcConstants.NODE_ID_COOKIE, nodeId, "/", myNodeIdHolder.getExpirationTime(), false);
    myTeamCityClient.getState().addCookie(cookie);
  }

  @NotNull
  public PresignedUrlDto getUrl(@NotNull final String objectKey, @Nullable String digest, @Nullable Long ttl) {
    validateClient();
    try {
      return fetchPresignedUrlDto(objectKey, s3ObjectKeyRequestEntity(objectKey, digest, ttl));
    } catch (HttpClientUtil.HttpErrorCodeException | IOException e) {
      LOGGER.warnAndDebugDetails("Failed resolving S3 pre-signed URL, got exception " + e.getMessage(), e);
      throw new FetchFailedException(e);
    }
  }

  @Override
  @NotNull
  public PresignedUrlDto getMultipartPresignedUrl(@NotNull final String objectKey, @NotNull final List<String> digests, @Nullable String uploadId, @Nullable Long ttl) {
    validateClient();
    try {
      return fetchPresignedUrlDto(objectKey, multipartRequestEntity(objectKey, digests, uploadId, ttl));
    } catch (Exception e) {
      throw new FetchFailedException(e);
    }
  }

  @NotNull
  private PresignedUrlDto fetchPresignedUrlDto(@NotNull final String objectKey, @NotNull final StringRequestEntity requestEntity) throws IOException {
    final PostMethod post = postTemplate();
    post.setRequestEntity(requestEntity);
    post.setRequestHeader("Content-Type", "application/xml; charset=" + StandardCharsets.UTF_8.name());
    post.setRequestHeader(S3Constants.S3_ARTIFACT_KEYS_HEADER_NAME, objectKey);
    final String responseBody = HttpClientUtil.executeAndReleaseConnection(myTeamCityClient, post, myErrorHandler);
    final PresignedUrlListResponseDto presignedUrlListResponseDto = deserializeResponseV2(responseBody);
    return presignedUrlListResponseDto.getPresignedUrls()
                                      .stream()
                                      .filter(presignedUrlDto -> Objects.equals(presignedUrlDto.getObjectKey(), objectKey))
                                      .findFirst()
                                      .orElseThrow(() -> new IllegalArgumentException("Response from s3 urls provider does not contain required object " + objectKey));
  }

  @Override
  public void completeMultipartUpload(@NotNull MultipartUploadCompleteRequestDto multipartUploadComplete) {
    validateClient();
    finishMultipartUpload(Objects.requireNonNull(multipartUploadComplete.getObjectKey()),
                          multipartUploadComplete.getEtags(),
                          Objects.requireNonNull(multipartUploadComplete.getUploadId()),
                          true);
  }

  @Override
  public void abortMultipartUpload(@NotNull final MultipartUploadAbortRequestDto multipartUploadAbort) {
    validateClient();
    finishMultipartUpload(multipartUploadAbort.getObjectKey(), null, multipartUploadAbort.getUploadId(), false);
  }

  @Override
  public void startMultipartUpload(@NotNull final MultipartUploadStartRequestDto multipartUploadStart) {
    // do nothing
  }

  private void finishMultipartUpload(@NotNull final String objectKey, @Nullable final List<String> etags, @NotNull final String uploadId, final boolean isSuccessful) {
    LOGGER.debug(() -> "Multipart upload " + uploadId + " signaling " + (isSuccessful ? "success" : "failure") + " started");
    final PostMethod post = postTemplate();
    post.setParameter(OBJECT_KEY, objectKey);
    post.setParameter(OBJECT_KEY + "_BASE64", Base64.getEncoder().encodeToString(objectKey.getBytes(StandardCharsets.UTF_8)));
    post.setParameter(FINISH_UPLOAD, uploadId);
    post.setParameter(UPLOAD_SUCCESSFUL, String.valueOf(isSuccessful));
    if (isSuccessful && etags != null) {
      etags.forEach(etag -> post.addParameter(ETAGS, etag));
    }
    try {
      HttpClientUtil.executeAndReleaseConnection(myTeamCityClient, post, myErrorHandler);
      LOGGER.debug(() -> "Multipart upload " + uploadId + " signaling " + (isSuccessful ? "success" : "failure") + " finished");
    } catch (Exception e) {
      LOGGER.warnAndDebugDetails("Multipart upload " + uploadId + " signaling " + (isSuccessful ? "success" : "failure") + " failed: " + e.getMessage(), e);
      ExceptionUtil.rethrowAsRuntimeException(e);
    }
  }


  @NotNull
  private StringRequestEntity multipartRequestEntity(@NotNull final String s3ObjectKey, @NotNull final List<String> digests, @Nullable String uploadId, @Nullable Long ttl) {
    try {
      return requestEntity(PresignedUrlRequestSerializer.serializeRequestV2(PresignedUrlListRequestDto.forObjectKeyMultipart(s3ObjectKey, uploadId, digests, ttl)));
    } catch (UnsupportedEncodingException e) {
      LOGGER.warnAndDebugDetails("Unsupported encoding", e);
      throw new MisconfigurationException(e);
    }
  }

  @NotNull
  private StringRequestEntity requestEntity(@NotNull String xml) throws UnsupportedEncodingException {
    return new StringRequestEntity(xml, ContentType.APPLICATION_XML.getMimeType(), StandardCharsets.UTF_8.name());
  }

  @NotNull
  private StringRequestEntity s3ObjectKeysRequestEntity(@NotNull Collection<String> s3ObjectKeys, Map<String, String> digests) {
    try {
      final List<Pair<String, String>> keysWithDigests = s3ObjectKeys.stream().map(k -> Pair.create(k, digests.get(k))).collect(Collectors.toList());
      return requestEntity(PresignedUrlRequestSerializer.serializeRequestV2(PresignedUrlListRequestDto.forObjectKeysWithDigests(keysWithDigests)));
    } catch (UnsupportedEncodingException e) {
      LOGGER.warnAndDebugDetails("Unsupported encoding", e);
      throw new MisconfigurationException(e);
    }
  }

  @NotNull
  private StringRequestEntity s3ObjectKeyRequestEntity(@NotNull String objectKey, @Nullable String digest, @Nullable Long ttl) {
    try {
      return requestEntity(PresignedUrlRequestSerializer.serializeRequestV2(PresignedUrlListRequestDto.forObjectKeyWithDigest(objectKey, digest, ttl)));
    } catch (UnsupportedEncodingException e) {
      LOGGER.warnAndDebugDetails("Unsupported encoding", e);
      throw new MisconfigurationException(e);
    }
  }

  @NotNull
  private PostMethod postTemplate() {
    final PostMethod post = new PostMethod(myPresignedUrlsPostUrl);
    post.addRequestHeader(HttpHeaders.USER_AGENT, HttpUserAgent.getUserAgent());
    post.setRequestHeader("Accept", "application/xml");
    post.setRequestHeader("Accept-Charset", StandardCharsets.UTF_8.name());
    addAdditionalHeaders(post);
    post.setDoAuthentication(true);
    return post;
  }

  @Override
  public void close() {
    myShutdown.set(true);
    HttpClientUtil.shutdown(myTeamCityClient);
  }

  private void validateClient() {
    if (myShutdown.get()) {
      LOGGER.warn("TeamCity presigned urls provider client already shut down");
      throw new ClientAlreadyShutdownException("TeamCity presigned urls provider client already shut down");
    }
  }

  private void addAdditionalHeaders(HttpMethod request) {
    HashMap<String, String> headerToProviderMap = new HashMap<>();
    ArtifactTransportAdditionalHeadersProvider.Configuration configuration = () -> request.getName();
    for (ArtifactTransportAdditionalHeadersProvider extension : myAdditionalHeadersProviders) {
      List<ArtifactTransportAdditionalHeadersProvider.Header> headers = extension.getHeaders(configuration);
      String extensionName = extension.getClass().getName();
      for (ArtifactTransportAdditionalHeadersProvider.Header header : headers) {
        String existingExtensionsName = headerToProviderMap.get(header.getName().toUpperCase());
        if (existingExtensionsName == null) {
          request.addRequestHeader(header.getName(), header.getValue());
          headerToProviderMap.put(header.getName().toUpperCase(), extensionName);
        } else {
          String headerName = header.getName();
          String message = String.format("Multiple extensions(%s, %s) provide the same additional header '%s'", existingExtensionsName, extensionName, headerName);
          LOGGER.warn(message);
        }
      }
    }
  }

  public static class ClientAlreadyShutdownException extends IllegalStateException {
    public ClientAlreadyShutdownException(@NotNull final String message) {
      super(message);
    }
  }
}
