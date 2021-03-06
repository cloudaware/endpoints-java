/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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
 */
package com.google.api.server.spi.swagger;

import com.google.api.server.spi.Constant;
import com.google.api.server.spi.EndpointMethod;
import com.google.api.server.spi.Strings;
import com.google.api.server.spi.TypeLoader;
import com.google.api.server.spi.config.ApiConfigException;
import com.google.api.server.spi.config.annotationreader.ApiAnnotationIntrospector;
import com.google.api.server.spi.config.model.ApiConfig;
import com.google.api.server.spi.config.model.ApiIssuerAudienceConfig;
import com.google.api.server.spi.config.model.ApiIssuerConfigs;
import com.google.api.server.spi.config.model.ApiIssuerConfigs.IssuerConfig;
import com.google.api.server.spi.config.model.ApiKey;
import com.google.api.server.spi.config.model.ApiMethodConfig;
import com.google.api.server.spi.config.model.ApiParameterConfig;
import com.google.api.server.spi.config.model.FieldType;
import com.google.api.server.spi.config.model.Schema;
import com.google.api.server.spi.config.model.Schema.Field;
import com.google.api.server.spi.config.model.SchemaRepository;
import com.google.api.server.spi.config.validation.ApiConfigValidator;
import com.google.api.server.spi.types.DateAndTime;
import com.google.api.server.spi.types.SimpleDate;
import com.google.common.base.CaseFormat;
import com.google.common.base.Converter;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;

import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.swagger.models.Info;
import io.swagger.models.Model;
import io.swagger.models.ModelImpl;
import io.swagger.models.Operation;
import io.swagger.models.Path;
import io.swagger.models.RefModel;
import io.swagger.models.Response;
import io.swagger.models.Scheme;
import io.swagger.models.Swagger;
import io.swagger.models.auth.ApiKeyAuthDefinition;
import io.swagger.models.auth.In;
import io.swagger.models.auth.OAuth2Definition;
import io.swagger.models.auth.SecuritySchemeDefinition;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.PathParameter;
import io.swagger.models.parameters.QueryParameter;
import io.swagger.models.parameters.SerializableParameter;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.BooleanProperty;
import io.swagger.models.properties.ByteArrayProperty;
import io.swagger.models.properties.DateProperty;
import io.swagger.models.properties.DateTimeProperty;
import io.swagger.models.properties.DoubleProperty;
import io.swagger.models.properties.FloatProperty;
import io.swagger.models.properties.IntegerProperty;
import io.swagger.models.properties.LongProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.models.properties.StringProperty;

/**
 * Generates a {@link Swagger} object representing a set of {@link ApiConfig} objects.
 */
public class SwaggerGenerator {
  private static final String API_KEY = "api_key";
  private static final String API_KEY_PARAM = "key";
  private static final Converter<String, String> CONVERTER =
      CaseFormat.LOWER_CAMEL.converterTo(CaseFormat.UPPER_CAMEL);
  private static final ImmutableMap<Type, String> TYPE_TO_STRING_MAP =
      ImmutableMap.<java.lang.reflect.Type, String>builder()
          .put(String.class, "string")
          .put(Boolean.class, "boolean")
          .put(Boolean.TYPE, "boolean")
          .put(Integer.class, "integer")
          .put(Integer.TYPE, "integer")
          .put(Long.class, "integer")
          .put(Long.TYPE, "integer")
          .put(Float.class, "number")
          .put(Float.TYPE, "number")
          .put(Double.class, "number")
          .put(Double.TYPE, "number")
          .put(byte[].class, "string")
          .put(SimpleDate.class, "string")
          .put(DateAndTime.class, "string")
          .put(Date.class, "string")
          .build();
  private static final ImmutableMap<Type, String> TYPE_TO_FORMAT_MAP =
      ImmutableMap.<java.lang.reflect.Type, String>builder()
          .put(Integer.class, "int32")
          .put(Integer.TYPE, "int32")
          .put(Long.class, "int64")
          .put(Long.TYPE, "int64")
          .put(Float.class, "float")
          .put(Float.TYPE, "float")
          .put(Double.class, "double")
          .put(Double.TYPE, "double")
          .put(byte[].class, "byte")
          .put(SimpleDate.class, "date")
          .put(DateAndTime.class, "date-time")
          .put(Date.class, "date-time")
          .build();
  private static final ImmutableMap<FieldType, Property> FIELD_TYPE_TO_PROPERTY_MAP =
      ImmutableMap.<FieldType, Property>builder()
          .put(FieldType.BOOLEAN, new BooleanProperty())
          .put(FieldType.BYTE_STRING, new ByteArrayProperty())
          .put(FieldType.DATE, new DateProperty())
          .put(FieldType.DATE_TIME, new DateTimeProperty())
          .put(FieldType.DOUBLE, new DoubleProperty())
          .put(FieldType.FLOAT, new FloatProperty())
          .put(FieldType.INT8, new IntegerProperty())
          .put(FieldType.INT16, new IntegerProperty())
          .put(FieldType.INT32, new IntegerProperty())
          .put(FieldType.INT64, new LongProperty())
          .put(FieldType.STRING, new StringProperty())
          .build();

  private static final Function<ApiConfig, ApiKey> CONFIG_TO_ROOTLESS_KEY =
      new Function<ApiConfig, ApiKey>() {
        @Override
        public ApiKey apply(ApiConfig config) {
          return new ApiKey(config.getName(), config.getVersion(), null /* root */);
        }
      };

  public Swagger writeSwagger(Iterable<ApiConfig> configs, boolean writeInternal,
      SwaggerContext context) throws ApiConfigException {
    try {
      TypeLoader typeLoader = new TypeLoader(SwaggerGenerator.class.getClassLoader());
      SchemaRepository repo = new SchemaRepository(typeLoader);
      ApiConfigValidator validator = new ApiConfigValidator(typeLoader, repo);
      return writeSwagger(configs, writeInternal, context, repo, validator);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  public Swagger writeSwagger(Iterable<ApiConfig> configs, boolean writeInternal,
      SwaggerContext context, SchemaRepository repo, ApiConfigValidator validator)
      throws ApiConfigException {
    ImmutableListMultimap<ApiKey, ? extends ApiConfig> configsByKey = FluentIterable.from(configs)
        .index(CONFIG_TO_ROOTLESS_KEY);
    Swagger swagger = new Swagger()
        .produces("application/json")
        .consumes("application/json")
        .scheme(context.scheme)
        .host(context.hostname)
        .basePath(context.basePath)
        .info(new Info()
            .title(context.hostname)
            .version(context.docVersion));
    for (ApiKey apiKey : configsByKey.keySet()) {
      writeApi(apiKey, configsByKey.get(apiKey), swagger, context, writeInternal, repo, validator);
    }
    return swagger;
  }

  private void writeApi(ApiKey apiKey, ImmutableList<? extends ApiConfig> apiConfigs,
      Swagger swagger, SwaggerContext context, boolean writeInternal, SchemaRepository repo,
      ApiConfigValidator validator)
      throws ApiConfigException {
    // TODO: This may result in duplicate validations in the future if made available online
    validator.validate(apiConfigs);
    for (ApiConfig apiConfig : apiConfigs) {
      for (IssuerConfig issuerConfig : apiConfig.getIssuers().asMap().values()) {
        addNonConflictingSecurityDefinition(swagger, issuerConfig);
      }
      List<String> legacyAudiences = apiConfig.getApiClassConfig().getAudiences();
      if (legacyAudiences != null && !legacyAudiences.isEmpty()) {
        addNonConflictingSecurityDefinition(swagger, ApiIssuerConfigs.GOOGLE_ID_TOKEN_ISSUER);
        addNonConflictingSecurityDefinition(swagger, ApiIssuerConfigs.GOOGLE_ID_TOKEN_ISSUER_ALT);
      }
      writeApiClass(apiConfig, swagger, context, writeInternal, repo);
    }
    List<Schema> schemas = repo.getAllSchemaForApi(apiKey);
    if (!schemas.isEmpty()) {
      for (Schema schema : schemas) {
        swagger.addDefinition(schema.name(), convertToSwaggerSchema(schema));
      }
    }
  }

  private void writeApiClass(ApiConfig apiConfig, Swagger swagger, SwaggerContext context,
      boolean writeInternal, SchemaRepository repo) throws ApiConfigException {
    Map<EndpointMethod, ApiMethodConfig> methodConfigs = apiConfig.getApiClassConfig().getMethods();
    for (Map.Entry<EndpointMethod, ApiMethodConfig> methodConfig : methodConfigs.entrySet()) {
      if (!methodConfig.getValue().isIgnored()) {
        EndpointMethod endpointMethod = methodConfig.getKey();
        ApiMethodConfig config = methodConfig.getValue();
        writeApiMethod(
            config, endpointMethod, apiConfig, swagger, context, writeInternal, repo);
      }
    }
  }

  private void writeApiMethod(ApiMethodConfig methodConfig, EndpointMethod endpointMethod,
      ApiConfig apiConfig, Swagger swagger, SwaggerContext context, boolean writeInternal,
      SchemaRepository repo) throws ApiConfigException {
    Path path = getOrCreatePath(swagger, methodConfig);
    Operation operation = new Operation();
    operation.setOperationId(getOperationId(apiConfig, methodConfig));
    operation.setDescription(methodConfig.getDescription());
    Collection<String> pathParameters = methodConfig.getPathParameters();
    for (ApiParameterConfig parameterConfig : methodConfig.getParameterConfigs()) {
      switch (parameterConfig.getClassification()) {
        case API_PARAMETER:
          boolean isPathParameter = pathParameters.contains(parameterConfig.getName());
          SerializableParameter parameter =
              isPathParameter ? new PathParameter() : new QueryParameter();
          parameter.setName(parameterConfig.getName());
          parameter.setDescription(parameterConfig.getDescription());
          boolean required = isPathParameter || (!parameterConfig.getNullable()
              && parameterConfig.getDefaultValue() == null);
          if (parameterConfig.isRepeated()) {
            TypeToken<?> t = parameterConfig.getRepeatedItemSerializedType();
            parameter.setType("array");
            Property p = getSwaggerArrayProperty(t);
            if (parameterConfig.isEnum()) {  // TODO: Not sure if this is the right check
              ((StringProperty) p).setEnum(getEnumValues(t));
            }
            parameter.setItems(p);
          } else if (parameterConfig.isEnum()) {
            parameter.setType("string");
            parameter.setEnum(getEnumValues(parameterConfig.getType()));
            parameter.setRequired(required);
          } else {
            parameter.setType(
                TYPE_TO_STRING_MAP.get(parameterConfig.getSchemaBaseType().getType()));
            parameter.setFormat(
                TYPE_TO_FORMAT_MAP.get(parameterConfig.getSchemaBaseType().getType()));
            parameter.setRequired(required);
          }
          operation.parameter(parameter);
          break;
        case RESOURCE:
          TypeToken<?> requestType = parameterConfig.getSchemaBaseType();
          Schema schema = repo.getOrAdd(requestType, apiConfig);
          BodyParameter bodyParameter = new BodyParameter();
          bodyParameter.setName("body");
          bodyParameter.setSchema(new RefModel(schema.name()));
          operation.addParameter(bodyParameter);
          break;
        case UNKNOWN:
          throw new IllegalArgumentException("Unclassifiable parameter type found.");
        case INJECTED:
          break;  // Do nothing, these are synthetic and created by the framework.
      }
    }
    Response response = new Response().description("A successful response");
    if (methodConfig.hasResourceInResponse()) {
      TypeToken<?> returnType =
          ApiAnnotationIntrospector.getSchemaType(methodConfig.getReturnType(), apiConfig);
      Schema schema = repo.getOrAdd(returnType, apiConfig);
      response.setSchema(new RefProperty(schema.name()));
    }
    operation.response(200, response);
    writeAudiences(swagger, methodConfig, writeInternal, operation);
    if (methodConfig.isApiKeyRequired()) {
      operation.addSecurity(API_KEY, ImmutableList.<String>of());
      Map<String, SecuritySchemeDefinition> definitions = swagger.getSecurityDefinitions();
      if (definitions == null || !definitions.containsKey(API_KEY)) {
        swagger.securityDefinition(API_KEY, new ApiKeyAuthDefinition(API_KEY_PARAM, In.QUERY));
      }
    }
    path.set(methodConfig.getHttpMethod().toLowerCase(), operation);
  }

  private void writeAudiences(Swagger swagger, ApiMethodConfig methodConfig, boolean writeInternal,
      Operation operation) throws ApiConfigException {
    ApiIssuerAudienceConfig issuerAudiences = methodConfig.getIssuerAudiences();
    boolean issuerAudiencesIsEmpty = !issuerAudiences.isSpecified() || issuerAudiences.isEmpty();
    List<String> legacyAudiences = methodConfig.getAudiences();
    boolean legacyAudiencesIsEmpty = legacyAudiences == null || legacyAudiences.isEmpty();
    if (issuerAudiencesIsEmpty && legacyAudiencesIsEmpty) {
      return;
    }
    ImmutableMap<String, Collection<String>> audiences = issuerAudiences.asMap();
    // For reversability purposes, we can't use helper data structures here. When Swagger reads
    // the document back in, it uses primitive data structures.
    ImmutableList.Builder<ImmutableMap<String, ImmutableMap<String, List<String>>>> xSecurity =
        ImmutableList.builder();
    if (!issuerAudiencesIsEmpty) {
      for (Map.Entry<String, Collection<String>> entry : audiences.entrySet()) {
        operation.addSecurity(entry.getKey(), ImmutableList.<String>of());
        if (writeInternal) {
          xSecurity.add(ImmutableMap.of(entry.getKey(), createAudiences(entry.getValue())));
        }
      }
    }
    if (!legacyAudiencesIsEmpty) {
      addNonConflictingSecurityDefinition(swagger, ApiIssuerConfigs.GOOGLE_ID_TOKEN_ISSUER);
      addNonConflictingSecurityDefinition(swagger, ApiIssuerConfigs.GOOGLE_ID_TOKEN_ISSUER_ALT);
      operation.addSecurity(Constant.GOOGLE_ID_TOKEN_NAME, ImmutableList.<String>of());
      operation.addSecurity(Constant.GOOGLE_ID_TOKEN_NAME_HTTPS, ImmutableList.<String>of());
      if (writeInternal) {
        ImmutableMap<String, List<String>> legacySwaggerAudiences =
            createAudiences(legacyAudiences);
        xSecurity.add(
            ImmutableMap.of(Constant.GOOGLE_ID_TOKEN_NAME, legacySwaggerAudiences));
        xSecurity.add(
            ImmutableMap.of(Constant.GOOGLE_ID_TOKEN_NAME_HTTPS, legacySwaggerAudiences));
      }
    }
    if (writeInternal) {
      operation.setVendorExtension("x-security", xSecurity.build());
    }
  }

  private Model convertToSwaggerSchema(Schema schema) {
    ModelImpl docSchema = new ModelImpl();
    Map<String, Property> fields = Maps.newLinkedHashMap();
    if (!schema.fields().isEmpty()) {
      for (Field f : schema.fields().values()) {
        fields.put(f.name(), convertToSwaggerProperty(f));
      }
      docSchema.setProperties(fields);
    }
    if (!schema.enumValues().isEmpty()) {
      docSchema._enum(schema.enumValues());
    }
    return docSchema;
  }

  private Property convertToSwaggerProperty(Field f) {
    Property p = FIELD_TYPE_TO_PROPERTY_MAP.get(f.type());
    if (p != null) {
      return p;
    } else if (f.type() == FieldType.OBJECT || f.type() == FieldType.ENUM) {
      return new RefProperty(f.schemaReference().get().name());
    } else if (f.type() == FieldType.ARRAY) {
      return new ArrayProperty(convertToSwaggerProperty(f.arrayItemSchema()));
    }
    throw new IllegalArgumentException("could not convert field " + f);
  }

  private static String getOperationId(ApiConfig apiConfig, ApiMethodConfig methodConfig) {
    return CONVERTER.convert(apiConfig.getName()) +
        CONVERTER.convert(methodConfig.getEndpointMethodName());
  }

  private static Property getSwaggerArrayProperty(TypeToken<?> typeToken) {
    Class<?> type = typeToken.getRawType();
    if (type == String.class) {
      return new StringProperty();
    } else if (type == Boolean.class || type == Boolean.TYPE) {
      return new BooleanProperty();
    } else if (type == Integer.class || type == Integer.TYPE) {
      return new IntegerProperty();
    } else if (type == Long.class || type == Long.TYPE) {
      return new LongProperty();
    } else if (type == Float.class || type == Float.TYPE) {
      return new FloatProperty();
    } else if (type == Double.class || type == Double.TYPE) {
      return new DoubleProperty();
    } else if (type == byte[].class) {
      return new ByteArrayProperty();
    } else if (type.isEnum()) {
      return new StringProperty();
    }
    throw new IllegalArgumentException("invalid property type");
  }

  private Path getOrCreatePath(Swagger swagger, ApiMethodConfig methodConfig) {
    String pathStr = "/" + methodConfig.getCanonicalPath();
    Path path = swagger.getPath(pathStr);
    if (path == null) {
      path = new Path();
      swagger.path(pathStr, path);
    }
    return path;
  }

  private static List<String> getEnumValues(TypeToken<?> t) {
    List<String> values = Lists.newArrayList();
    for (Object value : t.getRawType().getEnumConstants()) {
      values.add(value.toString());
    }
    return values;
  }

  public static class SwaggerContext {
    private Scheme scheme = Scheme.HTTPS;
    private String hostname = "myapi.appspot.com";
    private String basePath = "/_ah/api";
    private String docVersion = "1.0.0";

    public SwaggerContext setApiRoot(String apiRoot) {
      try {
        URL url = new URL(apiRoot);
        hostname = url.getHost();
        if (("http".equals(url.getProtocol()) && url.getPort() != 80 && url.getPort() != -1)
            || ("https".equals(url.getProtocol()) && url.getPort() != 443 && url.getPort() != -1)) {
          hostname += ":" + url.getPort();
        }
        basePath = Strings.stripTrailingSlash(url.getPath());
        setScheme(url.getProtocol());
        return this;
      } catch (MalformedURLException e) {
        throw new IllegalArgumentException(e);
      }
    }

    public SwaggerContext setScheme(String scheme) {
      this.scheme = "http".equals(scheme) ? Scheme.HTTP : Scheme.HTTPS;
      return this;
    }

    public SwaggerContext setHostname(String hostname) {
      this.hostname = hostname;
      return this;
    }

    public SwaggerContext setBasePath(String basePath) {
      this.basePath = basePath;
      return this;
    }

    public SwaggerContext setDocVersion(String docVersion) {
      this.docVersion = docVersion;
      return this;
    }
  }

  private static ImmutableMap<String, List<String>> createAudiences(Iterable<String> audiences) {
    return ImmutableMap.<String, List<String>>of("audiences", ImmutableList.copyOf(audiences));
  }

  private static SecuritySchemeDefinition toScheme(IssuerConfig issuerConfig) {
    OAuth2Definition tokenDef = new OAuth2Definition().implicit("");
    tokenDef.setVendorExtension("x-google-issuer", issuerConfig.getIssuer());
    if (!com.google.common.base.Strings.isNullOrEmpty(issuerConfig.getJwksUri())) {
      tokenDef.setVendorExtension("x-google-jwks_uri", issuerConfig.getJwksUri());
    }
    return tokenDef;
  }

  private static Map<String, SecuritySchemeDefinition> getOrCreateSecurityDefinitionMap(
      Swagger swagger) {
    Map<String, SecuritySchemeDefinition> securityDefinitions = swagger.getSecurityDefinitions();
    if (securityDefinitions == null) {
      securityDefinitions = new HashMap<>();
      swagger.setSecurityDefinitions(securityDefinitions);
    }
    return securityDefinitions;
  }

  private static void addNonConflictingSecurityDefinition(
      Swagger swagger, IssuerConfig issuerConfig) throws ApiConfigException {
    Map<String, SecuritySchemeDefinition> securityDefinitions =
        getOrCreateSecurityDefinitionMap(swagger);
    SecuritySchemeDefinition existingDef = securityDefinitions.get(issuerConfig.getName());
    SecuritySchemeDefinition newDef = toScheme(issuerConfig);
    if (existingDef != null && !existingDef.equals(newDef)) {
      throw new ApiConfigException(
          "Multiple conflicting definitions found for issuer " + issuerConfig.getName());
    }
    swagger.securityDefinition(issuerConfig.getName(), newDef);
  }
}
