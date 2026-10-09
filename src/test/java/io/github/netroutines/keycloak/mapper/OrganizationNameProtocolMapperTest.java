package io.github.netroutines.keycloak.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.organization.protocol.mappers.oidc.OrganizationGroupMembershipMapper;
import org.keycloak.organization.protocol.mappers.oidc.OrganizationMembershipMapper;
import org.keycloak.protocol.ProtocolMapper;
import org.keycloak.protocol.ProtocolMapperUtils;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.IDToken;
import org.keycloak.util.JsonSerialization;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OrganizationNameProtocolMapperTest {

    private static final String ORGANIZATION_ALIAS = "acme";
    private static final String ORGANIZATION_NAME = "ACME Corporation";
    private static final String ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

    private final OrganizationNameProtocolMapper mapper = new OrganizationNameProtocolMapper();

    @Test
    void addsOrganizationNameAndPreservesExistingAttributes() {
        Map<String, Object> claims = new HashMap<>(attributes());
        IDToken token = tokenWithClaim(Map.of(ORGANIZATION_ALIAS, claims));
        Object unrelatedClaim = Map.of("keep", List.of("unchanged"));
        token.getOtherClaims().put("unrelated", unrelatedClaim);

        mapper.setClaim(token, new ProtocolMapperModel(), null,
                fixture(enabledOrganization(ORGANIZATION_NAME)).session(), null);

        assertEquals(ORGANIZATION_NAME, organizationClaims(token).get("name"));
        assertEquals(ORGANIZATION_ID, organizationClaims(token).get("id"));
        assertEquals(List.of("12345"), organizationClaims(token).get("zip"));
        assertEquals(List.of("association"), organizationClaims(token).get("type"));
        assertEquals(Map.of("keep", "value"), organizationClaims(token).get("metadata"));
        assertSame(unrelatedClaim, token.getOtherClaims().get("unrelated"));
        assertEquals(attributes(), claims);
        assertEquals(Set.of(ORGANIZATION_ALIAS), organizationEntries(token).keySet());
    }

    @Test
    void enrichesObjectNodeAndPreservesItsSerializedAttributes() throws Exception {
        ObjectNode original = objectClaim(Map.of(ORGANIZATION_ALIAS, attributes()));
        ObjectNode snapshot = original.deepCopy();
        IDToken token = tokenWithClaim(original);

        mapper.setClaim(token, new ProtocolMapperModel(), null,
                fixture(enabledOrganization(ORGANIZATION_NAME)).session(), null);

        JsonNode serialized = JsonSerialization.mapper.readTree(JsonSerialization.writeValueAsString(token));
        JsonNode result = serialized.path("organization").path(ORGANIZATION_ALIAS);
        assertEquals(ORGANIZATION_NAME, result.path("name").asText());
        assertEquals(ORGANIZATION_ID, result.path("id").asText());
        assertEquals(snapshot.path(ORGANIZATION_ALIAS).path("zip"), result.path("zip"));
        assertEquals(snapshot.path(ORGANIZATION_ALIAS).path("type"), result.path("type"));
        assertEquals(snapshot.path(ORGANIZATION_ALIAS).path("metadata"), result.path("metadata"));
        assertEquals(snapshot, original);
        assertEquals(Set.of(ORGANIZATION_ALIAS), organizationEntries(token).keySet());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("immutableClaims")
    void supportsImmutableOuterAndNestedMaps(String description, Map<String, Object> organizations) {
        IDToken token = tokenWithClaim(organizations);
        Object originalNested = organizations.get(ORGANIZATION_ALIAS);

        mapper.setClaim(token, new ProtocolMapperModel(), null,
                fixture(enabledOrganization(ORGANIZATION_NAME)).session(), null);

        assertEquals(ORGANIZATION_NAME, organizationClaims(token).get("name"));
        assertEquals(ORGANIZATION_ID, organizationClaims(token).get("id"));
        assertEquals(List.of("association"), organizationClaims(token).get("type"));
        assertEquals(attributes(), originalNested);
        assertNotSame(organizations, organizationEntries(token));
        assertNotSame(originalNested, organizationClaims(token));
        assertEquals(organizations.keySet(), organizationEntries(token).keySet());
    }

    static Stream<Arguments> immutableClaims() {
        return Stream.of(
                Arguments.of("immutable outer", Map.of(ORGANIZATION_ALIAS, new HashMap<>(attributes()))),
                Arguments.of("immutable nested", new HashMap<>(Map.of(ORGANIZATION_ALIAS, attributes()))),
                Arguments.of("both immutable", Map.of(ORGANIZATION_ALIAS, attributes())),
                Arguments.of("unmodifiable wrappers", Collections.unmodifiableMap(
                        Map.of(ORGANIZATION_ALIAS, Collections.unmodifiableMap(new HashMap<>(attributes())))))
        );
    }

    @ParameterizedTest
    @MethodSource("existingNames")
    void organizationDisplayNameOverridesAnyExistingName(Object existingName) {
        Map<String, Object> claims = new HashMap<>(attributes());
        claims.put("name", existingName);
        IDToken token = tokenWithClaim(Map.of(ORGANIZATION_ALIAS, claims));

        mapper.setClaim(token, new ProtocolMapperModel(), null,
                fixture(enabledOrganization(ORGANIZATION_NAME)).session(), null);

        assertEquals(ORGANIZATION_NAME, organizationClaims(token).get("name"));
        assertEquals(ORGANIZATION_ID, organizationClaims(token).get("id"));
        assertSame(existingName, claims.get("name"));
    }

    static Stream<Arguments> existingNames() {
        return Stream.of(
                Arguments.of("Custom attribute value"),
                Arguments.of(List.of("Custom", "attribute")),
                Arguments.of(Map.of("custom", "value")),
                Arguments.of((Object) null)
        );
    }

    @Test
    void doesNothingWhenOrganizationClaimIsMissing() {
        IDToken token = new IDToken();
        KeycloakSession session = mock(KeycloakSession.class);

        mapper.setClaim(token, new ProtocolMapperModel(), null, session, null);

        assertFalse(token.getOtherClaims().containsKey("organization"));
        verifyNoInteractions(session);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedClaims")
    void leavesUnsupportedClaimShapesUntouched(String description, Object existingClaim) {
        IDToken token = tokenWithClaim(existingClaim);
        KeycloakSession session = mock(KeycloakSession.class);

        mapper.setClaim(token, new ProtocolMapperModel(), null, session, null);

        assertSame(existingClaim, token.getOtherClaims().get("organization"));
        assertEquals(Set.of("organization"), token.getOtherClaims().keySet());
        verifyNoInteractions(session);
    }

    static Stream<Arguments> unsupportedClaims() {
        return Stream.of(
                Arguments.of("String", ORGANIZATION_ALIAS),
                Arguments.of("List", List.of(ORGANIZATION_ALIAS)),
                Arguments.of("Set", Set.of(ORGANIZATION_ALIAS)),
                Arguments.of("Number", 42),
                Arguments.of("Boolean", true),
                Arguments.of("Java array", new String[]{ORGANIZATION_ALIAS}),
                Arguments.of("Other object", new Object()),
                Arguments.of("JSON array", JsonSerialization.mapper.createArrayNode().add(ORGANIZATION_ALIAS)),
                Arguments.of("JSON string", JsonSerialization.mapper.getNodeFactory().textNode(ORGANIZATION_ALIAS)),
                Arguments.of("JSON number", JsonSerialization.mapper.getNodeFactory().numberNode(42)),
                Arguments.of("JSON boolean", JsonSerialization.mapper.getNodeFactory().booleanNode(true)),
                Arguments.of("JSON null", JsonSerialization.mapper.getNodeFactory().nullNode()),
                Arguments.of("Explicit null", (Object) null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyClaims")
    void leavesEmptyStructuredClaimsUntouched(String description, Object existingClaim) {
        IDToken token = tokenWithClaim(existingClaim);
        KeycloakSession session = mock(KeycloakSession.class);

        mapper.setClaim(token, new ProtocolMapperModel(), null, session, null);

        assertSame(existingClaim, token.getOtherClaims().get("organization"));
        verifyNoInteractions(session);
    }

    static Stream<Arguments> emptyClaims() {
        return Stream.of(
                Arguments.of("empty Map", Map.of()),
                Arguments.of("empty ObjectNode", JsonSerialization.mapper.createObjectNode())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuredClaims")
    void leavesClaimUntouchedWhenOrganizationProviderIsMissing(String description, Object existingClaim) {
        IDToken token = tokenWithClaim(existingClaim);
        KeycloakSession session = mock(KeycloakSession.class);

        mapper.setClaim(token, new ProtocolMapperModel(), null, session, null);

        assertSame(existingClaim, token.getOtherClaims().get("organization"));
        verify(session).getProvider(OrganizationProvider.class);
        verifyNoMoreInteractions(session);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuredClaims")
    void leavesClaimUntouchedWhenOrganizationIsMissing(String description, Object existingClaim) {
        IDToken token = tokenWithClaim(existingClaim);
        SessionFixture fixture = fixture(Map.of());

        mapper.setClaim(token, new ProtocolMapperModel(), null, fixture.session(), null);

        assertSame(existingClaim, token.getOtherClaims().get("organization"));
        verify(fixture.provider()).getByAlias(ORGANIZATION_ALIAS);
        verifyNoMoreInteractions(fixture.provider());
    }

    static Stream<Arguments> structuredClaims() {
        return Stream.of(
                Arguments.of("Map", Map.of(ORGANIZATION_ALIAS, attributes())),
                Arguments.of("ObjectNode", objectClaim(Map.of(ORGANIZATION_ALIAS, attributes())))
        );
    }

    @Test
    void preservesExistingValuesForDisabledOrganization() {
        Map<String, Object> claims = Map.of("id", ORGANIZATION_ID, "name", "Existing name");
        Map<String, Object> organizations = Map.of(ORGANIZATION_ALIAS, claims);
        IDToken token = tokenWithClaim(organizations);
        OrganizationModel disabled = mock(OrganizationModel.class);

        mapper.setClaim(token, new ProtocolMapperModel(), null, fixture(disabled).session(), null);

        assertSame(organizations, token.getOtherClaims().get("organization"));
        assertEquals("Existing name", organizationClaims(token).get("name"));
        verify(disabled).isEnabled();
        verify(disabled, never()).getName();
    }

    @Test
    void continuesPastMissingAndDisabledOrganizationsWithoutChangingAliases() {
        Map<String, Object> organizations = new LinkedHashMap<>();
        organizations.put("missing", Map.of("name", "Retained missing value"));
        organizations.put("disabled", Map.of("name", "Retained disabled value"));
        organizations.put(ORGANIZATION_ALIAS, attributes());
        organizations.put("beta", Map.of("id", "beta-id"));
        IDToken token = tokenWithClaim(Collections.unmodifiableMap(organizations));
        OrganizationModel disabled = mock(OrganizationModel.class);
        SessionFixture fixture = fixture(Map.of(
                "disabled", disabled,
                ORGANIZATION_ALIAS, enabledOrganization(ORGANIZATION_NAME),
                "beta", enabledOrganization("Beta Corporation")));

        mapper.setClaim(token, new ProtocolMapperModel(), null, fixture.session(), null);

        Map<String, Object> result = organizationEntries(token);
        assertEquals(organizations.keySet(), result.keySet());
        assertSame(organizations.get("missing"), result.get("missing"));
        assertSame(organizations.get("disabled"), result.get("disabled"));
        assertEquals(ORGANIZATION_NAME, organizationClaims(token).get("name"));
        assertEquals(Map.of("id", "beta-id", "name", "Beta Corporation"), result.get("beta"));
        verify(fixture.provider()).getByAlias("missing");
        verify(fixture.provider()).getByAlias("disabled");
        verify(fixture.provider()).getByAlias(ORGANIZATION_ALIAS);
        verify(fixture.provider()).getByAlias("beta");
        verifyNoMoreInteractions(fixture.provider());
    }

    @Test
    void preservesMalformedEntriesWhileEnrichingSupportedEntries() {
        Map<Object, Object> organizations = new LinkedHashMap<>();
        organizations.put(ORGANIZATION_ALIAS, attributes());
        organizations.put("string", "legacy");
        organizations.put("list", List.of("legacy"));
        organizations.put("node", JsonSerialization.mapper.createObjectNode());
        organizations.put("null", null);
        organizations.put(null, attributes());
        organizations.put(42, attributes());
        IDToken token = tokenWithClaim(organizations);
        SessionFixture fixture = fixture(enabledOrganization(ORGANIZATION_NAME));

        mapper.setClaim(token, new ProtocolMapperModel(), null, fixture.session(), null);

        Map<String, Object> result = organizationEntries(token);
        assertEquals(organizations.keySet(), result.keySet());
        for (Object alias : organizations.keySet()) {
            if (!ORGANIZATION_ALIAS.equals(alias)) {
                assertSame(organizations.get(alias), result.get(alias));
            }
        }
        assertEquals(ORGANIZATION_NAME, organizationClaims(token).get("name"));
        verify(fixture.provider()).getByAlias(ORGANIZATION_ALIAS);
        verifyNoMoreInteractions(fixture.provider());
    }

    @Test
    void trustsExistingAliasesWithoutEnumeratingOrRevalidatingMembership() {
        IDToken token = tokenWithClaim(Map.of(ORGANIZATION_ALIAS, attributes()));
        OrganizationModel organization = enabledOrganization(ORGANIZATION_NAME);
        SessionFixture fixture = fixture(organization);
        UserSessionModel userSession = mock(UserSessionModel.class);
        ClientSessionContext clientSession = mock(ClientSessionContext.class);

        // No membership or scope stubs: the existing claim is the trust boundary.
        mapper.setClaim(token, new ProtocolMapperModel(), userSession, fixture.session(), clientSession);

        assertEquals(Set.of(ORGANIZATION_ALIAS), organizationEntries(token).keySet());
        assertEquals(ORGANIZATION_NAME, organizationClaims(token).get("name"));
        verify(fixture.provider()).getByAlias(ORGANIZATION_ALIAS);
        verifyNoMoreInteractions(fixture.provider());
        verify(organization, never()).isMember(any());
        verifyNoInteractions(userSession, clientSession);
    }

    @ParameterizedTest(name = "jsonType={0}, multivalued={1}, ObjectNode={2}")
    @MethodSource("importedConfigurations")
    void importedSettingsCannotChangeClaimShapeOrLoseName(String jsonType, boolean multivalued,
                                                          boolean objectNode) throws Exception {
        Map<String, String> originalConfig = Map.of(
                OIDCAttributeMapperHelper.JSON_TYPE, jsonType,
                ProtocolMapperUtils.MULTIVALUED, Boolean.toString(multivalued),
                OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, "custom.organization",
                OIDCAttributeMapperHelper.INCLUDE_IN_ID_TOKEN, "true");
        ProtocolMapperModel model = modelWithConfig(originalConfig);
        model.setConfig(originalConfig);
        Map<String, Object> organizations = Map.of(ORGANIZATION_ALIAS, attributes());
        IDToken token = tokenWithClaim(objectNode ? objectClaim(organizations) : organizations);

        mapper.setClaim(token, model, null, fixture(enabledOrganization(ORGANIZATION_NAME)).session(), null);

        assertInstanceOf(Map.class, token.getOtherClaims().get("organization"));
        assertEquals(Set.of(ORGANIZATION_ALIAS), organizationEntries(token).keySet());
        assertEquals(ORGANIZATION_NAME, organizationClaims(token).get("name"));
        assertEquals(ORGANIZATION_ID, organizationClaims(token).get("id"));
        assertFalse(token.getOtherClaims().containsKey("custom"));
        assertSame(originalConfig, model.getConfig());
        JsonNode serialized = JsonSerialization.mapper.readTree(JsonSerialization.writeValueAsString(token));
        assertTrue(serialized.path("organization").isObject());
        assertEquals(ORGANIZATION_NAME,
                serialized.path("organization").path(ORGANIZATION_ALIAS).path("name").asText());
    }

    static Stream<Arguments> importedConfigurations() {
        return Stream.of("JSON", "String").flatMap(type ->
                Stream.of(false, true).flatMap(multivalued ->
                        Stream.of(false, true).map(objectNode -> Arguments.of(type, multivalued, objectNode))));
    }

    @Test
    void effectiveModelPreservesOriginalConfiguration() {
        Map<String, String> config = Map.of(
                OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, "custom.claim",
                OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN, "true",
                OIDCAttributeMapperHelper.INCLUDE_IN_USERINFO, "false");
        ProtocolMapperModel original = modelWithConfig(config);
        original.setConfig(config);

        ProtocolMapperModel effective = mapper.getEffectiveModel(null, null, original);

        assertNotSame(original, effective);
        assertNotSame(config, effective.getConfig());
        assertEquals("organization", effective.getConfig().get(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME));
        assertEquals("true", effective.getConfig().get(OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN));
        assertEquals("false", effective.getConfig().get(OIDCAttributeMapperHelper.INCLUDE_IN_USERINFO));
        assertSame(config, original.getConfig());
        assertEquals("custom.claim", original.getConfig().get(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME));
    }

    @Test
    void effectiveModelHandlesMissingConfiguration() {
        ProtocolMapperModel original = new ProtocolMapperModel();

        ProtocolMapperModel effective = mapper.getEffectiveModel(null, null, original);

        assertEquals(Map.of(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, "organization"), effective.getConfig());
        assertNull(original.getConfig());
    }

    @Test
    void keycloakSortsMapperAfterBuiltInOrganizationMappers() {
        OrganizationMembershipMapper membership = new OrganizationMembershipMapper();
        OrganizationGroupMembershipMapper groups = new OrganizationGroupMembershipMapper();
        ProtocolMapperModel membershipModel = modelWithConfig(Map.of());
        membershipModel.setProtocolMapper(membership.getId());
        ProtocolMapperModel groupModel = modelWithConfig(Map.of());
        groupModel.setProtocolMapper(groups.getId());
        ProtocolMapperModel nameModel = modelWithConfig(Map.of());
        KeycloakSession session = mock(KeycloakSession.class);
        KeycloakSessionFactory factory = mock(KeycloakSessionFactory.class);
        ClientSessionContext context = mock(ClientSessionContext.class);
        AuthenticatedClientSessionModel clientSession = mock(AuthenticatedClientSessionModel.class);
        ClientModel client = mock(ClientModel.class);
        when(session.getKeycloakSessionFactory()).thenReturn(factory);
        when(factory.getProviderFactory(ProtocolMapper.class, membership.getId())).thenReturn(membership);
        when(factory.getProviderFactory(ProtocolMapper.class, groups.getId())).thenReturn(groups);
        when(factory.getProviderFactory(ProtocolMapper.class, mapper.getId())).thenReturn(mapper);
        when(context.getProtocolMappersStream()).thenReturn(Stream.of(nameModel, groupModel, membershipModel));
        when(context.getClientSession()).thenReturn(clientSession);
        when(clientSession.getClient()).thenReturn(client);
        when(client.getProtocol()).thenReturn(OIDCLoginProtocol.LOGIN_PROTOCOL);
        Set<String> organizationIds = Set.of(membership.getId(), groups.getId(), mapper.getId());

        List<String> order = ProtocolMapperUtils.getSortedProtocolMappers(session, context)
                .map(entry -> entry.getValue().getId())
                .filter(organizationIds::contains)
                .toList();

        assertEquals(List.of(membership.getId(), groups.getId(), mapper.getId()), order);
        assertTrue(mapper.getPriority() > membership.getPriority());
        assertTrue(mapper.getPriority() > groups.getPriority());
    }

    @Test
    void serviceProviderDescriptorRegistersMapper() throws Exception {
        String resource = "META-INF/services/org.keycloak.protocol.ProtocolMapper";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            String descriptor = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(descriptor.lines().anyMatch(OrganizationNameProtocolMapper.class.getName()::equals));
        }
    }

    @Test
    void serviceLoaderDiscoversAndInstantiatesMapper() {
        ProtocolMapper discovered = ServiceLoader.load(ProtocolMapper.class, getClass().getClassLoader())
                .stream()
                .filter(provider -> provider.type().equals(OrganizationNameProtocolMapper.class))
                .map(ServiceLoader.Provider::get)
                .findFirst()
                .orElseThrow();

        assertInstanceOf(OrganizationNameProtocolMapper.class, discovered);
        assertEquals(OrganizationNameProtocolMapper.PROVIDER_ID, discovered.getId());
        assertEquals(OIDCLoginProtocol.LOGIN_PROTOCOL, discovered.getProtocol());
    }

    @ParameterizedTest
    @EnumSource(TokenTarget.class)
    void enrichesEnabledTokenTargets(TokenTarget target) {
        IDToken token = target.newToken();
        token.getOtherClaims().put("organization", Map.of(ORGANIZATION_ALIAS, attributes()));
        SessionFixture fixture = fixture(enabledOrganization(ORGANIZATION_NAME));

        IDToken result = target.transform(mapper, token, modelFor(target, true), fixture.session(), null);

        assertSame(token, result);
        assertEquals(ORGANIZATION_NAME, organizationClaims(result).get("name"));
        assertEquals(Set.of(ORGANIZATION_ALIAS), organizationEntries(result).keySet());
    }

    @ParameterizedTest
    @EnumSource(TokenTarget.class)
    void skipsDisabledTokenTargets(TokenTarget target) {
        IDToken token = target.newToken();
        Object original = Map.of(ORGANIZATION_ALIAS, attributes());
        token.getOtherClaims().put("organization", original);
        SessionFixture fixture = fixture(enabledOrganization(ORGANIZATION_NAME));

        target.transform(mapper, token, modelFor(target, false), fixture.session(), null);

        assertSame(original, token.getOtherClaims().get("organization"));
        verifyNoInteractions(fixture.provider());
    }

    @ParameterizedTest
    @EnumSource(TokenTarget.class)
    void enrichesActualBuiltInMembershipOutputOnEachTokenTarget(TokenTarget target) throws Exception {
        OrganizationModel organization = enabledOrganization(ORGANIZATION_NAME);
        SessionFixture fixture = fixture(organization);
        UserModel user = mock(UserModel.class);
        UserSessionModel userSession = mock(UserSessionModel.class);
        when(userSession.getUser()).thenReturn(user);
        when(fixture.context().getOrganization()).thenReturn(organization);
        when(organization.isMember(user)).thenReturn(true);
        when(organization.getAlias()).thenReturn(ORGANIZATION_ALIAS);
        when(organization.getId()).thenReturn(ORGANIZATION_ID);
        when(organization.getAttributes()).thenReturn(Map.of(
                "zip", List.of("12345"), "name", List.of("Custom organization attribute")));
        ProtocolMapperModel membershipModel = modelFor(target, true);
        membershipModel.setProtocolMapper(OrganizationMembershipMapper.PROVIDER_ID);
        membershipModel.getConfig().put(ProtocolMapperUtils.MULTIVALUED, "true");
        membershipModel.getConfig().put(OrganizationMembershipMapper.ADD_ORGANIZATION_ID, "true");
        membershipModel.getConfig().put(OrganizationMembershipMapper.ADD_ORGANIZATION_ATTRIBUTES, "true");
        IDToken token = target.newToken();

        target.transform(new OrganizationMembershipMapper(), token, membershipModel, fixture.session(), userSession);
        ObjectNode membershipClaim = assertInstanceOf(ObjectNode.class, token.getOtherClaims().get("organization"));
        target.transform(mapper, token, modelFor(target, true), fixture.session(), userSession);

        JsonNode serialized = JsonSerialization.mapper.readTree(JsonSerialization.writeValueAsString(token));
        JsonNode result = serialized.path("organization").path(ORGANIZATION_ALIAS);
        assertEquals(ORGANIZATION_ID, result.path("id").asText());
        assertEquals(ORGANIZATION_NAME, result.path("name").asText());
        assertEquals(List.of("12345"), organizationClaims(token).get("zip"));
        assertEquals(Set.of(ORGANIZATION_ALIAS), organizationEntries(token).keySet());
        assertTrue(membershipClaim.path(ORGANIZATION_ALIAS).path("name").isArray());
        verify(organization).isMember(user); // Only the built-in mapper revalidates membership.
    }

    @ParameterizedTest
    @EnumSource(TokenTarget.class)
    void requiresMembershipClaimOnTheSameTokenTarget(TokenTarget target) {
        IDToken token = target.newToken();
        SessionFixture fixture = fixture(enabledOrganization(ORGANIZATION_NAME));
        ProtocolMapperModel membershipModel = modelFor(target, false);
        membershipModel.setProtocolMapper(OrganizationMembershipMapper.PROVIDER_ID);

        target.transform(new OrganizationMembershipMapper(), token, membershipModel, fixture.session(), null);
        target.transform(mapper, token, modelFor(target, true), fixture.session(), null);

        assertFalse(token.getOtherClaims().containsKey("organization"));
        verifyNoInteractions(fixture.provider());
    }

    @ParameterizedTest
    @CsvSource({
            "USER_INFO, id.token.claim, true",
            "USER_INFO, id.token.claim, false",
            "INTROSPECTION, access.token.claim, true",
            "INTROSPECTION, access.token.claim, false"
    })
    void preservesLegacyTokenTargetFallbacks(TokenTarget target, String fallbackKey, boolean included) {
        IDToken token = target.newToken();
        Object original = Map.of(ORGANIZATION_ALIAS, attributes());
        token.getOtherClaims().put("organization", original);
        SessionFixture fixture = fixture(enabledOrganization(ORGANIZATION_NAME));

        target.transform(mapper, token, modelWithConfig(Map.of(fallbackKey, Boolean.toString(included))),
                fixture.session(), null);

        if (included) {
            assertEquals(ORGANIZATION_NAME, organizationClaims(token).get("name"));
        } else {
            assertSame(original, token.getOtherClaims().get("organization"));
            verifyNoInteractions(fixture.provider());
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true", "false,false"})
    void lightweightTokensUseTheirOwnInclusionSetting(boolean regularIncluded, boolean lightweightIncluded) {
        AccessToken token = new AccessToken();
        Object original = Map.of(ORGANIZATION_ALIAS, attributes());
        token.getOtherClaims().put("organization", original);
        SessionFixture fixture = fixture(enabledOrganization(ORGANIZATION_NAME));
        when(fixture.client().getAttribute(Constants.USE_LIGHTWEIGHT_ACCESS_TOKEN_ENABLED)).thenReturn("true");
        ProtocolMapperModel model = modelWithConfig(Map.of(
                OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN, Boolean.toString(regularIncluded),
                OIDCAttributeMapperHelper.INCLUDE_IN_LIGHTWEIGHT_ACCESS_TOKEN, Boolean.toString(lightweightIncluded)));

        mapper.transformAccessToken(token, model, fixture.session(), null, null);

        if (lightweightIncluded) {
            assertEquals(ORGANIZATION_NAME, organizationClaims(token).get("name"));
        } else {
            assertSame(original, token.getOtherClaims().get("organization"));
            verifyNoInteractions(fixture.provider());
        }
    }

    @Test
    void lightweightTokensDoNotInheritRegularAccessTokenInclusion() {
        AccessToken token = new AccessToken();
        Object original = Map.of(ORGANIZATION_ALIAS, attributes());
        token.getOtherClaims().put("organization", original);
        SessionFixture fixture = fixture(enabledOrganization(ORGANIZATION_NAME));
        when(fixture.client().getAttribute(Constants.USE_LIGHTWEIGHT_ACCESS_TOKEN_ENABLED)).thenReturn("true");

        mapper.transformAccessToken(token, modelFor(TokenTarget.ACCESS_TOKEN, true), fixture.session(), null, null);

        assertSame(original, token.getOtherClaims().get("organization"));
        verifyNoInteractions(fixture.provider());
    }

    private static Map<String, Object> attributes() {
        return Map.of("id", ORGANIZATION_ID, "zip", List.of("12345"),
                "type", List.of("association"), "metadata", Map.of("keep", "value"));
    }

    private static ObjectNode objectClaim(Map<?, ?> organizations) {
        return JsonSerialization.mapper.valueToTree(organizations);
    }

    private static OrganizationModel enabledOrganization(String name) {
        OrganizationModel organization = mock(OrganizationModel.class);
        when(organization.isEnabled()).thenReturn(true);
        when(organization.getName()).thenReturn(name);
        return organization;
    }

    private SessionFixture fixture(OrganizationModel organization) {
        return fixture(Map.of(ORGANIZATION_ALIAS, organization));
    }

    private SessionFixture fixture(Map<String, OrganizationModel> organizations) {
        KeycloakSession session = mock(KeycloakSession.class);
        KeycloakContext context = mock(KeycloakContext.class);
        ClientModel client = mock(ClientModel.class);
        OrganizationProvider provider = mock(OrganizationProvider.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(mock(RealmModel.class));
        when(context.getClient()).thenReturn(client);
        when(session.getProvider(OrganizationProvider.class)).thenReturn(provider);
        organizations.forEach((alias, organization) -> when(provider.getByAlias(alias)).thenReturn(organization));
        return new SessionFixture(session, context, client, provider);
    }

    private static ProtocolMapperModel modelWithConfig(Map<String, String> config) {
        ProtocolMapperModel model = new ProtocolMapperModel();
        model.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
        model.setProtocolMapper(OrganizationNameProtocolMapper.PROVIDER_ID);
        model.setConfig(new HashMap<>(config));
        return model;
    }

    private static ProtocolMapperModel modelFor(TokenTarget target, boolean included) {
        return modelWithConfig(Map.of(target.configKey, Boolean.toString(included)));
    }

    private static IDToken tokenWithClaim(Object organizations) {
        IDToken token = new IDToken();
        token.getOtherClaims().put("organization", organizations);
        return token;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> organizationEntries(IDToken token) {
        return (Map<String, Object>) token.getOtherClaims().get("organization");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> organizationClaims(IDToken token) {
        return (Map<String, Object>) organizationEntries(token).get(ORGANIZATION_ALIAS);
    }

    private record SessionFixture(KeycloakSession session, KeycloakContext context, ClientModel client,
                                  OrganizationProvider provider) {
    }

    private enum TokenTarget {
        ACCESS_TOKEN(OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN),
        ID_TOKEN(OIDCAttributeMapperHelper.INCLUDE_IN_ID_TOKEN),
        USER_INFO(OIDCAttributeMapperHelper.INCLUDE_IN_USERINFO),
        INTROSPECTION(OIDCAttributeMapperHelper.INCLUDE_IN_INTROSPECTION);

        private final String configKey;

        TokenTarget(String configKey) {
            this.configKey = configKey;
        }

        IDToken newToken() {
            return this == ID_TOKEN ? new IDToken() : new AccessToken();
        }

        IDToken transform(AbstractOIDCProtocolMapper mapper, IDToken token, ProtocolMapperModel model,
                          KeycloakSession session, UserSessionModel userSession) {
            return switch (this) {
                case ACCESS_TOKEN ->
                        mapper.transformAccessToken((AccessToken) token, model, session, userSession, null);
                case ID_TOKEN -> mapper.transformIDToken(token, model, session, userSession, null);
                case USER_INFO -> mapper.transformUserInfoToken((AccessToken) token, model, session, userSession, null);
                case INTROSPECTION ->
                        mapper.transformIntrospectionToken((AccessToken) token, model, session, userSession, null);
            };
        }
    }
}
