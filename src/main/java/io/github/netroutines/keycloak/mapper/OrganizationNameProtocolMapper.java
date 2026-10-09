package io.github.netroutines.keycloak.mapper;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.TokenIntrospectionTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;
import org.keycloak.util.JsonSerialization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OrganizationNameProtocolMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper,
        OIDCIDTokenMapper,
        UserInfoTokenMapper,
        TokenIntrospectionTokenMapper {

    public static final String PROVIDER_ID = "oidc-organization-name-mapper";

    private static final String ORGANIZATION_CLAIM = "organization";
    private static final String ORGANIZATION_NAME = "name";

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = new ArrayList<>();

    static {
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(
                CONFIG_PROPERTIES,
                OrganizationNameProtocolMapper.class
        );
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Organization Name";
    }

    @Override
    public String getDisplayCategory() {
        return "Organization";
    }

    @Override
    public String getHelpText() {
        return "Adds the organization display name to an existing Organization Membership claim.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public ProtocolMapperModel getEffectiveModel(
            KeycloakSession session,
            RealmModel realm,
            ProtocolMapperModel model) {

        ProtocolMapperModel effectiveModel = new ProtocolMapperModel(model);

        if (effectiveModel.getConfig() == null) {
            effectiveModel.setConfig(new HashMap<>());
        }

        effectiveModel.getConfig().put(
                OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME,
                ORGANIZATION_CLAIM
        );

        return effectiveModel;
    }

    @Override
    protected void setClaim(
            IDToken token,
            ProtocolMapperModel model,
            UserSessionModel userSession,
            KeycloakSession session,
            ClientSessionContext clientSessionCtx) {

        Object existingClaim = token.getOtherClaims().get(ORGANIZATION_CLAIM);
        Map<?, ?> organizations;

        if (existingClaim instanceof ObjectNode) {
            organizations = JsonSerialization.mapper.convertValue(existingClaim, Map.class);
        } else if (existingClaim instanceof Map<?, ?> claims) {
            organizations = claims;
        } else {
            // Leave String, Collection, scalar and other unsupported claims untouched.
            return;
        }

        if (organizations.isEmpty()) {
            return;
        }

        OrganizationProvider organizationProvider =
                session.getProvider(OrganizationProvider.class);

        if (organizationProvider == null) {
            return;
        }

        Map<Object, Object> enrichedOrganizations = new LinkedHashMap<>(organizations);
        boolean enriched = false;

        // Existing aliases are trusted membership output. Enrich these entries only;
        // membership and requested scopes are intentionally not revalidated here.
        for (Map.Entry<?, ?> entry : organizations.entrySet()) {
            if (!(entry.getKey() instanceof String alias)
                    || !(entry.getValue() instanceof Map<?, ?> claims)) {
                continue;
            }

            OrganizationModel organization = organizationProvider.getByAlias(alias);

            if (organization == null || !organization.isEnabled()) {
                continue;
            }

            Map<Object, Object> enrichedClaims = new LinkedHashMap<>(claims);
            enrichedClaims.put(ORGANIZATION_NAME, organization.getName());
            enrichedOrganizations.put(alias, enrichedClaims);
            enriched = true;
        }

        if (enriched) {
            // Store the completed object directly: imported mapper conversion options
            // must not serialize a stale copy, stringify it or make it multivalued.
            token.getOtherClaims().put(ORGANIZATION_CLAIM, enrichedOrganizations);
        }
    }
}
