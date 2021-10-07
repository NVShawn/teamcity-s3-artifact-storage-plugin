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

import com.amazonaws.SdkClientException;
import com.amazonaws.auth.policy.Policy;
import com.amazonaws.auth.policy.Principal;
import com.amazonaws.auth.policy.Statement;
import com.amazonaws.auth.policy.actions.S3Actions;
import com.amazonaws.auth.policy.resources.S3ObjectResource;
import com.amazonaws.services.cloudfront.AmazonCloudFront;
import com.amazonaws.services.cloudfront.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.BucketPolicy;
import com.intellij.openapi.diagnostic.Logger;
import java.io.IOException;
import java.io.StringWriter;
import java.security.*;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.annotation.XmlRootElement;
import jetbrains.buildServer.Used;
import jetbrains.buildServer.artifacts.s3.S3Util;
import jetbrains.buildServer.artifacts.s3.serialization.S3XmlSerializerFactory;
import jetbrains.buildServer.controllers.ActionErrors;
import jetbrains.buildServer.controllers.BaseFormXmlController;
import jetbrains.buildServer.controllers.BasePropertiesBean;
import jetbrains.buildServer.controllers.admin.projects.PluginPropertiesUtil;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.auth.AccessChecker;
import jetbrains.buildServer.util.amazon.AWSCommonParams;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;

import static jetbrains.buildServer.artifacts.s3.cloudfront.CloudFrontConstants.*;

public class S3CloudFrontDistributionCreationController extends BaseFormXmlController {
  private static final Logger LOG = Logger.getInstance(S3CloudFrontDistributionCreationController.class.getName());

  public static final String BASE_COMMENT = "Created by TeamCity";
  public static final String COMMENT = BASE_COMMENT + " for '%s'";
  public static final String NUMBERED_COMMENT = COMMENT + " (%d)";
  public static final AllowedMethods ALL_METHODS_ALLOWED = new AllowedMethods().withItems(Method.values()).withQuantity(7);
  public static final Predicate<CachePolicy> IS_GENERATED_POLICY = p -> p.getCachePolicyConfig().getName().equals(S3_CLOUDFRONT_GENERATED_CACHE_POLICY);
  public static final Predicate<CachePolicy> IS_DEFAULT_POLICY = p -> p.getCachePolicyConfig().getName().equals(S3_CLOUDFRONT_DEFAULT_CACHE_POLICY);

  @NotNull
  private final ProjectManager myProjectManager;
  private final AccessChecker myAccessChecker;

  public S3CloudFrontDistributionCreationController(@NotNull final PluginDescriptor descriptor,
                                                    @NotNull WebControllerManager controllerManager,
                                                    @NotNull ProjectManager projectManager,
                                                    @NotNull AccessChecker accessChecker) {
    myProjectManager = projectManager;
    myAccessChecker = accessChecker;
    final String path = descriptor.getPluginResourcesPath("cloudFront/createDistribution.html");
    controllerManager.registerController(path, this);
    Provider provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
    if (provider == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  @Override
  @Nullable
  protected ModelAndView doGet(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) {
    return null;
  }

  @Override
  protected void doPost(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Element xmlResponse) {
    final BasePropertiesBean bean = new BasePropertiesBean(null);
    PluginPropertiesUtil.bindPropertiesFromRequest(request, bean);
    Map<String, String> params = bean.getProperties();
    String projectId = request.getParameter("projectId");

    final ActionErrors errors = new ActionErrors();

    SProject project = myProjectManager.findProjectByExternalId(projectId);

    if (project == null) {
      errors.addError(S3_CLOUDFRONT_CREATE_DISTRIBUTION, String.format("Project %s not found", projectId));
    } else {
      myAccessChecker.checkCanEditProject(project);

      String projectName = project.getName();

      try {
        KeyPair keyPair = generateKeyPair();
        String bucketName = S3Util.getBucketName(params);

        if (keyPair.getPrivate() != null && keyPair.getPublic() != null && bucketName != null) {
          String privateKey = toPemString("PRIVATE KEY", keyPair.getPrivate().getEncoded());
          String publicKey = toPemString("PUBLIC KEY", keyPair.getPublic().getEncoded());

          DistributionDTO distributionDTO = AWSCommonParams.withAWSClients(params, clients -> {
            AmazonCloudFront cloudFrontClient = clients.createCloudFrontClient();
            AmazonS3 s3Client = clients.createS3Client();

            String comment;

            long distrCount =
              cloudFrontClient.listDistributions(new ListDistributionsRequest()).getDistributionList().getItems().stream()
                              .filter(d -> d.getComment().startsWith(String.format(COMMENT, projectName))).count();
            if (distrCount > 0) {
              comment = String.format(NUMBERED_COMMENT, projectName, distrCount);
            } else {
              comment = String.format(COMMENT, projectName);
            }

            String name = "generated_" + UUID.randomUUID().toString().substring(0, 8);
            String publicKeyId = null;
            String keyGroupId = null;
            try {
              publicKeyId = uploadPublicKey(publicKey, name, comment, cloudFrontClient);
              keyGroupId = createKeyGroup(publicKeyId, name, comment, cloudFrontClient);
              Distribution distribution = createDistribution(keyGroupId, comment, bucketName, cloudFrontClient, s3Client);
              return new DistributionDTO(distribution.getId(), comment, publicKeyId, name, privateKey);
            } catch (SdkClientException e) {
              errors.addException(S3_CLOUDFRONT_CREATE_DISTRIBUTION, e);
              if (keyGroupId != null) {
                try {
                  cloudFrontClient.deleteKeyGroup(new DeleteKeyGroupRequest().withId(keyGroupId));
                } catch (SdkClientException clientException) {
                  LOG.warnAndDebugDetails("Encountered exception while trying to delete CloudFront key group", clientException);
                }
              }
              if (publicKeyId != null) {
                try {
                  cloudFrontClient.deletePublicKey(new DeletePublicKeyRequest().withId(publicKeyId));
                } catch (SdkClientException clientException) {
                  LOG.warnAndDebugDetails("Encountered exception while trying to delete CloudFront public key", clientException);
                }
              }
            }
            return null;
          });
          if (distributionDTO != null) {
            Element element = S3XmlSerializerFactory.getInstance().serializeAsElement(distributionDTO);
            xmlResponse.addContent(element);
          }
        }
      } catch (IllegalArgumentException | SdkClientException | IOException | NoSuchAlgorithmException e) {
        errors.addException(S3_CLOUDFRONT_CREATE_DISTRIBUTION, e);
      }
    }

    errors.serialize(xmlResponse);
  }

  @NotNull
  private String toPemString(@NotNull String header, @NotNull byte[] key) throws IOException {
    PemObject pemObject = new PemObject(header, key);
    StringWriter writer = new StringWriter();
    PemWriter pemWriter = new PemWriter(writer);
    pemWriter.writeObject(pemObject);
    pemWriter.flush();
    return writer.toString();
  }

  @NotNull
  private Distribution createDistribution(@NotNull String keyGroupId,
                                          @NotNull String comment,
                                          @NotNull String bucketName,
                                          @NotNull AmazonCloudFront cloudFrontClient,
                                          @NotNull AmazonS3 s3Client) {
    String originId = bucketName + "." + UUID.randomUUID();

    String oaiId = getOriginAccessIdentityId(s3Client, cloudFrontClient, bucketName);
    String bucketRegion = s3Client.getBucketLocation(bucketName);
    Origin origin = createOrigin(bucketName, bucketRegion, originId, oaiId);
    DistributionConfig distributionConfig = createDistributionConfig(cloudFrontClient, keyGroupId, origin, comment);
    CreateDistributionResult result = cloudFrontClient.createDistribution(new CreateDistributionRequest(distributionConfig));
    return result.getDistribution();
  }

  @NotNull
  private String createKeyGroup(@NotNull String publicKeyId, @NotNull String name, @NotNull String comment, @NotNull AmazonCloudFront cloudFrontClient) {
    CreateKeyGroupRequest createKeyGroupRequest = new CreateKeyGroupRequest()
      .withKeyGroupConfig(new KeyGroupConfig()
                            .withName(name)
                            .withComment(comment)
                            .withItems(publicKeyId));
    CreateKeyGroupResult keyGroup = cloudFrontClient.createKeyGroup(createKeyGroupRequest);

    return keyGroup.getKeyGroup().getId();
  }

  @NotNull
  private String uploadPublicKey(@NotNull String publicKey, @NotNull String name, @NotNull String comment, @NotNull AmazonCloudFront cloudFrontClient) {
    PublicKeyConfig config = new PublicKeyConfig()
      .withName(name)
      .withComment(comment)
      .withEncodedKey(publicKey)
      .withCallerReference(ZonedDateTime.now(ZoneOffset.UTC).toString());

    CreatePublicKeyResult result = cloudFrontClient.createPublicKey(new CreatePublicKeyRequest().withPublicKeyConfig(config));
    return result.getPublicKey().getId();
  }

  @NotNull
  private KeyPair generateKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
    keyGen.initialize(2048);
    return keyGen.generateKeyPair();
  }

  @NotNull
  private DistributionConfig createDistributionConfig(@NotNull AmazonCloudFront cloudFrontClient, @NotNull String keyGroupId, @NotNull Origin origin, @NotNull String comment) {
    DefaultCacheBehavior cacheBehavior = createDefaultCacheBehavior(cloudFrontClient, keyGroupId, origin.getId());

    return new DistributionConfig()
      .withDefaultCacheBehavior(cacheBehavior)
      .withOrigins(new Origins().withItems(origin).withQuantity(1))
      .withCallerReference(ZonedDateTime.now(ZoneOffset.UTC).toString())
      .withComment(comment)
      .withEnabled(true);
  }

  @NotNull
  private Origin createOrigin(@NotNull String bucketName, @NotNull String bucketRegion, @NotNull String originId, @NotNull String originAccessIdentityId) {

    String oaiId = String.format(S3_CLOUDFRONT_OAI_ID_TEMPLATE, originAccessIdentityId);
    return new Origin()
      .withS3OriginConfig(new S3OriginConfig().withOriginAccessIdentity(oaiId))
      .withDomainName(String.format(S3_BUCKET_DOMAIN_PATTERN, bucketName, bucketRegion))
      .withId(originId);
  }

  @NotNull
  private DefaultCacheBehavior createDefaultCacheBehavior(@NotNull AmazonCloudFront cloudFrontClient, @NotNull String keyGroupId, @NotNull String originId) {
    CachePolicy defaultPolicy = getOrCreateCachePolicy(cloudFrontClient);

    TrustedKeyGroups trustedKeyGroups = new TrustedKeyGroups()
      .withQuantity(1)
      .withEnabled(true)
      .withItems(keyGroupId);
    return new DefaultCacheBehavior()
      .withViewerProtocolPolicy(ViewerProtocolPolicy.RedirectToHttps)
      .withTargetOriginId(originId)
      .withAllowedMethods(ALL_METHODS_ALLOWED)
      .withCachePolicyId(defaultPolicy.getId())
      .withTrustedKeyGroups(trustedKeyGroups);
  }

  @NotNull
  private CachePolicy getOrCreateCachePolicy(@NotNull AmazonCloudFront cloudFrontClient) {
    List<CachePolicySummary> existingPolicies = cloudFrontClient.listCachePolicies(new ListCachePoliciesRequest()).getCachePolicyList().getItems();

    return existingPolicies
      .stream()
      .map(p -> p.getCachePolicy())
      .filter(IS_GENERATED_POLICY)
      .findAny()
      .orElseGet(() -> createNewPolicy(cloudFrontClient, existingPolicies));
  }

  @NotNull
  private CachePolicy createNewPolicy(@NotNull AmazonCloudFront cloudFrontClient, @NotNull List<CachePolicySummary> existingPolicies) {
    CachePolicy defaultPolicy = existingPolicies
      .stream()
      .map(p -> p.getCachePolicy())
      .filter(IS_DEFAULT_POLICY)
      .findAny()
      .orElseThrow(() -> new AmazonCloudFrontException(String.format("Managed Cache policy '%s' not found", S3_CLOUDFRONT_DEFAULT_CACHE_POLICY)));

    ParametersInCacheKeyAndForwardedToOrigin forwardingParameters = new ParametersInCacheKeyAndForwardedToOrigin()
      .withQueryStringsConfig(new CachePolicyQueryStringsConfig().withQueryStringBehavior(CachePolicyQueryStringBehavior.All))
      .withCookiesConfig(new CachePolicyCookiesConfig().withCookieBehavior(CachePolicyCookieBehavior.None))
      .withHeadersConfig(new CachePolicyHeadersConfig().withHeaderBehavior(CachePolicyHeaderBehavior.None))
      .withEnableAcceptEncodingGzip(true)
      .withEnableAcceptEncodingBrotli(true);

    CreateCachePolicyRequest request = new CreateCachePolicyRequest()
      .withCachePolicyConfig(defaultPolicy.getCachePolicyConfig()
                                          .clone()
                                          .withName(S3_CLOUDFRONT_GENERATED_CACHE_POLICY)
                                          .withComment(BASE_COMMENT)
                                          .withParametersInCacheKeyAndForwardedToOrigin(forwardingParameters)
      );

    CachePolicy newPolicy = cloudFrontClient.createCachePolicy(request).getCachePolicy();

    return newPolicy != null ? newPolicy : defaultPolicy;
  }

  @NotNull
  private String getOriginAccessIdentityId(@NotNull AmazonS3 s3Client, @NotNull AmazonCloudFront cloudFrontClient, @NotNull String bucketName) {
    Policy policy = getPolicy(bucketName, s3Client);
    List<CloudFrontOriginAccessIdentitySummary> existingIdentities = cloudFrontClient.listCloudFrontOriginAccessIdentities(new ListCloudFrontOriginAccessIdentitiesRequest())
                                                                                     .getCloudFrontOriginAccessIdentityList()
                                                                                     .getItems();
    List<String> existingOaiIds = existingIdentities
      .stream()
      .map(CloudFrontOriginAccessIdentitySummary::getId)
      .collect(Collectors.toList());
    String oaiId = extractOriginAccessIdentity(policy, existingOaiIds);

    if (oaiId == null) {
      oaiId = getDefaultOriginAccessIdentity(cloudFrontClient, s3Client, bucketName, policy, existingIdentities);
    }
    return oaiId;
  }

  @NotNull
  private String getDefaultOriginAccessIdentity(@NotNull AmazonCloudFront cloudFrontClient,
                                                @NotNull AmazonS3 s3Client,
                                                @NotNull String bucketName,
                                                @NotNull Policy policy,
                                                @NotNull List<CloudFrontOriginAccessIdentitySummary> existingIdentities) {
    String oaiId = existingIdentities.stream()
                                     .filter(o -> o.getComment().equals(S3_CLOUDFRONT_DEFAULT_OAI_COMMENT))
                                     .findFirst()
                                     .map(CloudFrontOriginAccessIdentitySummary::getId)
                                     .orElseGet(() -> createOriginAccessIdentity(cloudFrontClient, S3_CLOUDFRONT_DEFAULT_OAI_COMMENT));

    Collection<Statement> statements = policy.getStatements();
    statements.add(generateStatementForOAI(bucketName, oaiId));
    policy.setStatements(statements);
    s3Client.setBucketPolicy(bucketName, policy.toJson());
    return oaiId;
  }

  @Nullable
  private String extractOriginAccessIdentity(@NotNull Policy policy, @NotNull List<String> existingIds) {
    String pattern = S3_CLOUDFRONT_OAI_SUBSTRING;

    return policy.getStatements().stream()
                 .flatMap(s -> s.getPrincipals().stream())
                 .filter(p -> p.getProvider().equals("AWS"))
                 .map(Principal::getId)
                 .filter(id -> id.contains(S3_CLOUDFRONT_OAI_SUBSTRING))
                 .map(id -> id.substring(id.indexOf(pattern) + pattern.length()))
                 .filter(existingIds::contains)
                 .findAny().orElse(null);
  }

  @NotNull
  private Policy getPolicy(@NotNull String bucketName, @NotNull AmazonS3 s3Client) {
    BucketPolicy bucketPolicy = s3Client.getBucketPolicy(bucketName);
    Policy policy;
    if (bucketPolicy.getPolicyText() == null) {
      policy = new Policy();
    } else {
      policy = Policy.fromJson(bucketPolicy.getPolicyText());
    }
    return policy;
  }

  @NotNull
  private Statement generateStatementForOAI(@NotNull String bucketName, @NotNull String id) {
    return new Statement(Statement.Effect.Allow)
      .withActions(S3Actions.GetObject, S3Actions.PutObject, S3Actions.DeleteObject)
      .withPrincipals(new Principal(String.format(S3_CLOUDFRONT_PRINCIPAL_TEMPLATE, id)))
      .withResources(new S3ObjectResource(bucketName, "*"));
  }

  @NotNull
  private String createOriginAccessIdentity(@NotNull AmazonCloudFront cloudFrontClient, @NotNull String comment) {
    CloudFrontOriginAccessIdentityConfig config = new CloudFrontOriginAccessIdentityConfig().withCallerReference(comment).withComment(comment);

    CreateCloudFrontOriginAccessIdentityRequest request = new CreateCloudFrontOriginAccessIdentityRequest()
      .withCloudFrontOriginAccessIdentityConfig(config);
    return cloudFrontClient.createCloudFrontOriginAccessIdentity(request)
                           .getCloudFrontOriginAccessIdentity()
                           .getId();
  }

  @XmlRootElement(name = "distribution")
  static class DistributionDTO {
    private final String id;
    private final String description;
    private final String publicKeyId;
    private final String publicKeyName;
    private final String privateKey;

    @Used("xml-serialization")
    public DistributionDTO(@NotNull String id, @NotNull String description, @NotNull String publicKeyId, @NotNull String publicKeyName, @NotNull String privateKey) {
      this.id = id;
      this.description = description;
      this.publicKeyId = publicKeyId;
      this.publicKeyName = publicKeyName;
      this.privateKey = privateKey;
    }

    public String getId() {
      return id;
    }

    public String getDescription() {
      return description;
    }

    public String getPublicKeyId() {
      return publicKeyId;
    }

    public String getPublicKeyName() {
      return publicKeyName;
    }

    public String getPrivateKey() {
      return privateKey;
    }
  }
}