# Organization Name Mapper

[![GitHub Release](https://img.shields.io/github/v/release/netroutines/organization-name-mapper?label=latest%20release)](https://github.com/netroutines/organization-name-mapper/releases/latest)
[![Tests](https://github.com/netroutines/organization-name-mapper/actions/workflows/tests.yml/badge.svg?branch=main)](https://github.com/netroutines/organization-name-mapper/actions/workflows/tests.yml)
[![License](https://img.shields.io/github/license/netroutines/organization-name-mapper)](LICENSE)
![Keycloak](https://img.shields.io/badge/Keycloak%20built%20against-26.7.3-blue)
![Java bytecode](https://img.shields.io/badge/Java%20bytecode-17-blue)

Custom OIDC protocol mapper that adds the organization display name to an existing structured Keycloak `organization`
claim.

It is intended as a temporary solution until native organization-name mapping is available in the deployed Keycloak
version.

## Example

Before:

```json
{
  "organization": {
    "acme": {
      "id": "00000000-0000-0000-0000-000000000001",
      "type": [
        "example"
      ]
    }
  }
}
```

After:

```json
{
  "organization": {
    "acme": {
      "id": "00000000-0000-0000-0000-000000000001",
      "name": "ACME Corporation",
      "type": [
        "example"
      ]
    }
  }
}
```

The mapper only augments an already structured `organization` claim represented as a Java map or a JSON object
(`ObjectNode`). `String`, `List`, other `Collection`, scalar, array, and other unsupported claim representations remain
unchanged.

Immutable outer and nested maps are supported. Imported/manual `jsonType.label`, `multivalued`, and claim-name settings
do not change the fixed structured output.

The nested `name` field is reserved by this mapper. For a resolvable, enabled organization, the display-name string
overwrites any existing nested `name` value, including a custom organization attribute with that name.

All other existing organization attributes are preserved. Missing or disabled organizations retain their existing
entries unchanged.

### Claim provenance and trust boundary

This mapper does not add new organization aliases: it enriches only aliases already present in the `organization` claim.

Existing claim keys are trusted to have been produced by a trusted membership mapper. This mapper does not independently
revalidate user membership or requested organization scopes.

Configure `organization` to be produced by trusted membership mappers; do not populate it from untrusted or
user-controlled data. If another mapper supplies an alias, this mapper can add that enabled organization's display name
even when the alias was not selected by the built-in `Organization Membership` mapper.

## Compatibility

| Mapper version | Built against | Runtime verified on |
|----------------|---------------|---------------------|
| 1.0.0          | 26.7.3        | 26.7.3              |

The provider version and the Keycloak version are independent.

Version `1.0.0` was compiled against and runtime-tested on Keycloak `26.7.3`. This is the initial verified compatibility
baseline; it does not imply that earlier Keycloak versions are incompatible or that all later versions are automatically
compatible.

The same released provider JAR may also work unchanged with later Keycloak versions. A Keycloak version should be
considered runtime-verified only after the released JAR has been tested against that version.

This provider depends on Keycloak server APIs, including private SPI APIs. Compatibility across Keycloak releases must
therefore not be assumed solely from the Keycloak major or minor version.

If the unchanged released JAR is successfully verified against another Keycloak version, no new provider release is
required. The additional Keycloak version can be documented as runtime-verified for the existing provider release.

Create a new provider release only when the provider source, build configuration, or compiled compatibility requirements
must change.

Do not rebuild or replace an existing released artifact under the same provider version.

## Using the provider

This section is intended for operators integrating a released provider JAR into an existing Keycloak deployment.

### Install

Use a provider release whose runtime compatibility has been verified for the Keycloak version used by the deployment.

Copy the JAR into the Keycloak providers directory before building the server image:

```dockerfile
COPY organization-name-mapper-1.0.0.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build
```

Only one version of this provider should exist in `/opt/keycloak/providers/`.

After installing or replacing the provider:

1. Build a new Keycloak container image.
2. Deploy the new image using the existing persistent database and deployment configuration.
3. Verify that Keycloak starts successfully.
4. Verify the generated token.

Building and redeploying the container image does not require recreating realm, client, organization, or mapper
configuration when the deployment continues to use the same persistent Keycloak database.

Runtime configuration such as environment variables, secrets, configuration files, and volumes must still be preserved
by the deployment.

### Configure

In the Keycloak Admin Console:

`Client scopes` → `organization` → `Mappers` → `Add mapper` → `By configuration` → `Organization Name`

Give the mapper a descriptive name, for example:

```text
organization-name
```

Enable the required token targets: Access Token, ID Token, UserInfo, and/or token introspection.

The standard `Organization Membership` mapper must produce the structured JSON `organization` claim on the **same token
target** being enriched. Enable the corresponding target on both mappers.

UserInfo and introspection build their own claim output; an organization claim in the access token alone is not
sufficient.

For lightweight access tokens, enable `Add to lightweight access token` on both mappers. That setting is separate from
regular Access Token inclusion and defaults to disabled.

For Keycloak 26.7.3, keep `Multivalued` enabled and use a structured organization claim, for example by enabling
`Add organization id` or `Add organization attributes`.

Verify that the generated token contains:

```text
organization.<organization-alias>.name
```

### Remove

To remove the provider:

1. Remove the custom `Organization Name` mapper from the client scope.
2. Remove the provider JAR from `/opt/keycloak/providers/`.
3. Build a new Keycloak image.
4. Redeploy the image using the existing database and deployment configuration.
5. Verify that Keycloak starts successfully.
6. Verify the generated token.

## Maintaining the provider

This section is intended for building, testing, validating compatibility, and releasing the provider.

### Build and test

The provider version and Keycloak build-time dependency version must be explicitly defined in `pom.xml`, for example:

```xml
<version>1.0.0</version>
<keycloak.version>26.7.3</keycloak.version>
```

Do not dynamically determine the Keycloak dependency version for a release build. The committed `pom.xml` must identify
the exact Keycloak version against which that provider artifact was compiled.

Runtime compatibility with additional Keycloak versions may be established later by testing the unchanged released JAR
against those versions.

To run only the tests:

```bash
mvn clean test
```

To run the tests and build the provider:

```bash
mvn clean package
```

`mvn clean package` already executes the test suite, so running `mvn clean test` immediately before it is optional.

For version `1.0.0`, the generated artifact is:

```text
target/organization-name-mapper-1.0.0.jar
```

### Verify the build

After the final build, inspect the JAR contents:

```bash
jar tf target/organization-name-mapper-1.0.0.jar
```

The JAR should contain at least:

```text
io/github/netroutines/keycloak/mapper/OrganizationNameProtocolMapper.class
META-INF/services/org.keycloak.protocol.ProtocolMapper
META-INF/LICENSE
```

Calculate the SHA-256 checksum only after the final build.

#### Linux

```bash
sha256sum target/organization-name-mapper-1.0.0.jar
```

#### Windows

```powershell
Get-FileHash .\target\organization-name-mapper-1.0.0.jar -Algorithm SHA256
```

Do not rebuild the artifact after recording the checksum for a release.

The checksum should be published together with the release so that the deployed artifact can be verified.

### Keycloak upgrades

When the deployed Keycloak version changes, first verify the existing released provider JAR against the target Keycloak
version.

Recommended procedure:

1. Review the migration requirements for the target Keycloak version.
2. Back up the Keycloak database before performing a Keycloak server upgrade.
3. Use the existing released provider JAR unchanged in a test deployment running the target Keycloak version.
4. Run `/opt/keycloak/bin/kc.sh build` while building the new Keycloak image.
5. Verify that Keycloak starts successfully.
6. Verify that the `Organization Name` mapper is discovered and available in the Admin Console.
7. Verify that the standard `Organization Membership` mapper still produces the expected structured `organization`
   claim.
8. Verify that the generated token still contains:

   ```text
   organization.<organization-alias>.name
   ```

9. Optionally validate source-level compatibility against the target Keycloak dependencies without changing the
   committed build baseline:

   ```bash
   mvn -Dkeycloak.version=<target-keycloak-version> clean test
   ```

If the unchanged released JAR works correctly, no new provider release is required. The target Keycloak version can be
recorded as an additionally runtime-verified version for that provider release.

If the existing JAR is incompatible, or if source changes or recompilation against new Keycloak APIs are required,
create a new provider release.

For example, if compatibility with Keycloak `26.8.0` requires rebuilding or changing the provider, the new release could
use:

```xml
<version>1.0.1</version>
<keycloak.version>26.8.0</keycloak.version>
```

and produce:

```text
organization-name-mapper-1.0.1.jar
```

The provider version should reflect changes to this provider, not merely the fact that the deployed Keycloak version
changed.

Never keep multiple versions of this provider in the same Keycloak image.

A Keycloak upgrade does not require building Keycloak itself from source. It requires validating this provider against
the target Keycloak version and building a new Keycloak server/container image containing a compatible provider JAR.

### Provider releases

Create a new provider release when the provider source, build configuration, or compiled compatibility requirements
change.

For each new provider release:

1. Update the provider version in `pom.xml`.
2. Set `keycloak.version` to the exact Keycloak version used as the build-time reference.
3. Run `mvn clean package`.
4. Inspect the generated JAR.
5. Calculate its SHA-256 checksum.
6. Commit the release changes.
7. Create the corresponding Git tag using the provider version, for example `1.0.1`.
8. Create a GitHub release from that tag.
9. Attach the exact JAR that was verified.
10. Include the build-time Keycloak version, runtime-verified Keycloak versions, and SHA-256 checksum in the release
    notes.

Do not create a new provider release solely because another Keycloak version has been verified as compatible with the
unchanged existing JAR.

Do not run another build between calculating the checksum and uploading the release artifact.

### Migration to native Keycloak support

Once the deployed Keycloak release provides the native `Add organization name` option:

1. Enable the native option and verify the generated token.
2. Remove the custom `Organization Name` mapper.
3. Remove this provider JAR from the Keycloak image.
4. Build and redeploy the Keycloak image without the custom provider.
5. Verify the generated token again.

The custom provider should not remain installed once the native functionality is being used.

## Upstream

The corresponding Keycloak contribution is tracked in:

https://github.com/keycloak/keycloak/pull/51430

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).

_Keycloak™ is a trademark of The Linux Foundation and is used here for identification and compatibility purposes only._
