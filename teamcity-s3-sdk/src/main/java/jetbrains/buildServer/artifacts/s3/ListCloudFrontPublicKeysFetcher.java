package jetbrains.buildServer.artifacts.s3;

import com.amazonaws.services.cloudfront.model.*;

import java.util.*;
import java.util.stream.Collectors;
import javax.xml.bind.annotation.*;

import jetbrains.buildServer.Used;
import org.jetbrains.annotations.NotNull;

public class ListCloudFrontPublicKeysFetcher extends S3ClientResourceFetcher<ListCloudFrontPublicKeysFetcher.ListPublicKeysDto> {

  @Override
  protected ListPublicKeysDto fetchDto(Map<String, String> parameters) throws Exception {
    return S3Util.withCloudFrontClient(parameters, client -> {
      ListPublicKeysResult result = client.listPublicKeys(new ListPublicKeysRequest());
      List<PublicKeyDto> keys = result.getPublicKeyList().getItems().stream()
        .map(key -> new PublicKeyDto(key.getId(), key.getName()))
        .collect(Collectors.toList());
      return new ListPublicKeysDto(keys);
    });
  }

  @XmlRootElement(name = "publicKeys")
  @XmlAccessorType(XmlAccessType.PROPERTY)
  public static class ListPublicKeysDto implements S3Dto {
    @NotNull
    private final List<PublicKeyDto> publicKeys;

    @Used("xml-serialization")
    public ListPublicKeysDto() {
      this.publicKeys = null;
    }

    public ListPublicKeysDto(@NotNull final List<PublicKeyDto> publicKeys) {
      this.publicKeys = publicKeys;
    }

    @XmlElement(name = "publicKey")
    @XmlList
    @NotNull
    public List<PublicKeyDto> getPublicKeys() {
      return publicKeys;
    }
  }

  @XmlRootElement(name = "publicKey")
  public static class PublicKeyDto {
    @NotNull
    private final String id;
    @NotNull
    private final String name;

    @Used("xml-serialization")
    public PublicKeyDto(@NotNull String id, @NotNull String name) {
      this.id = id;
      this.name = name;
    }

    @NotNull
    public String getId() {
      return id;
    }

    @NotNull
    public String getName() {
      return name;
    }
  }
}
